package io.github.lordship.access.internal;

import io.github.lordship.shared.RuleConflict;
import io.github.lordship.shared.SystemPrincipal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * Keeps the keys to the role editor with at least one person.
 *
 * <p>The keys are the four permissions that let someone see and change what roles can do, and who
 * holds them. Admins edit live roles as they please, so without this one click could take the last
 * keys away (a permission off the Admin role, the Admin role deleted, the last admin taken off it)
 * and nobody could put them back without the database.
 *
 * <p>A change that takes the keys from the last live agent who held them is refused with 409
 * access.last_key, and rolled back. A database where nobody held them before the change (a test
 * fixture, a fresh install before root signs in) is left alone: the guard only stops the keys being lost.
 */
@Component
public class AccessKeys {

    // see and change what roles can do, and who holds each role
    public static final List<String> KEYS = List.of(
            "agent_roles:view", "agent_roles:edit", "role_permissions:view", "role_permissions:edit");

    private final JdbcClient jdbc;

    public AccessKeys(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Runs a change that may take permissions away, inside the caller's transaction.
     * Two such changes take turns (a transaction-wide advisory lock), so two admins removing
     * each other's keys at the same moment cannot both get through.
     */
    public <T> T keep(Supplier<T> change) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext('lordship.access.keys'))) locked")
                .query(Integer.class)
                .single();
        boolean heldBefore = anyoneHolds();
        T result = change.get();
        if (heldBefore && !anyoneHolds()) {
            throw RuleConflict.of("access.last_key");
        }
        return result;
    }

    /**
     * Makes, renames and restores of roles take turns until the end of the transaction, so the
     * case-insensitive "name taken" check cannot be raced (the database's unique index on role_name
     * cares about case, the check does not).
     */
    public void lockRoleNames() {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext('lordship.access.role_names'))) locked")
                .query(Integer.class)
                .single();
    }

    // True when some live agent (not the server) holds every key, through live roles and grants, without a denial
    public boolean anyoneHolds() {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM agent a
                    WHERE a.deleted_at IS NULL
                    AND a.uuid <> :system
                    AND (
                        SELECT count(DISTINCT p.permission_name)
                        FROM granted_role gr
                        JOIN agent_role r ON r.uuid = gr.role_id AND r.deleted_at IS NULL
                        JOIN role_permission rp ON rp.role_id = r.uuid AND rp.deleted_at IS NULL
                        JOIN permission p ON p.uuid = rp.permission_id
                        WHERE gr.agent_id = a.uuid
                        AND gr.deleted_at IS NULL
                        AND p.permission_name IN (:keys)
                        AND NOT EXISTS (
                            SELECT 1 FROM denied_permission d
                            WHERE d.agent_id = a.uuid
                            AND d.permission_id = p.uuid
                            AND d.deleted_at IS NULL
                        )
                    ) = :count
                )
                """)
                .param("system", SystemPrincipal.AGENT_UUID)
                .param("keys", KEYS)
                .param("count", KEYS.size())
                .query(Boolean.class)
                .single();
    }
}
