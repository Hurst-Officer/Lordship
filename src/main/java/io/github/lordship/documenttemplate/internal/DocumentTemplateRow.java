package io.github.lordship.documenttemplate.internal;

import io.github.lordship.documenttemplate.DocumentSection;
import io.github.lordship.documenttemplate.DocumentStyle;
import io.github.lordship.documenttemplate.DocumentTemplate;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record DocumentTemplateRow(
        UUID uuid,
        String name,
        AgreementType agreementType,
        InstrumentType instrumentType,
        int version,
        String note,
        OffsetDateTime createdAt,
        UUID createdBy,
        OffsetDateTime deletedAt
) {

    /** Sections arrive already carrying their clauses; the service assembles them. */
    public DocumentTemplate toDocumentTemplate(List<DocumentSection> sections) {
        return toDocumentTemplate(sections, List.of());
    }

    public DocumentTemplate toDocumentTemplate(List<DocumentSection> sections, List<DocumentStyle> styles) {
        return new DocumentTemplate(
                this.uuid,
                this.name,
                this.agreementType,
                this.instrumentType,
                this.version,
                this.note,
                this.createdAt,
                this.deletedAt,
                sections,
                styles
        );
    }

    public DocumentTemplate toDocumentTemplate() {
        return toDocumentTemplate(List.of());
    }
}