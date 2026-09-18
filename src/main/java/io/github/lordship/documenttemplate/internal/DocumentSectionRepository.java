package io.github.lordship.documenttemplate.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class DocumentSectionRepository {

    private static final Set<String> PATCHABLE_COLUMNS = Set.of(
            "ordinal", "name", "section_key", "signature_block",
            "listed_as_addendum", "required", "statute_ref", "note",
            "number_formats", "cite_formats", "style", "title_style"
    );

    private final JdbcClient jdbc;
    private final DocumentSectionRowMapper rowMapper;

    public DocumentSectionRepository(JdbcClient jdbc, DocumentSectionRowMapper documentSectionRowMapper) {
        this.jdbc = jdbc;
        this.rowMapper = documentSectionRowMapper;
    }

    /**
     * A section needs a name and nothing else. Ordinal lands it at the end of
     * the packet, assigned here rather than by the client -- the same shape as
     * lot.sort_order. Reordering is a PATCH with a decimal, since ordinals are
     * sparse: 12.5 sits between 12 and 13 and nothing else moves.
     */
    public DocumentSectionRow save(UUID templateId, String name, UUID createdBy) {
        return jdbc.sql("""
                        INSERT INTO document_section (template, ordinal, name, created_by)
                        VALUES (
                            :templateId,
                            (SELECT COALESCE(MAX(ordinal), 0) + 1
                               FROM document_section
                              WHERE template = :templateId AND deleted_at IS NULL),
                            :name,
                            :createdBy
                        )
                        RETURNING *
                        """)
                .param("templateId", templateId)
                .param("name", name)
                .param("createdBy", createdBy)
                .query(rowMapper)
                .single();
    }

    public Optional<DocumentSectionRow> findById(UUID uuid) {
        return jdbc.sql("SELECT * FROM document_section WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .query(rowMapper)
                .optional();
    }

    public List<DocumentSectionRow> findByTemplate(UUID templateId) {
        return jdbc.sql("""
                        SELECT * FROM document_section
                         WHERE template = :templateId AND deleted_at IS NULL
                         ORDER BY ordinal
                        """)
                .param("templateId", templateId)
                .query(rowMapper)
                .list();
    }

    public Optional<DocumentSectionRow> patch(UUID uuid, Map<String, Object> changes) {
        if (changes.isEmpty()) return findById(uuid);

        for (String col : changes.keySet()) {
            if (!PATCHABLE_COLUMNS.contains(col)) {
                throw new IllegalArgumentException("Invalid column: " + col);
            }
        }

        StringBuilder sql = new StringBuilder("UPDATE document_section SET ");
        changes.forEach((col, val) -> {
            sql.append(col).append(" = :").append(col);
            // The service hands these over as String[]; the driver needs telling it is a text[].
            if ("number_formats".equals(col) || "cite_formats".equals(col)) {
                sql.append("::text[]");
            }
            sql.append(", ");
        });
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *");

        Map<String, Object> params = new HashMap<>(changes);
        params.put("uuid", uuid);

        return jdbc.sql(sql.toString())
                .params(params)
                .query(rowMapper)
                .optional();
    }

    public boolean softDelete(UUID uuid) {
        return jdbc.sql("""
                        UPDATE document_section SET deleted_at = CURRENT_TIMESTAMP
                         WHERE uuid = :uuid AND deleted_at IS NULL
                        """)
                .param("uuid", uuid)
                .update() > 0;
    }
}
