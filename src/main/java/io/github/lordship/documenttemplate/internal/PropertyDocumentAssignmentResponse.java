package io.github.lordship.documenttemplate.internal;

import io.github.lordship.documenttemplate.PropertyDocumentAssignment;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One row of "what this park can generate".
 *
 * <p>The document's name and version are flattened in rather than nested, so a
 * park's document list is readable without a second call per row. The version
 * is worth showing: it is the wording in force for this park today, and it is
 * what a render will freeze onto the instrument.
 *
 * <p>{@code customizations} comes along because a park's document is the
 * template plus its changes, and a list that showed only the name would tell an
 * admin the five parks use the same document when one of them has dropped a
 * section.
 */
public record PropertyDocumentAssignmentResponse(
        UUID uuid,
        UUID propertyId,
        AgreementType agreementType,
        InstrumentType instrumentType,
        UUID documentTemplateId,
        String documentName,
        Integer documentVersion,
        boolean customized,
        List<PropertyDocumentCustomizationResponse> customizations,
        String note,
        OffsetDateTime createdAt
) {

    public static PropertyDocumentAssignmentResponse from(PropertyDocumentAssignment assignment) {
        return new PropertyDocumentAssignmentResponse(
                assignment.uuid(),
                assignment.propertyId(),
                assignment.agreementType(),
                assignment.instrumentType(),
                assignment.document() == null ? null : assignment.document().uuid(),
                assignment.documentName(),
                assignment.documentVersion(),
                assignment.isCustomized(),
                assignment.customizations().stream()
                        .map(PropertyDocumentCustomizationResponse::from)
                        .toList(),
                assignment.note(),
                assignment.createdAt());
    }
}
