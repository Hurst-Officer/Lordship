package io.github.lordship.instruments.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The frozen packet's sections. No patch and no soft delete: this table is a
 * snapshot of what was printed, and the only legitimate way to change what a
 * document says is a new instrument.
 *
 * <p>{@link #deleteByInstrument(UUID)} is the one exception, and it is a hard
 * delete rather than an edit -- re-freezing a DRAFT throws the previous attempt
 * away entirely. Whether the instrument is still a draft is the service's to
 * check; the repository will not know.
 */
@Repository
public class InstrumentSectionRepository {

    private final JdbcClient jdbc;

    public InstrumentSectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** uuid and created_at are assigned by the database, so the row's own values are ignored. */
    public InstrumentSectionRow save(InstrumentSectionRow row) {
        return jdbc.sql("""
                        INSERT INTO instrument_section (
                            instrument, ordinal, name, section_key,
                            signature_block, listed_as_addendum, statute_ref
                        ) VALUES (
                            :instrument, :ordinal, :name, :sectionKey,
                            :signatureBlock, :listedAsAddendum, :statuteRef
                        )
                        RETURNING *
                        """)
                .param("instrument", row.instrument())
                .param("ordinal", row.ordinal())
                .param("name", row.name())
                .param("sectionKey", row.sectionKey())
                .param("signatureBlock", row.signatureBlock())
                .param("listedAsAddendum", row.listedAsAddendum())
                .param("statuteRef", row.statuteRef())
                .query(InstrumentSectionRow.class)
                .single();
    }

    public Optional<InstrumentSectionRow> findById(UUID uuid) {
        return jdbc.sql("SELECT * FROM instrument_section WHERE uuid = :uuid")
                .param("uuid", uuid)
                .query(InstrumentSectionRow.class)
                .optional();
    }

    /** Print order. Ordinals here are 1..n, assigned at the freeze. */
    public List<InstrumentSectionRow> findByInstrument(UUID instrument) {
        return jdbc.sql("""
                        SELECT * FROM instrument_section
                         WHERE instrument = :instrument
                         ORDER BY ordinal
                        """)
                .param("instrument", instrument)
                .query(InstrumentSectionRow.class)
                .list();
    }

    /** Clauses reference sections, so delete those first. */
    public int deleteByInstrument(UUID instrument) {
        return jdbc.sql("DELETE FROM instrument_section WHERE instrument = :instrument")
                .param("instrument", instrument)
                .update();
    }
}
