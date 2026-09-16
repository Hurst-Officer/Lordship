package io.github.lordship.instruments.internal;

import io.github.lordship.instruments.InstrumentStatus;
import io.github.lordship.instruments.OnExpiry;
import io.github.lordship.instruments.ServiceMethod;
import io.github.lordship.shared.InstrumentType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Four enum columns, none of which the default property mapper can turn into a
 * Java enum: {@code type} is a Postgres enum and the other three are TEXT with
 * a CHECK. That is the whole reason this file exists.
 */
@Component
public class InstrumentRowMapper implements RowMapper<InstrumentRow> {

    @Override
    public InstrumentRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new InstrumentRow(
                rs.getObject("uuid", UUID.class),
                rs.getObject("tenancy", UUID.class),
                enumOf(InstrumentType.class, rs.getString("type")),
                enumOf(InstrumentStatus.class, rs.getString("status")),

                rs.getString("serial"),
                rs.getObject("amends", UUID.class),

                rs.getObject("term_start", LocalDate.class),
                rs.getObject("term_months", Integer.class),
                enumOf(OnExpiry.class, rs.getString("on_expiry")),

                rs.getObject("template", UUID.class),
                rs.getObject("template_version", Integer.class),
                rs.getObject("document_assignment", UUID.class),

                rs.getObject("generated_at", OffsetDateTime.class),
                rs.getObject("generated_file", UUID.class),

                rs.getObject("sent_at", OffsetDateTime.class),
                rs.getObject("sent_by", UUID.class),

                rs.getObject("served_on", LocalDate.class),
                enumOf(ServiceMethod.class, rs.getString("service_method")),
                rs.getObject("served_by", UUID.class),
                rs.getObject("proof_file", UUID.class),

                rs.getObject("returned_on", LocalDate.class),
                rs.getObject("returned_file", UUID.class),

                rs.getString("note"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("created_by", UUID.class)
        );
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
