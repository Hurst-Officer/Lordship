package io.github.lordship.properties.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.stream.Collectors;

@Repository
public class PropertyRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final PropertyRowMapper rowMapper;

    public PropertyRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.rowMapper = new PropertyRowMapper(objectMapper);
    }

    private static final Set<String> PATCHABLE_COLUMNS = Set.of(
            "property_code", "property_name", "property_street", "property_city",
            "property_state", "property_zip","purchase_date", "property_zoning", "property_parcel",
            "payable_to", "remittance_address", "year_built", "custom_fields", "property_manager"
    );

    public PropertyRow save(String propertyName, String propertyStreet, String propertyCity,
                            String propertyState, String propertyZip, String propertyCode) {
        return jdbc.sql("""
                        INSERT INTO property (
                            property_code, property_name, property_street,
                            property_city, property_state, property_zip
                        ) VALUES (
                            :propertyCode, :propertyName, :propertyStreet,
                            :propertyCity, :propertyState, :propertyZip
                        ) RETURNING *
                        """)
                .param("propertyCode", propertyCode)
                .param("propertyName", propertyName)
                .param("propertyStreet", propertyStreet)
                .param("propertyCity", propertyCity)
                .param("propertyState", propertyState)
                .param("propertyZip", propertyZip)
                .query(rowMapper)
                .single();
    }

    public Optional<PropertyRow> findByCode(String propertyCode) {
        return jdbc.sql("SELECT * FROM property WHERE property_code = :propertyCode AND deleted_at IS NULL")
                .param("propertyCode", propertyCode)
                .query(rowMapper)
                .optional();
    }

    public Optional<PropertyRow> findById(UUID propertyId) {
        return jdbc.sql("SELECT * FROM property WHERE uuid = :propertyId AND deleted_at IS NULL")
                .param("propertyId", propertyId)
                .query(rowMapper)
                .optional();
    }

    public List<PropertyRow> findAll() {
        return jdbc.sql("SELECT * FROM property WHERE deleted_at IS NULL")
                .query(rowMapper)
                .list();
    }

    public Optional<PropertyRow> patch(UUID uuid, Map<String, Object> changes) {
        if (changes.isEmpty()) return findById(uuid);

        for (String col : changes.keySet()) {
            if (!PATCHABLE_COLUMNS.contains(col)) {
                throw new IllegalArgumentException("Invalid column: " + col);
            }
        }

        // custom_fields is jsonb, so it is sent as JSON text and cast
        String setClauses = changes.keySet().stream()
                .map(col -> col.equals("custom_fields")
                        ? col + " = CAST(:" + col + " AS jsonb)"
                        : col + " = :" + col)
                .collect(Collectors.joining(", "));

        String sql = "UPDATE property SET " + setClauses +
                " WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *";

        Map<String, Object> params = new HashMap<>(changes);
        params.put("uuid", uuid);
        if (params.containsKey("custom_fields")) {
            params.put("custom_fields", objectMapper.writeValueAsString(params.get("custom_fields")));
        }

        return jdbc.sql(sql)
                .params(params)
                .query(rowMapper)
                .optional();
    }

    public Set<String> findUsedPropertyCodes() {
        return jdbc.sql("""
            SELECT property_code
            FROM property
            WHERE property_code IS NOT NULL
              AND deleted_at IS NULL
            """)
                .query(String.class)
                .set();
    }

    public boolean softDelete(UUID uuid) {
        int rows = jdbc.sql("UPDATE property SET deleted_at = NOW() WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .update();
        return rows > 0;
    }
}