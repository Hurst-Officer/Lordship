package io.github.lordship.securitydeposits;

/**
 * Which kind of paper put this deposit here. MIGRATION is not a peer of the
 * other two -- they say why the deposit exists, it says how the row got into
 * Lordship -- so it is stamped by the migration endpoint and never offered to
 * an agent as a choice.
 */
public enum SecurityDepositSource {
    LEASE, ASSUMPTION, MIGRATION;

    public boolean isAgentSelectable() {
        return this != MIGRATION;
    }
}