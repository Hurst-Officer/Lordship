package io.github.lordship.documenttemplate.internal;

import io.github.lordship.documenttemplate.CustomizationAction;
import io.github.lordship.documenttemplate.PropertyDocumentCustomization;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** One row of {@code property_document_customization}. */
public record PropertyDocumentCustomizationRow(
        UUID uuid,
        UUID assignment,
        CustomizationAction action,
        UUID section,
        UUID clause,
        BigDecimal ordinal,
        String title,
        String body,
        String conditionField,
        List<String> conditionValues,
        String note,
        OffsetDateTime createdAt,
        UUID createdBy,
        OffsetDateTime deletedAt
) {

    public PropertyDocumentCustomization toPropertyDocumentCustomization() {
        return new PropertyDocumentCustomization(
                this.uuid,
                this.assignment,
                this.action,
                this.section,
                this.clause,
                this.ordinal,
                this.title,
                this.body,
                this.conditionField,
                this.conditionValues,
                this.note,
                this.createdAt,
                this.deletedAt
        );
    }
}
