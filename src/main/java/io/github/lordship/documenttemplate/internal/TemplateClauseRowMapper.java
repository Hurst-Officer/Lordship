package io.github.lordship.documenttemplate.internal;

import org.springframework.jdbc.core.RowMapper;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * condition_values is a TEXT[], which the default property mapper hands back as
 * a java.sql.Array rather than a List. That column is the only reason this file
 * exists.
 */
public class TemplateClauseRowMapper implements RowMapper<TemplateClauseRow> {

    @Override
    public TemplateClauseRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TemplateClauseRow(
                rs.getObject("uuid", UUID.class),
                rs.getObject("section", UUID.class),
                rs.getBigDecimal("ordinal"),
                rs.getString("clause_key"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("condition_field"),
                textArray(rs, "condition_values"),
                rs.getBoolean("required"),
                rs.getString("statute_ref"),
                rs.getString("note"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("created_by", UUID.class),
                rs.getObject("deleted_at", OffsetDateTime.class),
                rs.getObject("parent", UUID.class),
                rs.getObject("variant_of", UUID.class),
                rs.getBoolean("numbered"),
                rs.getObject("requires_next", UUID.class),
                rs.getObject("style", UUID.class)
        );
    }

    // A null array and an empty one both mean "no condition", so both come back
    // as an empty list rather than making every caller null-check.
    static List<String> textArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        try {
            String[] values = (String[]) array.getArray();
            // Not Arrays.asList: that view stays writable through the backing
            // array, and TemplateClauseRow is a record that does not copy it.
            return values == null ? List.of() : Arrays.stream(values).toList();
        } finally {
            array.free();
        }
    }
}
