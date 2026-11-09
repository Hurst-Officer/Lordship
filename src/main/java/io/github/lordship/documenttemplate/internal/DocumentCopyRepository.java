package io.github.lordship.documenttemplate.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Copies one document's live wording into another: its styles, its sections and their clauses.
 *
 * <p>How next year's lease is started. The new document gets new ids for everything, and every link
 * inside it -- a clause's parent, its variant, the clause it must sit above, its style, and each
 * {@code {{ref:<uuid>}}} its body cites -- is pointed at the copy, never back at the original. Deleted
 * rows are left behind.
 *
 * <p>Nothing about parks is copied: assignments and their customizations belong to the original, and a
 * park moves to the new document by being assigned it.
 */
@Repository
public class DocumentCopyRepository {

    // the same shape as TokenSyntax.REF, kept here so the copy can rewrite each one in place
    private static final Pattern REF = Pattern.compile(
            "\\{\\{(\\s*)ref:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(\\s*)}}");

    private final JdbcClient jdbc;

    public DocumentCopyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private record Pair(UUID oldId, UUID newId) { }

    /**
     * Copies everything live under {@code from} into {@code to}, which must already exist and be empty.
     * Returns the new id of every row copied, by its old id.
     */
    public Map<UUID, UUID> copyWording(UUID from, UUID to, UUID createdBy) {
        // One new id per live row, made by the database so they are uuidv7 like every other id
        Map<UUID, UUID> ids = new HashMap<>();
        for (Pair pair : jdbc.sql("""
                        SELECT uuid AS old_id, uuidv7() AS new_id FROM document_style
                         WHERE template = :from AND deleted_at IS NULL
                        UNION ALL
                        SELECT uuid, uuidv7() FROM document_section
                         WHERE template = :from AND deleted_at IS NULL
                        UNION ALL
                        SELECT c.uuid, uuidv7() FROM template_clause c
                          JOIN document_section s ON s.uuid = c.section
                         WHERE s.template = :from AND s.deleted_at IS NULL AND c.deleted_at IS NULL
                        """)
                .param("from", from)
                .query((rs, n) -> new Pair(rs.getObject("old_id", UUID.class), rs.getObject("new_id", UUID.class)))
                .list()) {
            ids.put(pair.oldId(), pair.newId());
        }
        if (ids.isEmpty()) {
            return ids;
        }

        // A temporary map the inserts below join through, dropped with the transaction
        jdbc.sql("CREATE TEMP TABLE copy_ids (old_id UUID PRIMARY KEY, new_id UUID NOT NULL) ON COMMIT DROP").update();
        ids.forEach((oldId, newId) -> jdbc.sql("INSERT INTO copy_ids (old_id, new_id) VALUES (:oldId, :newId)")
                .param("oldId", oldId)
                .param("newId", newId)
                .update());

        jdbc.sql("""
                        INSERT INTO document_style (uuid, template, name, css, target, note, created_by)
                        SELECT m.new_id, :to, s.name, s.css, s.target, s.note, :createdBy
                          FROM document_style s JOIN copy_ids m ON m.old_id = s.uuid
                        """)
                .param("to", to)
                .param("createdBy", createdBy)
                .update();

        jdbc.sql("""
                        INSERT INTO document_section (uuid, template, ordinal, name, section_key, signature_block,
                                                      listed_as_addendum, required, statute_ref, number_formats,
                                                      cite_formats, style, title_style, note, created_by)
                        SELECT m.new_id, :to, s.ordinal, s.name, s.section_key, s.signature_block,
                               s.listed_as_addendum, s.required, s.statute_ref, s.number_formats,
                               s.cite_formats, st.new_id, tt.new_id, s.note, :createdBy
                          FROM document_section s
                          JOIN copy_ids m ON m.old_id = s.uuid
                          LEFT JOIN copy_ids st ON st.old_id = s.style
                          LEFT JOIN copy_ids tt ON tt.old_id = s.title_style
                        """)
                .param("to", to)
                .param("createdBy", createdBy)
                .update();

        // The clauses first without their links to each other: a parent, a variant or the clause it sits above
        // may come later in the list, and each is a foreign key to a row that has to exist already.
        jdbc.sql("""
                        INSERT INTO template_clause (uuid, section, ordinal, clause_key, title, body, numbered,
                                                     style, condition_field, condition_values, required,
                                                     statute_ref, note, created_by)
                        SELECT m.new_id, sm.new_id, c.ordinal, c.clause_key, c.title, c.body, c.numbered,
                               st.new_id, c.condition_field, c.condition_values, c.required,
                               c.statute_ref, c.note, :createdBy
                          FROM template_clause c
                          JOIN copy_ids m ON m.old_id = c.uuid
                          JOIN copy_ids sm ON sm.old_id = c.section
                          LEFT JOIN copy_ids st ON st.old_id = c.style
                        """)
                .param("createdBy", createdBy)
                .update();

        // Then the links. One whose clause was deleted (and so not copied) is left empty.
        jdbc.sql("""
                        UPDATE template_clause n
                           SET parent = p.new_id, variant_of = v.new_id, requires_next = r.new_id
                          FROM template_clause c
                          JOIN copy_ids m ON m.old_id = c.uuid
                          LEFT JOIN copy_ids p ON p.old_id = c.parent
                          LEFT JOIN copy_ids v ON v.old_id = c.variant_of
                          LEFT JOIN copy_ids r ON r.old_id = c.requires_next
                         WHERE n.uuid = m.new_id
                        """)
                .update();

        // And each body's citations, pointed at the copies of the clauses they cite
        List<Body> bodies = jdbc.sql("""
                        SELECT n.uuid, n.body FROM template_clause n
                          JOIN copy_ids m ON m.new_id = n.uuid
                         WHERE n.body LIKE '%ref:%'
                        """)
                .query((rs, n) -> new Body(rs.getObject("uuid", UUID.class), rs.getString("body")))
                .list();
        for (Body body : bodies) {
            String rewritten = rewriteRefs(body.text(), ids);
            if (!rewritten.equals(body.text())) {
                jdbc.sql("UPDATE template_clause SET body = :body WHERE uuid = :uuid")
                        .param("body", rewritten)
                        .param("uuid", body.uuid())
                        .update();
            }
        }
        return ids;
    }

    private record Body(UUID uuid, String text) { }

    /** Each {{ref:<old>}} as {{ref:<new>}}. A citation of a clause that was not copied is left as it was. */
    static String rewriteRefs(String body, Map<UUID, UUID> ids) {
        Matcher ref = REF.matcher(body);
        StringBuilder out = new StringBuilder();
        while (ref.find()) {
            UUID cited = UUID.fromString(ref.group(2));
            UUID copy = ids.get(cited);
            String replacement = copy == null
                    ? ref.group(0)
                    : "{{" + ref.group(1) + "ref:" + copy + ref.group(3) + "}}";
            ref.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        ref.appendTail(out);
        return out.toString();
    }
}
