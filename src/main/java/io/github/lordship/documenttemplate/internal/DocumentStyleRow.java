package io.github.lordship.documenttemplate.internal;

import io.github.lordship.documenttemplate.DocumentStyle;
import io.github.lordship.shared.StyleTarget;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One row of {@code document_style}. All simple types, so JdbcClient maps it without a RowMapper. */
public record DocumentStyleRow(
        UUID uuid,
        UUID template,
        String name,
        String css,
        String target,
        String note,
        OffsetDateTime createdAt,
        UUID createdBy,
        OffsetDateTime deletedAt
) {

    public DocumentStyle toDocumentStyle() {
        return new DocumentStyle(
                this.uuid,
                this.name,
                this.css,
                StyleTarget.of(this.target).orElse(null),
                this.note,
                this.createdAt,
                this.deletedAt
        );
    }
}
