package io.github.lordship.instruments;

public enum InstrumentStatus {
    DRAFT, GENERATED, SENT, SERVED, APPROVED, ABANDONED;
    // DRAFT     - clauses frozen, nothing printed yet. The only editable state.
    // GENERATED - a file exists, with a timestamp and a record of what produced it.
    // SENT      - made available to the property manager to print and deliver or get signed
    // SERVED    - delivered/signed according to property manager, with a method and a date behind it.
    // APPROVED  - signed copy is back. Agent at office approves. This is what puts the charge terms in force.
    // ABANDONED - never went out, or was replaced before it did.

    /** Only a draft may have its clauses rewritten; after that a change is a new instrument. */
    public boolean isEditable() {
        return this == DRAFT;
    }

    public boolean isReleased() {
        return this == SENT || this == SERVED || this == APPROVED;
    }

    /** The tenant has it. The line that matters for notice periods and service. */
    public boolean isWithTenant() {
        return this == SERVED || this == APPROVED;
    }

    /** Whether a document has left the office and could be relied on by a tenant. */
//    public boolean isOut() {
//        return this == SENT || this == SERVED || this == APPROVED;
//    }
}