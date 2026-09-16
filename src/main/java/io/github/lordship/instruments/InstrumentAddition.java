package io.github.lordship.instruments;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A clause the office worker typed onto one agreement while it was still a
 * draft. Not the global document's, not the park's -- this lease only.
 *
 * <p>The shed the seller left behind, which the tenant may keep until June.
 * True of one lease and no other one ever, so there is nothing to author into
 * a template and no park-wide rule to write. Without somewhere to put it the
 * sentence gets handwritten in the margin and never reaches the database at
 * all, which is the outcome this exists to prevent.
 *
 * <p>Deliberately not a row of {@code instrument_clause}. A draft is re-frozen
 * whenever the deal changes, and the freeze discards what it produced last
 * time; a typed clause living in the snapshot would go with it. Held here it is
 * an input to the freeze instead, alongside the template and the park's
 * customizations.
 *
 * <p>There is no matching removal. An assistant may add a sentence to a lease
 * and may not take a mandated disclosure out of one.
 */
public record InstrumentAddition(
        UUID uuid,
        UUID instrument,
        UUID section,
        BigDecimal ordinal,
        String title,
        String body,
        String note,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {
    public boolean isSoftDeleted() {
        return deletedAt != null;
    }
}
