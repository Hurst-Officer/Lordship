package io.github.lordship.instruments.internal;

import io.github.lordship.instruments.ClauseOrigin;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

/** {@code origin} is TEXT with a CHECK, which the default property mapper leaves as a String. */
@Component
public class InstrumentClauseRowMapper implements RowMapper<InstrumentClauseRow> {

    @Override
    public InstrumentClauseRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        String origin = rs.getString("origin");
        return new InstrumentClauseRow(
                rs.getObject("uuid", UUID.class),
                rs.getObject("instrument", UUID.class),
                rs.getObject("section", UUID.class),
                rs.getBigDecimal("ordinal"),
                rs.getString("clause_key"),
                rs.getString("title"),
                rs.getString("label"),
                rs.getString("body"),
                rs.getString("body_template"),
                rs.getString("statute_ref"),
                origin == null ? null : ClauseOrigin.valueOf(origin),
                rs.getObject("source_clause", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }
}
