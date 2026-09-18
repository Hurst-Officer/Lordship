package io.github.lordship.instruments.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * The words on the page. Written at the freeze and never edited -- there is no
 * patch and no soft delete, only the hard delete that re-freezing a DRAFT uses.
 *
 * <p>This is also the table that turns thousands of leases from a shelf of PDFs
 * into something answerable: what does clause RENT_AND_FEES say across every
 * lease at this park is a query here.
 */
@Repository
public class InstrumentClauseRepository {

    private final JdbcClient jdbc;
    private final InstrumentClauseRowMapper rowMapper;

    public InstrumentClauseRepository(JdbcClient jdbc, InstrumentClauseRowMapper instrumentClauseRowMapper) {
        this.jdbc = jdbc;
        this.rowMapper = instrumentClauseRowMapper;
    }

    /** uuid and created_at are assigned by the database, so the row's own values are ignored. */
    public InstrumentClauseRow save(InstrumentClauseRow row) {
        return jdbc.sql("""
                        INSERT INTO instrument_clause (
                            instrument, section, ordinal, clause_key, title, label,
                            body, body_template, statute_ref, origin, source_clause
                        ) VALUES (
                            :instrument, :section, :ordinal, :clauseKey, :title, :label,
                            :body, :bodyTemplate, :statuteRef, :origin, :sourceClause
                        )
                        RETURNING *
                        """)
                .param("instrument", row.instrument())
                .param("section", row.section())
                .param("ordinal", row.ordinal())
                .param("clauseKey", row.clauseKey())
                .param("title", row.title())
                .param("label", row.label())
                .param("body", row.body())
                .param("bodyTemplate", row.bodyTemplate())
                .param("statuteRef", row.statuteRef())
                .param("origin", row.origin() == null ? null : row.origin().name())
                .param("sourceClause", row.sourceClause())
                .query(rowMapper)
                .single();
    }

    /**
     * The whole document in print order, sections flattened. Ordered by the
     * section's ordinal first, because a clause's ordinal restarts at 1 in every
     * section and ordering by it alone would interleave the packet.
     */
    public List<InstrumentClauseRow> findByInstrument(UUID instrument) {
        return jdbc.sql("""
                        SELECT c.* FROM instrument_clause c
                          JOIN instrument_section s ON s.uuid = c.section
                         WHERE c.instrument = :instrument
                         ORDER BY s.ordinal, c.ordinal
                        """)
                .param("instrument", instrument)
                .query(rowMapper)
                .list();
    }

    public List<InstrumentClauseRow> findBySection(UUID section) {
        return jdbc.sql("""
                        SELECT * FROM instrument_clause
                         WHERE section = :section
                         ORDER BY ordinal
                        """)
                .param("section", section)
                .query(rowMapper)
                .list();
    }

    /**
     * Every clause on this park's released paper that did not come from the
     * global document -- the park's own rules, and the sentences office workers
     * typed onto individual agreements.
     *
     * <p>The question nobody can answer today: what is out there, in leases
     * tenants have signed, that no one at the template level ever reviewed. Text
     * search cannot ask it, because you would have to already know the words.
     *
     * <p>Released rather than all: a draft is still being written and a clause
     * in one is not yet anybody's obligation.
     */
    public List<InstrumentClauseRow> findAddedClausesReleasedAtProperty(UUID propertyId) {
        return jdbc.sql("""
                        SELECT c.* FROM instrument_clause c
                          JOIN instrument i ON i.uuid = c.instrument
                          JOIN tenancy t    ON t.uuid = i.tenancy AND t.deleted_at IS NULL
                          JOIN lot l        ON l.uuid = t.lot_id  AND l.deleted_at IS NULL
                         WHERE l.property_id = :propertyId
                           AND c.origin <> 'TEMPLATE'
                           AND i.status IN ('SENT', 'SERVED', 'APPROVED')
                         ORDER BY i.created_at DESC, c.ordinal
                        """)
                .param("propertyId", propertyId)
                .query(rowMapper)
                .list();
    }

    /** The same question about one document, for the review screen before it goes out. */
    public List<InstrumentClauseRow> findAddedClausesByInstrument(UUID instrument) {
        return jdbc.sql("""
                        SELECT c.* FROM instrument_clause c
                          JOIN instrument_section s ON s.uuid = c.section
                         WHERE c.instrument = :instrument
                           AND c.origin <> 'TEMPLATE'
                         ORDER BY s.ordinal, c.ordinal
                        """)
                .param("instrument", instrument)
                .query(rowMapper)
                .list();
    }

    public int deleteByInstrument(UUID instrument) {
        return jdbc.sql("DELETE FROM instrument_clause WHERE instrument = :instrument")
                .param("instrument", instrument)
                .update();
    }
}
