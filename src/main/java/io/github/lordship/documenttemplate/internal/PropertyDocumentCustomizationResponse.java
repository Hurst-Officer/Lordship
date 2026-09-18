package io.github.lordship.documenttemplate.internal;

import io.github.lordship.documenttemplate.CustomizationAction;
import io.github.lordship.documenttemplate.PropertyDocumentCustomization;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One change a park made to a document it was assigned.
 *
 * <p>Sent flat, with the fields that mean nothing for this action left null, so
 * the screen reads {@code action} and shows the right row rather than three
 * response shapes behind one name.
 */
public record PropertyDocumentCustomizationResponse(
        UUID uuid,
        CustomizationAction action,
        UUID sectionId,
        UUID clauseId,
        BigDecimal ordinal,
        String title,
        String body,
        String conditionField,
        List<String> conditionValues,
        String note,
        OffsetDateTime createdAt,
        UUID parentId
) {

    public static PropertyDocumentCustomizationResponse from(PropertyDocumentCustomization c) {
        return new PropertyDocumentCustomizationResponse(
                c.uuid(),
                c.action(),
                c.section(),
                c.clause(),
                c.ordinal(),
                c.title(),
                c.body(),
                c.conditionField(),
                c.conditionValues(),
                c.note(),
                c.createdAt(),
                c.parent());
    }
}
