package io.github.lordship.instruments.internal;

import io.github.lordship.instruments.DocumentFreeze;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of {@code instrument_section} -- a sub-document as it was printed.
 *
 * <p>All simple types, so JdbcClient maps this one without a RowMapper.
 *
 * <p>No {@code required}, and no {@code deletedAt} or {@code updatedAt}: a
 * snapshot is not edited, and whether the section was required is a fact about
 * the template, not about the paper. A required section that ended up empty
 * never became a row at all -- {@code Frozen.omittedRequired} reported it and
 * generation refused.
 */
public record InstrumentSectionRow(
        UUID uuid,
        UUID instrument,
        BigDecimal ordinal,
        String name,
        String sectionKey,
        boolean signatureBlock,
        boolean listedAsAddendum,
        String statuteRef,
        OffsetDateTime createdAt
) {

    /** uuid and createdAt are the database's to assign. */
    public static InstrumentSectionRow from(UUID instrument, DocumentFreeze.FrozenSection section) {
        return new InstrumentSectionRow(
                null,
                instrument,
                section.ordinal(),
                section.name(),
                section.sectionKey(),
                section.signatureBlock(),
                section.listedAsAddendum(),
                section.statuteRef(),
                null
        );
    }
}
