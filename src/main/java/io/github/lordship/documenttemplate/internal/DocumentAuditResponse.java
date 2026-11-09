package io.github.lordship.documenttemplate.internal;

import io.github.lordship.documenttemplate.DocumentAudit;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;

import org.springframework.modulith.NamedInterface;

import java.util.List;
import java.util.UUID;

// Shared with the screens module, so the park page's one call sends its check in this same shape
@NamedInterface("responses")
public record DocumentAuditResponse(
        UUID propertyId,
        List<FindingResponse> documents
) {

    @NamedInterface("responses")
    public record FindingResponse(
            UUID assignmentId,
            UUID documentTemplateId,
            String documentName,
            int documentVersion,
            AgreementType agreementType,
            InstrumentType instrumentType,
            int configurationsChecked,
            List<DocumentAudit.Gap> gaps
    ) {
        public static FindingResponse from(DocumentAudit.DocumentFinding finding) {
            return new FindingResponse(
                    finding.assignmentUuid(),
                    finding.documentTemplateId(),
                    finding.documentName(),
                    finding.documentVersion(),
                    finding.agreementType(),
                    finding.instrumentType(),
                    finding.configurationsChecked(),
                    finding.gaps()
            );
        }
    }

    public static DocumentAuditResponse from(DocumentAudit audit) {
        return new DocumentAuditResponse(
                audit.propertyId(),
                audit.documents().stream().map(FindingResponse::from).toList()
        );
    }

}
