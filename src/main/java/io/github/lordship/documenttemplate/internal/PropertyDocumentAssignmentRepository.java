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
public class PropertyDocumentAssignmentRepository {

    // The two enums are not patchable: they come from the template, and the
    // composite foreign key exists to keep them that way.
    private static final Set<String> PATCHABLE_COLUMNS = Set.of("note");

    private final JdbcClient jdbc;
    private final PropertyDocumentAssignmentRowMapper rowMapper = new PropertyDocumentAssignmentRowMapper();

    public PropertyDocumentAssignmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The agreement and instrument types are copied down from the template
     * rather than supplied, so the caller cannot claim a lease template is a
     * notice. The composite FK to document_template refuses the row if they
     * ever disagree.
     */
    public PropertyDocumentAssignmentRow save(UUID propertyId,
                                              UUID documentTemplateId,
                                              AgreementType agreementType,
                                              InstrumentType instrumentType,
                                              UUID createdBy) {
        return jdbc.sql("""
                        INSERT INTO property_document_assignment (
                            property, document_template, agreement_type, instrument_type, created_by
                        ) VALUES (
                            :propertyId,
                            :documentTemplateId,
                            CAST(:agreementType AS agreement_type),
                            CAST(:instrumentType AS instrument_type),
                            :createdBy
                        )
                        RETURNING *
                        """)
                .param("propertyId", propertyId)
                .param("documentTemplateId", documentTemplateId)
                .param("agreementType", agreementType.name())
                .param("instrumentType", instrumentType.name())
                .param("createdBy", createdBy)
                .query(rowMapper)
                .single();
    }

    public Optional<PropertyDocumentAssignmentRow> findById(UUID uuid) {
        return jdbc.sql("""
                        SELECT * FROM property_document_assignment
                         WHERE uuid = :uuid AND deleted_at IS NULL
                        """)
                .param("uuid", uuid)
                .query(rowMapper)
                .optional();
    }

    /** Everything one park may generate, ordered so a list reads predictably. */
    public List<PropertyDocumentAssignmentRow> findByProperty(UUID propertyId) {
        return jdbc.sql("""
                        SELECT * FROM property_document_assignment
                         WHERE property = :propertyId AND deleted_at IS NULL
                         ORDER BY agreement_type, instrument_type
                        """)
                .param("propertyId", propertyId)
                .query(rowMapper)
                .list();
    }

    /**
     * The one document this park uses for this kind of deal. Empty means the
     * park cannot generate that document at all -- which is the answer generate
     * needs, and the check the assign path makes before creating a duplicate the
     * unique index would refuse anyway.
     */
    public Optional<PropertyDocumentAssignmentRow> findByPropertyAndKind(UUID propertyId,
                                                                         AgreementType agreementType,
                                                                         InstrumentType instrumentType) {
        return jdbc.sql("""
                        SELECT * FROM property_document_assignment
                         WHERE property = :propertyId
                           AND agreement_type = CAST(:agreementType AS agreement_type)
                           AND instrument_type = CAST(:instrumentType AS instrument_type)
                           AND deleted_at IS NULL
                        """)
                .param("propertyId", propertyId)
                .param("agreementType", agreementType.name())
                .param("instrumentType", instrumentType.name())
                .query(rowMapper)
                .optional();
    }

    /** Which parks a document is in use at -- the list behind a refusal to retire it. */
    public List<PropertyDocumentAssignmentRow> findByDocumentTemplate(UUID documentTemplateId) {
        return jdbc.sql("""
                        SELECT * FROM property_document_assignment
                         WHERE document_template = :documentTemplateId AND deleted_at IS NULL
                         ORDER BY created_at
                        """)
                .param("documentTemplateId", documentTemplateId)
                .query(rowMapper)
                .list();
    }

    public Optional<PropertyDocumentAssignmentRow> patch(UUID uuid, Map<String, Object> changes) {
        if (changes.isEmpty()) return findById(uuid);

        for (String col : changes.keySet()) {
            if (!PATCHABLE_COLUMNS.contains(col)) {
                throw new IllegalArgumentException("Invalid column: " + col);
            }
        }

        StringBuilder sql = new StringBuilder("UPDATE property_document_assignment SET ");
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

    public boolean softDelete(UUID uuid) {
        return jdbc.sql("""
                        UPDATE property_document_assignment SET deleted_at = CURRENT_TIMESTAMP
                         WHERE uuid = :uuid AND deleted_at IS NULL
                        """)
                .param("uuid", uuid)
                .update() > 0;
    }

    // ---- every park, and restoring -------------------------------------------

    /** Every live assignment of every park, for the list of documents. Few rows: parks times kinds of paper. */
    public List<PropertyDocumentAssignmentRow> findAllLive() {
        return jdbc.sql("""
                        SELECT * FROM property_document_assignment
                         WHERE deleted_at IS NULL
                         ORDER BY property, agreement_type, instrument_type
                        """)
                .query(rowMapper)
                .list();
    }

    /** An assignment live or ended, for a restore. */
    public Optional<PropertyDocumentAssignmentRow> findAnyById(UUID uuid) {
        return jdbc.sql("SELECT * FROM property_document_assignment WHERE uuid = :uuid")
                .param("uuid", uuid)
                .query(rowMapper)
                .optional();
    }

    /** Empty when it was not ended. Its customizations were never touched, so they come back with it. */
    public Optional<PropertyDocumentAssignmentRow> restore(UUID uuid) {
        return jdbc.sql("""
                        UPDATE property_document_assignment SET deleted_at = NULL
                         WHERE uuid = :uuid AND deleted_at IS NOT NULL
                         RETURNING *
                        """)
                .param("uuid", uuid)
                .query(rowMapper)
                .optional();
    }
}
