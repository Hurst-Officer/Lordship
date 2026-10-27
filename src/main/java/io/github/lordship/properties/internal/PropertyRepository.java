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
            "property_state", "property_zip", "property_county", "latitude", "longitude", "purchase_date",
            "property_zoning", "property_parcels", "acres", "payable_to", "business_numbers",
            "remittance_address", "year_built", "custom_fields", "field_links", "field_notes", "property_manager"
    );

    // These columns arrive as Java lists or maps. They are sent as JSON text,
    // and this is the SQL that turns that text into the column's type.
    // A JSON null becomes an empty list or map, since the columns are NOT NULL.
    private static final Map<String, String> JSON_COLUMN_SQL = Map.of(
            "custom_fields", "CAST(:custom_fields AS jsonb)",
            "field_links", "COALESCE(NULLIF(CAST(:field_links AS jsonb), 'null'::jsonb), '{}'::jsonb)",
            "field_notes", "COALESCE(NULLIF(CAST(:field_notes AS jsonb), 'null'::jsonb), '{}'::jsonb)",
            "business_numbers", "COALESCE(NULLIF(CAST(:business_numbers AS jsonb), 'null'::jsonb), '[]'::jsonb)",
            "property_parcels", "ARRAY(SELECT jsonb_array_elements_text("
                    + "COALESCE(NULLIF(CAST(:property_parcels AS jsonb), 'null'::jsonb), '[]'::jsonb)))"
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
        return jdbc.sql("SELECT * FROM property WHERE deleted_at IS NULL ORDER BY lower(property_name), uuid")
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

        String setClauses = changes.keySet().stream()
                .map(col -> col + " = " + JSON_COLUMN_SQL.getOrDefault(col, ":" + col))
                .collect(Collectors.joining(", "));

        String sql = "UPDATE property SET " + setClauses +
                " WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *";

        Map<String, Object> params = new HashMap<>(changes);
        params.put("uuid", uuid);
        for (String col : JSON_COLUMN_SQL.keySet()) {
            if (params.containsKey(col)) {
                params.put(col, objectMapper.writeValueAsString(params.get(col)));
            }
        }

        return jdbc.sql(sql)
                .params(params)
                .query(rowMapper)
                .optional();
    }

    // Sets the link for one field. A null url removes it.
    public Optional<PropertyRow> setFieldLink(UUID uuid, String field, String url) {
        return setFieldText("field_links", uuid, field, url);
    }

    // Sets the note for one field. A null note removes it.
    public Optional<PropertyRow> setFieldNote(UUID uuid, String field, String note) {
        return setFieldText("field_notes", uuid, field, note);
    }

    // column is always "field_links" or "field_notes", never input from a request
    private Optional<PropertyRow> setFieldText(String column, UUID uuid, String field, String text) {
        String sql = """
                UPDATE property
                SET %1$s = CASE
                        WHEN CAST(:text AS text) IS NULL THEN %1$s - CAST(:field AS text)
                        ELSE %1$s || jsonb_build_object(CAST(:field AS text), CAST(:text AS text))
                    END
                WHERE uuid = :uuid AND deleted_at IS NULL
                RETURNING *
                """.formatted(column);
        return jdbc.sql(sql)
                .param("uuid", uuid)
                .param("field", field)
                .param("text", text)
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