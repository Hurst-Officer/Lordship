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
public class DocumentStyleRepository {

    private static final Set<String> PATCHABLE_COLUMNS = Set.of("name", "css", "target", "note");

    private final JdbcClient jdbc;

    public DocumentStyleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A style needs a name; the css starts empty and arrives by PATCH, like a clause body. */
    public DocumentStyleRow save(UUID templateId, String name, UUID createdBy) {
        return jdbc.sql("""
                        INSERT INTO document_style (template, name, css, created_by)
                        VALUES (:templateId, :name, '', :createdBy)
                        RETURNING *
                        """)
                .param("templateId", templateId)
                .param("name", name)
                .param("createdBy", createdBy)
                .query(DocumentStyleRow.class)
                .single();
    }

    public Optional<DocumentStyleRow> findById(UUID uuid) {
        return jdbc.sql("SELECT * FROM document_style WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .query(DocumentStyleRow.class)
                .optional();
    }

    public List<DocumentStyleRow> findByTemplate(UUID templateId) {
        return jdbc.sql("""
                        SELECT * FROM document_style
                         WHERE template = :templateId AND deleted_at IS NULL
                         ORDER BY target NULLS LAST, name
                        """)
                .param("templateId", templateId)
                .query(DocumentStyleRow.class)
                .list();
    }

    /** Whether a live clause or section still wears this style. */
    public boolean isInUse(UUID uuid) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM template_clause
                             WHERE style = :uuid AND deleted_at IS NULL
                            UNION ALL
                            SELECT 1 FROM document_section
                             WHERE (style = :uuid OR title_style = :uuid) AND deleted_at IS NULL
                        )
                        """)
                .param("uuid", uuid)
                .query(Boolean.class)
                .single();
    }

    public Optional<DocumentStyleRow> patch(UUID uuid, Map<String, Object> changes) {
        if (changes.isEmpty()) return findById(uuid);

        for (String col : changes.keySet()) {
            if (!PATCHABLE_COLUMNS.contains(col)) {
                throw new IllegalArgumentException("Invalid column: " + col);
            }
        }

        StringBuilder sql = new StringBuilder("UPDATE document_style SET ");
        changes.forEach((col, val) -> sql.append(col).append(" = :").append(col).append(", "));
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *");

        Map<String, Object> params = new HashMap<>(changes);
        params.put("uuid", uuid);

        return jdbc.sql(sql.toString())
                .params(params)
                .query(DocumentStyleRow.class)
                .optional();
    }

    public boolean softDelete(UUID uuid) {
        return jdbc.sql("""
                        UPDATE document_style SET deleted_at = CURRENT_TIMESTAMP
                         WHERE uuid = :uuid AND deleted_at IS NULL
                        """)
                .param("uuid", uuid)
                .update() > 0;
    }
}
