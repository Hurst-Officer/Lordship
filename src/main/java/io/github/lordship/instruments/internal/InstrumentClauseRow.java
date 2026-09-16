package io.github.lordship.instruments.internal;

import io.github.lordship.instruments.ClauseOrigin;
import io.github.lordship.instruments.DocumentFreeze;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of {@code instrument_clause} -- a clause as it was printed, and the
 * same clause with its tokens still in.
 *
 * <p>Keeping both is what makes "did the paper drift from the deal" a
 * mechanical question: re-resolve {@code bodyTemplate} against the term's
 * current values and compare with {@code body}, and a mismatch names the clause
 * where the two diverged.
 *
 * <p>{@code sourceClause} is deliberately not a foreign key. The clause it came
 * from may be edited, retired or soft-deleted later, and this snapshot has to
 * outlive that.
 */
public record InstrumentClauseRow(
        UUID uuid,
        UUID instrument,
        UUID section,
        BigDecimal ordinal,
        String clauseKey,
        String title,
        String body,
        String bodyTemplate,
        String statuteRef,
        ClauseOrigin origin,
        UUID sourceClause,
        OffsetDateTime createdAt
) {

    /** uuid and createdAt are the database's to assign; section is the row just written. */
    public static InstrumentClauseRow from(UUID instrument,
                                           UUID section,
                                           DocumentFreeze.FrozenClause clause,
                                           ClauseOrigin origin) {
        return new InstrumentClauseRow(
                null,
                instrument,
                section,
                clause.ordinal(),
                clause.clauseKey(),
                clause.title(),
                clause.body(),
                clause.bodyTemplate(),
                clause.statuteRef(),
                origin,
                clause.sourceClause(),
                null
        );
    }
}
