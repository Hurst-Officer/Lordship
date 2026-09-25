package io.github.lordship.tenancyterms;

import io.github.lordship.shared.InstrumentType;

import java.util.Optional;

public enum TenancyTermSource {
    LEASE, INCREASE_NOTICE, ASSUMPTION, ADDENDUM, CORRECTION, MIGRATION;

    /**
     * MIGRATION and CORRECTION terms are entered by an admin with no document.
     * Every other kind is created from a document and can only go into force
     * with one.
     */
    public boolean requiresInstrument() {
        return this != MIGRATION && this != CORRECTION;
    }

    /**
     * Which kind of charge term a document of this type creates.
     * Returns empty for WAIVER and PAY_OR_VACATE, which change no charge terms.
     *
     * <p>The switch has no default on purpose. If a new InstrumentType is added,
     * this will not compile until the new type is listed here.
     */
    public static Optional<TenancyTermSource> producedBy(InstrumentType type) {
        return switch (type) {
            case LEASE -> Optional.of(LEASE);
            case INCREASE_NOTICE -> Optional.of(INCREASE_NOTICE);
            case ASSUMPTION -> Optional.of(ASSUMPTION);
            case ADDENDUM -> Optional.of(ADDENDUM);
            case WAIVER, PAY_OR_VACATE -> Optional.empty();
        };
    }
}
