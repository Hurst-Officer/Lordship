package io.github.lordship.lots.internal;

import io.github.lordship.lots.ShapeData;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

public class LotRowMapper implements RowMapper<LotRow> {

    private final ObjectMapper objectMapper;

    public LotRowMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public LotRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new LotRow(
                (UUID) rs.getObject("uuid"),
                (UUID) rs.getObject("property_id"),
                (Boolean) rs.getObject("is_rentable"),
                rs.getString("not_rentable_reason"),
                rs.getString("lot_number"),
                rs.getString("title_link"),
                rs.getString("lot_address"),
                rs.getString("lot_parcel"),
                rs.getString("description"),
                rs.getString("notes"),
                (Integer) rs.getObject("sort_order"),
                readShapeData(rs.getString("shape_data")),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("deleted_at", OffsetDateTime.class)
        );
    }

    private ShapeData readShapeData(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ShapeData.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Malformed shape_data JSON", e);
        }
    }
}