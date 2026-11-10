package io.github.lordship.presence.internal;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The notices Postgres sends a connection that LISTENs (PGConnection.getNotifications).
 * Reached by name, so the Postgres driver can stay a runtime-only dependency of Lordship.
 */
final class PgNotifications {

    private final Object connection;
    private final Method getNotifications;
    private final Method getParameter;

    PgNotifications(Connection jdbc) throws SQLException {
        try {
            ClassLoader loader = PgNotifications.class.getClassLoader();
            Class<?> api = Class.forName("org.postgresql.PGConnection", true, loader);
            this.connection = jdbc.unwrap(api);
            this.getNotifications = api.getMethod("getNotifications", int.class);
            this.getParameter = Class.forName("org.postgresql.PGNotification", true, loader).getMethod("getParameter");
        } catch (ReflectiveOperationException e) {
            throw new SQLException("The Postgres driver has no notifications (is the database Postgres?)", e);
        }
    }

    // The payloads that arrived, waiting up to timeoutMs for the first. Empty when none came.
    // Throws when the connection broke.
    List<String> poll(int timeoutMs) throws SQLException {
        try {
            Object[] arrived = (Object[]) getNotifications.invoke(connection, timeoutMs);
            if (arrived == null || arrived.length == 0) return List.of();
            List<String> payloads = new ArrayList<>(arrived.length);
            for (Object notification : arrived) {
                payloads.add((String) getParameter.invoke(notification));
            }
            return payloads;
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof SQLException sql) throw sql;
            throw new SQLException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new SQLException(e);
        }
    }
}
