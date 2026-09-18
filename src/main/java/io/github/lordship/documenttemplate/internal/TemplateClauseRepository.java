package io.github.lordship.documenttemplate.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class TemplateClauseRepository {

    private static final Set<String> PATCHABLE_COLUMNS = Set.of(
            "ordinal", "clause_key", "title", "body",
            "condition_field", "condition_values",
            "required", "statute_ref", "note",
            "parent", "variant_of", "numbered", "requires_next", "style"
    );

    private final JdbcClient jdbc;
    private final TemplateClauseRowMapper rowMapper = new TemplateClauseRowMapper();

    public TemplateClauseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A clause needs a section and nothing else -- every other column is
     * nullable, so "add clause" is a button rather than a form. Ordinal lands
     * it at the end of the section, assigned here rather than by the client.
     */
    public TemplateClauseRow save(UUID sectionId, UUID createdBy) {
        return jdbc.sql("""
                        INSERT INTO template_clause (section, ordinal, created_by)
                        VALUES (
                            :sectionId,
                            (SELECT COALESCE(MAX(ordinal), 0) + 1
                               FROM template_clause
                              WHERE section = :sectionId AND deleted_at IS NULL),
                            :createdBy
                        )
                        RETURNING *
                        """)
                .param("sectionId", sectionId)
                .param("createdBy", createdBy)
                .query(rowMapper)
                .single();
    }

    public Optional<TemplateClauseRow> findById(UUID uuid) {
        return jdbc.sql("SELECT * FROM template_clause WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .query(rowMapper)
                .optional();
    }

    public List<TemplateClauseRow> findBySection(UUID sectionId) {
        return jdbc.sql("""
                        SELECT * FROM template_clause
                         WHERE section = :sectionId AND deleted_at IS NULL
                         ORDER BY ordinal
                        """)
                .param("sectionId", sectionId)
                .query(rowMapper)
                .list();
    }

    // Hydrating a whole document is two queries, not one per section.
    public List<TemplateClauseRow> findBySectionIds(Collection<UUID> sectionIds) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT * FROM template_clause
                         WHERE section IN (:sectionIds) AND deleted_at IS NULL
                         ORDER BY section, ordinal
                        """)
                .param("sectionIds", sectionIds)
                .query(rowMapper)
                .list();
    }

    // Which documents would break if a token were renamed or retired. Also the
    // cheap way to find every clause that still hardcodes a figure.
    public List<TemplateClauseRow> findReferencingToken(String tokenName) {
        return jdbc.sql("""
                        SELECT * FROM template_clause
                         WHERE deleted_at IS NULL
                           AND body LIKE '%{{' || :tokenName || '}}%'
                         ORDER BY section, ordinal
                        """)
                .param("tokenName", tokenName)
                .query(rowMapper)
                .list();
    }

    public Optional<TemplateClauseRow> patch(UUID uuid, Map<String, Object> changes) {
        if (changes.isEmpty()) return findById(uuid);

        for (String col : changes.keySet()) {
            if (!PATCHABLE_COLUMNS.contains(col)) {
                throw new IllegalArgumentException("Invalid column: " + col);
            }
        }

        StringBuilder sql = new StringBuilder("UPDATE template_clause SET ");
        changes.forEach((col, val) -> {
            sql.append(col).append(" = :").append(col);
            // The driver needs to be told a String[] is a text[], not a record.
            if ("condition_values".equals(col)) {
                sql.append("::text[]");
            }
            sql.append(", ");
        });
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *");

        Map<String, Object> params = new HashMap<>(changes);
        // Not computeIfPresent: it deletes the key when the function returns
        // null, and clearing a condition is exactly the case that returns null.
        // The column is still named in the SET clause, so the parameter has to
        // stay in the map with a null value.
        if (params.containsKey("condition_values")) {
            params.put("condition_values", toStringArray(params.get("condition_values")));
        }
        params.put("uuid", uuid);

        return jdbc.sql(sql.toString())
                .params(params)
                .query(rowMapper)
                .optional();
    }

    public boolean softDelete(UUID uuid) {
        return jdbc.sql("""
                        UPDATE template_clause SET deleted_at = CURRENT_TIMESTAMP
                         WHERE uuid = :uuid AND deleted_at IS NULL
                        """)
                .param("uuid", uuid)
                .update() > 0;
    }

    // JSON gives us a List; Postgres wants an array. An empty list clears the
    // condition rather than storing {}, so a clause can be made unconditional
    // the same way any other field is cleared.
    private static Object toStringArray(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Collection<?> collection) {
            if (collection.isEmpty()) {
                return null;
            }
            return collection.stream().map(String::valueOf).toArray(String[]::new);
        }
        if (value instanceof String[] array) {
            return array.length == 0 ? null : array;
        }
        throw new IllegalArgumentException("conditionValues must be a list of strings");
    }
}