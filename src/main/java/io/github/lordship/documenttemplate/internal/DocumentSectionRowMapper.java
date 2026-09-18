package io.github.lordship.documenttemplate.internal;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

/** number_formats and cite_formats are TEXT[], which the default property mapper cannot place. */
@Component
public class DocumentSectionRowMapper implements RowMapper<DocumentSectionRow> {

    @Override
    public DocumentSectionRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new DocumentSectionRow(
                rs.getObject("uuid", UUID.class),
                rs.getObject("template", UUID.class),
                rs.getBigDecimal("ordinal"),
                rs.getString("name"),
                rs.getString("section_key"),
                rs.getBoolean("signature_block"),
                rs.getBoolean("listed_as_addendum"),
                rs.getBoolean("required"),
                rs.getString("statute_ref"),
                rs.getString("note"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("created_by", UUID.class),
                rs.getObject("deleted_at", OffsetDateTime.class),
                TemplateClauseRowMapper.textArray(rs, "number_formats"),
                TemplateClauseRowMapper.textArray(rs, "cite_formats"),
                rs.getObject("style", UUID.class),
                rs.getObject("title_style", UUID.class)
        );
    }
}
