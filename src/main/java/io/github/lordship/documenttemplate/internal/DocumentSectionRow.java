package io.github.lordship.documenttemplate.internal;

import io.github.lordship.documenttemplate.DocumentSection;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Mapped by {@link DocumentSectionRowMapper}: the two format columns are TEXT[]. */
public record DocumentSectionRow(
        UUID uuid,
        UUID template,
        BigDecimal ordinal,
        String name,
        String sectionKey,
        boolean signatureBlock,
        boolean listedAsAddendum,
        boolean required,
        String statuteRef,
        String note,
        OffsetDateTime createdAt,
        UUID createdBy,
        OffsetDateTime deletedAt,
        List<String> numberFormats,
        List<String> citeFormats,
        UUID style,
        UUID titleStyle
) {

    public DocumentSection toDocumentSection(List<TemplateClauseRow> clauseRows) {
        return new DocumentSection(
                this.uuid,
                this.ordinal,
                this.name,
                this.sectionKey,
                this.signatureBlock,
                this.listedAsAddendum,
                this.required,
                this.statuteRef,
                this.note,
                this.createdAt,
                this.deletedAt,
                clauseRows.stream().map(TemplateClauseRow::toTemplateClause).toList(),
                this.numberFormats,
                this.citeFormats,
                this.style,
                this.titleStyle
        );
    }

    public DocumentSection toDocumentSection() {
        return toDocumentSection(List.of());
    }
}
