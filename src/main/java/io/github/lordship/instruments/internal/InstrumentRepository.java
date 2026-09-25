package io.github.lordship.instruments.internal;

import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class InstrumentRepository {

    // tenancy, type, agreement_type, created_by and created_at are set once at creation. serial
    // and the provenance columns are stamped by markGenerated; the delivery
    // columns move through their own transitions. None of those go through
    // PATCH, because each one belongs to a CHECK that reads several columns at
    // once and a half-applied patch is what those constraints exist to stop.
    private static final Set<String> PATCHABLE_COLUMNS = Set.of(
            "term_start", "term_months", "on_expiry", "amends", "note"
    );

    private final JdbcClient jdbc;
    private final InstrumentRowMapper rowMapper;

    public InstrumentRepository(JdbcClient jdbc, InstrumentRowMapper instrumentRowMapper) {
        this.jdbc = jdbc;
        this.rowMapper = instrumentRowMapper;
    }

    /**
     * The minimum a document needs to exist: whose tenancy, what kind of paper,
     * and who asked for it. Lands in DRAFT with nothing printed, which is the
     * only state its clauses may be rewritten in.
     */
    public InstrumentRow save(UUID tenancy, InstrumentType type, AgreementType agreementType, UUID createdBy) {
        return save(tenancy, type, agreementType, null, createdBy);
    }

    // termStart may be null. It is filled in for a new lease; see InstrumentService.createDraft.
    public InstrumentRow save(UUID tenancy,
                              InstrumentType type,
                              AgreementType agreementType,
                              LocalDate termStart,
                              UUID createdBy) {
        return jdbc.sql("""
                        INSERT INTO instrument (tenancy, type, agreement_type, term_start, created_by)
                        VALUES (
                            :tenancy,
                            CAST(:type AS instrument_type),
                            CAST(:agreementType AS agreement_type),
                            :termStart,
                            :createdBy
                        )
                        RETURNING *
                        """)
                .param("tenancy", tenancy)
                .param("type", type.name())
                .param("agreementType", agreementType.name())
                .param("termStart", termStart)
                .param("createdBy", createdBy)
                .query(rowMapper)
                .single();
    }

    public Optional<InstrumentRow> findById(UUID uuid) {
        return jdbc.sql("SELECT * FROM instrument WHERE uuid = :uuid")
                .param("uuid", uuid)
                .query(rowMapper)
                .optional();
    }

    /** Type the serial off the paper, get the document. */
    public Optional<InstrumentRow> findBySerial(String serial) {
        return jdbc.sql("SELECT * FROM instrument WHERE serial = :serial")
                .param("serial", serial)
                .query(rowMapper)
                .optional();
    }

    /** The paper history for one tenancy, newest first. */
    public List<InstrumentRow> findByTenancy(UUID tenancy) {
        return jdbc.sql("""
                        SELECT * FROM instrument
                         WHERE tenancy = :tenancy
                         ORDER BY created_at DESC
                        """)
                .param("tenancy", tenancy)
                .query(rowMapper)
                .list();
    }

    /** The office work queue: what paper is out in the field for this tenancy right now. */
    public List<InstrumentRow> findOpenByTenancy(UUID tenancy) {
        return jdbc.sql("""
                        SELECT * FROM instrument
                         WHERE tenancy = :tenancy
                           AND status IN ('GENERATED', 'SENT', 'SERVED')
                         ORDER BY created_at DESC
                        """)
                .param("tenancy", tenancy)
                .query(rowMapper)
                .list();
    }

    public Optional<InstrumentRow> patch(UUID uuid, Map<String, Object> changes) {
        if (changes.isEmpty()) return findById(uuid);

        for (String col : changes.keySet()) {
            if (!PATCHABLE_COLUMNS.contains(col)) {
                throw new IllegalArgumentException("Invalid column: " + col);
            }
        }

        StringBuilder sql = new StringBuilder("UPDATE instrument SET ");
        changes.forEach((col, val) -> sql.append(col).append(" = :").append(col).append(", "));
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE uuid = :uuid RETURNING *");

        Map<String, Object> params = new HashMap<>(changes);
        params.put("uuid", uuid);

        return jdbc.sql(sql.toString())
                .params(params)
                .query(rowMapper)
                .optional();
    }

    /**
     * DRAFT to GENERATED. Serial, provenance, file and timestamp move together
     * because instrument_generated_has_provenance, _has_file and _has_timestamp
     * each read several of them -- setting one at a time is refused by the
     * database, which is the point.
     *
     * <p>Guarded on the current status rather than trusted from the caller: two
     * clicks on Generate should produce one document, not two serials against
     * the same paper.
     */
    public Optional<InstrumentRow> markGenerated(UUID uuid,
                                                 String serial,
                                                 UUID template,
                                                 int templateVersion,
                                                 UUID documentAssignment,
                                                 UUID generatedFile) {
        return jdbc.sql("""
                        UPDATE instrument
                           SET status              = 'GENERATED',
                               serial              = :serial,
                               template            = :template,
                               template_version    = :templateVersion,
                               document_assignment = :documentAssignment,
                               generated_file      = :generatedFile,
                               generated_at        = now()
                         WHERE uuid = :uuid AND status = 'DRAFT'
                         RETURNING *
                        """)
                .param("uuid", uuid)
                .param("serial", serial)
                .param("template", template)
                .param("templateVersion", templateVersion)
                .param("documentAssignment", documentAssignment)
                .param("generatedFile", generatedFile)
                .query(rowMapper)
                .optional();
    }

    /**
     * Paper that never went out, or was replaced before it did. Only from the
     * states where that is true: a document the tenant already has cannot be
     * un-sent, and the correction for one that is wrong is a new instrument.
     */
    public Optional<InstrumentRow> abandon(UUID uuid) {
        return jdbc.sql("""
                        UPDATE instrument
                           SET status = 'ABANDONED'
                         WHERE uuid = :uuid AND status IN ('DRAFT', 'GENERATED')
                         RETURNING *
                        """)
                .param("uuid", uuid)
                .query(rowMapper)
                .optional();
    }
}
