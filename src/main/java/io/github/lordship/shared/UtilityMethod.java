package io.github.lordship.shared;

public enum UtilityMethod {
    // NONE: the lease says nothing about it. INCLUDED: the lease says rent covers it.
    NONE, INCLUDED, FLAT, RUBS, SUBMETERED;

    public boolean requiresFlatAmount() {
        return this == FLAT;
    }

    public boolean requiresMeter() {
        return this == SUBMETERED;
    }
}