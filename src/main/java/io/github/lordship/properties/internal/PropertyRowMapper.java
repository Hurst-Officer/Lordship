package io.github.lordship.properties.internal;

import io.github.lordship.properties.BusinessNumber;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PropertyRowMapper implements RowMapper<PropertyRow> {

    private static final TypeReference<List<BusinessNumber>> BUSINESS_NUMBERS = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> FIELD_TEXT = new TypeReference<>() {};

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
                rs.getString("title_link"),
                rs.getString("property_street"),
                rs.getString("property_city"),
                rs.getString("property_state"),
                rs.getString("property_zip"),
                rs.getString("property_county"),
                (Double) rs.getObject("latitude"),
                (Double) rs.getObject("longitude"),
                rs.getObject("purchase_date", LocalDate.class),
                rs.getString("property_zoning"),
                readTextArray(rs.getArray("property_parcels")),
                rs.getBigDecimal("acres"),
                rs.getString("payable_to"),
                readJson(rs.getString("business_numbers"), BUSINESS_NUMBERS, List.of(), "business_numbers"),
                rs.getString("remittance_address"),
                (Integer) rs.getObject("year_built"),
                readCustomFields(rs.getString("custom_fields")),
                readJson(rs.getString("field_links"), FIELD_TEXT, Map.of(), "field_links"),
                readJson(rs.getString("field_notes"), FIELD_TEXT, Map.of(), "field_notes"),
                (UUID) rs.getObject("property_manager"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("deleted_at", OffsetDateTime.class)
        );
    }

    private static List<String> readTextArray(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return List.of((String[]) array.getArray());
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

    private <T> T readJson(String json, TypeReference<T> type, T whenNull, String column) {
        if (json == null) {
            return whenNull;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("Malformed " + column + " JSON", e);
        }
    }
}
