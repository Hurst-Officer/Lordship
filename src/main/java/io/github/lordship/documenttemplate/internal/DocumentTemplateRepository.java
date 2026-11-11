package io.github.lordship.documenttemplate.internal;

import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class DocumentTemplateRepository {

    private static final Set<String> PATCHABLE_COLUMNS = Set.of("name", "note");

    private final JdbcClient jdbc;
    private final DocumentTemplateRowMapper rowMapper = new DocumentTemplateRowMapper();

    public DocumentTemplateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // The minimum a document row needs: the three columns with no default.
    // version starts at 1 and is bumped by the service on any clause change.
    public DocumentTemplateRow save(String name,
                                    AgreementType agreementType,
                                    InstrumentType instrumentType,
                                    UUID createdBy) {
        return jdbc.sql("""
                        INSERT INTO document_template (
                            name, agreement_type, instrument_type, created_by
                        ) VALUES (
                            :name,
                            CAST(:agreementType AS agreement_type),
                            CAST(:instrumentType AS instrument_type),
                            :createdBy
                        )
                        RETURNING *
                        """)
                .param("name", name)
                .param("agreementType", agreementType.name())
                .param("instrumentType", instrumentType.name())
                .param("createdBy", createdBy)
                .query(rowMapper)
                .single();
    }

    public Optional<DocumentTemplateRow> findById(UUID uuid) {
        return jdbc.sql("SELECT * FROM document_template WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .query(rowMapper)
                .optional();
    }

    // Holds the document's row until the transaction ends, so two edits of one document run one after the
    // other, and the second edit is checked against the document the first edit left behind.
    // An unknown id locks nothing.
    public void lockForEdit(UUID uuid) {
        jdbc.sql("SELECT 1 FROM document_template WHERE uuid = :uuid FOR UPDATE")
                .param("uuid", uuid)
                .query(Integer.class)
                .optional();
    }

    /**
     * Both filters optional: the admin's document pool, narrowed or not.
     *
     * <p>Every parameter is cast to text, including inside the IS NULL test.
     * A bare parameter next to IS NULL gives Postgres nothing to infer from --
     * "could not determine data type of parameter $1" -- and it only shows up
     * when the filter is actually omitted. Comparing the enum columns as text
     * rather than casting the parameter to the enum type keeps both halves of
     * each clause on one type. The pool is small enough that not using the enum
     * comparison costs nothing.
     */
    public List<DocumentTemplateRow> findAll(AgreementType agreementType, InstrumentType instrumentType) {
        return jdbc.sql("""
                        SELECT * FROM document_template
                         WHERE deleted_at IS NULL
                           AND (CAST(:agreementType AS text) IS NULL
                                OR CAST(agreement_type AS text) = CAST(:agreementType AS text))
                           AND (CAST(:instrumentType AS text) IS NULL
                                OR CAST(instrument_type AS text) = CAST(:instrumentType AS text))
                         ORDER BY name
                        """)
                .param("agreementType", agreementType == null ? null : agreementType.name())
                .param("instrumentType", instrumentType == null ? null : instrumentType.name())
                .query(rowMapper)
                .list();
    }

    public Optional<DocumentTemplateRow> patch(UUID uuid, Map<String, Object> changes) {
        if (changes.isEmpty()) return findById(uuid);

        for (String col : changes.keySet()) {
            if (!PATCHABLE_COLUMNS.contains(col)) {
                throw new IllegalArgumentException("Invalid column: " + col);
            }
        }

        StringBuilder sql = new StringBuilder("UPDATE document_template SET ");
        changes.forEach((col, val) -> sql.append(col).append(" = :").append(col).append(", "));
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *");

        Map<String, Object> params = new HashMap<>(changes);
        params.put("uuid", uuid);

        return jdbc.sql(sql.toString())
                .params(params)
                .query(rowMapper)
                .optional();
    }

    // Any change to a section or a clause is a change to the document, so the
    // number an instrument records as template_version means something.
    public Optional<DocumentTemplateRow> bumpVersion(UUID uuid) {
        return jdbc.sql("""
                        UPDATE document_template SET version = version + 1
                         WHERE uuid = :uuid AND deleted_at IS NULL
                         RETURNING *
                        """)
                .param("uuid", uuid)
                .query(rowMapper)
                .optional();
    }

    // Retiring a document a park still generates from is how a lease quietly
    // stops being producible; the service turns this into a 409.
    public boolean isAssignedToAnyProperty(UUID uuid) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM property_document_assignment
                             WHERE document_template = :uuid AND deleted_at IS NULL
                        )
                        """)
                .param("uuid", uuid)
                .query(Boolean.class)
                .single();
    }

    public boolean softDelete(UUID uuid) {
        return jdbc.sql("""
                        UPDATE document_template SET deleted_at = CURRENT_TIMESTAMP
                         WHERE uuid = :uuid AND deleted_at IS NULL
                        """)
                .param("uuid", uuid)
                .update() > 0;
    }

    // ---- deleted documents ---------------------------------------------------

    /** A document live or deleted, for a restore. */
    public Optional<DocumentTemplateRow> findAnyById(UUID uuid) {
        return jdbc.sql("SELECT * FROM document_template WHERE uuid = :uuid")
                .param("uuid", uuid)
                .query(rowMapper)
                .optional();
    }

    /** Deleted documents, the most recently deleted first. */
    public List<DocumentTemplateRow> findDeleted() {
        return jdbc.sql("""
                        SELECT * FROM document_template
                         WHERE deleted_at IS NOT NULL
                         ORDER BY deleted_at DESC
                        """)
                .query(rowMapper)
                .list();
    }

    /** Empty when it was not deleted (a coworker restored it a moment earlier). */
    public Optional<DocumentTemplateRow> restore(UUID uuid) {
        return jdbc.sql("""
                        UPDATE document_template SET deleted_at = NULL
                         WHERE uuid = :uuid AND deleted_at IS NOT NULL
                         RETURNING *
                        """)
                .param("uuid", uuid)
                .query(rowMapper)
                .optional();
    }
}
