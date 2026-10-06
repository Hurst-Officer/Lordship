package io.github.lordship.documenttemplate.internal;

import io.github.lordship.documenttemplate.DocumentAudit;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;

import java.util.List;
import java.util.UUID;

public record DocumentAuditResponse(
        UUID propertyId,
        List<FindingResponse> documents
) {

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
