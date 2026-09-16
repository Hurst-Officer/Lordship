package io.github.lordship.documenttemplate.internal;

import io.github.lordship.documenttemplate.DocumentTemplate;
import io.github.lordship.documenttemplate.PropertyDocumentAssignment;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record PropertyDocumentAssignmentRow(
        UUID uuid,
        UUID property,
        UUID documentTemplate,
        AgreementType agreementType,
        InstrumentType instrumentType,
        String note,
        OffsetDateTime createdAt,
        UUID createdBy,
        OffsetDateTime deletedAt
) {

    /** The template arrives as a summary -- an assignment list has no use for sixty clause bodies. */
    public PropertyDocumentAssignment toPropertyDocumentAssignment(DocumentTemplate document) {
        return toPropertyDocumentAssignment(document, List.of());
    }

    public PropertyDocumentAssignment toPropertyDocumentAssignment(
            DocumentTemplate document,
            List<io.github.lordship.documenttemplate.PropertyDocumentCustomization> customizations) {
        return new PropertyDocumentAssignment(
                this.uuid,
                this.property,
                this.agreementType,
                this.instrumentType,
                this.note,
                this.createdAt,
                this.deletedAt,
                document,
                customizations
        );
    }
}
