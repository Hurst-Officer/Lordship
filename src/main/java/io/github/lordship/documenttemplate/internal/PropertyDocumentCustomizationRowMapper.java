package io.github.lordship.documenttemplate.internal;

import io.github.lordship.documenttemplate.CustomizationAction;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Two columns the default property mapper cannot place: {@code action} is TEXT
 * with a CHECK rather than a Java enum, and {@code condition_values} is a
 * TEXT[] that comes back as a java.sql.Array. Same reasons
 * {@link TemplateClauseRowMapper} exists, and the array handling is deliberately
 * identical -- two clause tables that disagree about what an empty condition
 * means is a clause that prints for the wrong tenant.
 */
@Component
public class PropertyDocumentCustomizationRowMapper implements RowMapper<PropertyDocumentCustomizationRow> {

    @Override
    public PropertyDocumentCustomizationRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new PropertyDocumentCustomizationRow(
                rs.getObject("uuid", UUID.class),
                rs.getObject("assignment", UUID.class),
                CustomizationAction.valueOf(rs.getString("action")),
                rs.getObject("section", UUID.class),
                rs.getObject("clause", UUID.class),
                rs.getBigDecimal("ordinal"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("condition_field"),
                textArray(rs, "condition_values"),
                rs.getString("note"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("created_by", UUID.class),
                rs.getObject("deleted_at", OffsetDateTime.class),
                rs.getObject("parent", UUID.class)
        );
    }

    // A null array and an empty one both mean "no condition", so both come back
    // as an empty list rather than making every caller null-check.
    private static List<String> textArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        try {
            String[] values = (String[]) array.getArray();
            // Not Arrays.asList: that view stays writable through the backing
            // array, and the row is a record that does not copy it.
            return values == null ? List.of() : Arrays.stream(values).toList();
        } finally {
            array.free();
        }
    }
}
