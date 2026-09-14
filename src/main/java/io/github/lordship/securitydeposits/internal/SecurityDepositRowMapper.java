package io.github.lordship.securitydeposits.internal;

import io.github.lordship.securitydeposits.SecurityDepositSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Component
public class SecurityDepositRowMapper implements RowMapper<SecurityDepositRow> {

    @Override
    public SecurityDepositRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SecurityDepositRow(
                (UUID) rs.getObject("uuid"),
                (UUID) rs.getObject("tenancy"),
                (UUID) rs.getObject("instrument"),
                enumOf(SecurityDepositSource.class, rs.getString("source")),
                rs.getBigDecimal("amount"),
                rs.getObject("collected_on", LocalDate.class),
                rs.getObject("settled_on", LocalDate.class),
                rs.getString("description"),
                rs.getString("note"),
                (UUID) rs.getObject("created_by"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("deleted_at", OffsetDateTime.class));
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "No " + type.getSimpleName() + " constant for database value '" + value + "'", e);
        }
    }
}