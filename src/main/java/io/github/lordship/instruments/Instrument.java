package io.github.lordship.instruments;

import io.github.lordship.shared.InstrumentType;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

// Component order matches the column order in V9__documents_and_deals.sql.
public record Instrument(
        UUID uuid,
        UUID tenancy,
        InstrumentType type,
        InstrumentStatus status,

        String serial,  // printed on the paper, assigned at GENERATED; typed back in to find the lease
        UUID amends,

        // This document's own period; null on notices and addenda. onExpiry is
        // what THIS paper claims happens next, not the system's renewal record.
        LocalDate termStart,
        Integer termMonths,
        OnExpiry onExpiry,

        // Where the wording came from. What it SAID is in instrument_clause.
        UUID template,
        Integer templateVersion,
        UUID documentAssignment,

        OffsetDateTime generatedAt,
        UUID generatedFile,

        OffsetDateTime sentAt,
        UUID sentBy,

        LocalDate servedOn,
        ServiceMethod serviceMethod,
        UUID servedBy,
        UUID proofFile,

        LocalDate returnedOn,
        UUID returnedFile,

        String note,
        OffsetDateTime createdAt,
        UUID createdBy
) {

    /** Paper that carries a term of its own -- mirrors instrument_lease_has_term. */
    public boolean carriesTerm() {
        return type == InstrumentType.LEASE
                || type == InstrumentType.ASSUMPTION
                || type == InstrumentType.WAIVER;
    }

    /**
     * The day after the last day this document covers.
     *
     * <p>Empty on paper with no term of its own. A notice does not expire.
     */
    public java.util.Optional<LocalDate> termEnd() {
        if (termStart == null || termMonths == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(termStart.plusMonths(termMonths));
    }

    /** The last day the tenant is covered -- what a lease prints as its end date. */
    public java.util.Optional<LocalDate> lastCoveredDay() {
        return termEnd().map(end -> end.minusDays(1));
    }
}