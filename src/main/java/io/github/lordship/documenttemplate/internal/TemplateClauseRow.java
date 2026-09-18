package io.github.lordship.documenttemplate.internal;

import io.github.lordship.documenttemplate.TemplateClause;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record TemplateClauseRow(
        UUID uuid,
        UUID section,
        BigDecimal ordinal,
        String clauseKey,
        String title,
        String body,
        String conditionField,
        List<String> conditionValues,
        boolean required,
        String statuteRef,
        String note,
        OffsetDateTime createdAt,
        UUID createdBy,
        OffsetDateTime deletedAt,
        UUID parent,
        UUID variantOf,
        boolean numbered,
        UUID requiresNext,
        UUID style
) {

    public TemplateClause toTemplateClause() {
        return new TemplateClause(
                this.uuid,
                this.ordinal,
                this.clauseKey,
                this.title,
                this.body,
                this.conditionField,
                this.conditionValues,
                this.required,
                this.statuteRef,
                this.note,
                this.createdAt,
                this.deletedAt,
                this.parent,
                this.variantOf,
                this.numbered,
                this.requiresNext,
                this.style
        );
    }
}
