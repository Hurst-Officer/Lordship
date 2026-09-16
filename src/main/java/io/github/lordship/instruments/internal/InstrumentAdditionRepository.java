package io.github.lordship.instruments.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Clauses an office worker typed onto one agreement.
 *
 * <p>Not part of the frozen packet -- these are an INPUT to the freeze, held
 * outside it so that re-freezing a draft cannot throw away something somebody
 * typed. That is the whole reason the table exists rather than the words going
 * straight into {@code instrument_clause}.
 */
@Repository
public class InstrumentAdditionRepository {

    // instrument and section are set once: where a sentence goes is what it is.
    // Moving one to a different section is remove-and-retype, which is also the
    // honest record of what happened.
    private static final Set<String> PATCHABLE_COLUMNS = Set.of(
            "ordinal", "title", "body", "note"
    );

    private final JdbcClient jdbc;

    public InstrumentAdditionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Added empty and filled in by a patch, the same as adding a template clause
     * or a park clause -- three screens, one gesture.
     *
     * <p>The ordinal lands it at the end of the section as this document will
     * print it, so the subquery counts the template's clauses, this park's added
     * clauses and this agreement's own. All three share one coordinate space;
     * counting one table would put two clauses on the same number and leave the
     * print order to whichever the sort happened to reach first.
     */
    public InstrumentAdditionRow save(UUID instrument, UUID section, UUID createdBy) {
        return jdbc.sql("""
                        INSERT INTO instrument_addition (instrument, section, ordinal, created_by)
                        VALUES (
                            :instrument,
                            :section,
                            (SELECT COALESCE(MAX(ordinal), 0) + 1 FROM (
                                 SELECT ordinal FROM template_clause
                                  WHERE section = :section AND deleted_at IS NULL
                                 UNION ALL
                                 SELECT c.ordinal FROM property_document_customization c
                                   JOIN property_document_assignment a ON a.uuid = c.assignment
                                   JOIN lot l     ON l.property_id = a.property
                                   JOIN tenancy t ON t.lot_id = l.uuid
                                   JOIN instrument i ON i.tenancy = t.uuid
                                  WHERE i.uuid = :instrument
                                    AND c.action = 'ADD_CLAUSE'
                                    AND c.section = :section
                                    AND c.deleted_at IS NULL
                                    AND a.deleted_at IS NULL
                                 UNION ALL
                                 SELECT ordinal FROM instrument_addition
                                  WHERE instrument = :instrument
                                    AND section = :section
                                    AND deleted_at IS NULL
                             ) taken),
                            :createdBy
                        )
                        RETURNING *
                        """)
                .param("instrument", instrument)
                .param("section", section)
                .param("createdBy", createdBy)
                .query(InstrumentAdditionRow.class)
                .single();
    }

    public Optional<InstrumentAdditionRow> findById(UUID uuid) {
        return jdbc.sql("""
                        SELECT * FROM instrument_addition
                         WHERE uuid = :uuid AND deleted_at IS NULL
                        """)
                .param("uuid", uuid)
                .query(InstrumentAdditionRow.class)
                .optional();
    }

    /** Everything typed onto this one agreement -- what the freeze reads. */
    public List<InstrumentAdditionRow> findByInstrument(UUID instrument) {
        return jdbc.sql("""
                        SELECT * FROM instrument_addition
                         WHERE instrument = :instrument AND deleted_at IS NULL
                         ORDER BY section, ordinal
                        """)
                .param("instrument", instrument)
                .query(InstrumentAdditionRow.class)
                .list();
    }

    public Optional<InstrumentAdditionRow> patch(UUID uuid, Map<String, Object> changes) {
        if (changes.isEmpty()) return findById(uuid);

        for (String col : changes.keySet()) {
            if (!PATCHABLE_COLUMNS.contains(col)) {
                throw new IllegalArgumentException("Invalid column: " + col);
            }
        }

        StringBuilder sql = new StringBuilder("UPDATE instrument_addition SET ");
        changes.forEach((col, val) -> sql.append(col).append(" = :").append(col).append(", "));
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *");

        Map<String, Object> params = new HashMap<>(changes);
        params.put("uuid", uuid);

        return jdbc.sql(sql.toString())
                .params(params)
                .query(InstrumentAdditionRow.class)
                .optional();
    }

    public boolean softDelete(UUID uuid) {
        return jdbc.sql("""
                        UPDATE instrument_addition SET deleted_at = CURRENT_TIMESTAMP
                         WHERE uuid = :uuid AND deleted_at IS NULL
                        """)
                .param("uuid", uuid)
                .update() > 0;
    }
}
