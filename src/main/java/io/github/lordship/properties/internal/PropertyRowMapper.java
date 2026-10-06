package io.github.lordship.properties.internal;

import org.springframework.jdbc.core.RowMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public class PropertyRowMapper implements RowMapper<PropertyRow> {

    private final ObjectMapper objectMapper;

    public PropertyRowMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public PropertyRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new PropertyRow(
                (UUID) rs.getObject("uuid"),
                rs.getString("property_code"),
                rs.getString("property_name"),
                rs.getString("property_street"),
                rs.getString("property_city"),
                rs.getString("property_state"),
                rs.getString("property_zip"),
                rs.getObject("purchase_date", LocalDate.class),
                rs.getString("property_zoning"),
                rs.getString("property_parcel"),
                rs.getString("payable_to"),
                rs.getString("remittance_address"),
                (Integer) rs.getObject("year_built"),
                readCustomFields(rs.getString("custom_fields")),
                (UUID) rs.getObject("property_manager"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("deleted_at", OffsetDateTime.class)
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readCustomFields(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Malformed custom_fields JSON", e);
        }
    }
}
