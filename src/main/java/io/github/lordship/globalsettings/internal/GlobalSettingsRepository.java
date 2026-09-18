package io.github.lordship.globalsettings.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The singleton. There is no save and no delete: V1 inserts the row and the
 * CHECK on {@code id} stops a second one ever existing, so the only verb here
 * is patch.
 */
@Repository
public class GlobalSettingsRepository {

    private static final Set<String> PATCHABLE_COLUMNS = Set.of("compliance_email");

    private final JdbcClient jdbc;

    public GlobalSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Optional rather than a bare row: the record is seeded by a migration, and
     * a caller that finds it missing is looking at a database that did not
     * finish setting itself up. Better to say so than to hand back a row of
     * nulls that prints on a lease.
     */
    public Optional<GlobalSettingsRow> find() {
        return jdbc.sql("SELECT * FROM global_settings WHERE id = 1")
                .query(GlobalSettingsRow.class)
                .optional();
    }

    public Optional<GlobalSettingsRow> patch(Map<String, Object> changes) {
        if (changes.isEmpty()) return find();

        for (String col : changes.keySet()) {
            if (!PATCHABLE_COLUMNS.contains(col)) {
                throw new IllegalArgumentException("Invalid column: " + col);
            }
        }

        StringBuilder sql = new StringBuilder("UPDATE global_settings SET ");
        changes.forEach((col, val) -> sql.append(col).append(" = :").append(col).append(", "));
        sql.append("updated_at = now()");
        sql.append(" WHERE id = 1 RETURNING *");

        Map<String, Object> params = new HashMap<>(changes);

        return jdbc.sql(sql.toString())
                .params(params)
                .query(GlobalSettingsRow.class)
                .optional();
    }
}
