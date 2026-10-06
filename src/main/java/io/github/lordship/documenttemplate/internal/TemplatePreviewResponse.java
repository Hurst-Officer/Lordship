package io.github.lordship.documenttemplate.internal;

import io.github.lordship.documenttemplate.DocumentTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TemplatePreviewResponse(
        Map<String, String> methodValues,
        List<SectionResponse> sections,
        List<SkippedResponse> skipped
) {

    public record SectionResponse(
            UUID id,
            BigDecimal ordinal,
            String name,
            String sectionKey,
            boolean signatureBlock,
            boolean listedAsAddendum,
            List<ClauseResponse> clauses
    ) {
        static SectionResponse from(DocumentTemplate.PreviewSection section) {
            return new SectionResponse(
                    section.uuid(),
                    section.ordinal(),
                    section.name(),
                    section.sectionKey(),
                    section.signatureBlock(),
                    section.listedAsAddendum(),
                    section.clauses().stream().map(ClauseResponse::from).toList()
            );
        }
    }

    public record ClauseResponse(
            UUID id,
            BigDecimal ordinal,
            String clauseKey,
            String title,
            String body
    ) {
        static ClauseResponse from(DocumentTemplate.PreviewClause clause) {
            return new ClauseResponse(
                    clause.uuid(),
                    clause.ordinal(),
                    clause.clauseKey(),
                    clause.title(),
                    clause.body()
            );
        }
    }

    public record SkippedResponse(
            UUID id,
            String clauseKey,
            String conditionField,
            List<String> conditionValues,
            String reason
    ) {
        static SkippedResponse from(DocumentTemplate.SkippedClause skipped) {
            return new SkippedResponse(
                    skipped.uuid(),
                    skipped.clauseKey(),
                    skipped.conditionField(),
                    skipped.conditionValues(),
                    skipped.reason()
            );
        }
    }

    public static TemplatePreviewResponse from(DocumentTemplate.Preview preview) {
        return new TemplatePreviewResponse(
                preview.methodValues(),
                preview.sections().stream().map(SectionResponse::from).toList(),
                preview.skipped().stream().map(SkippedResponse::from).toList()
        );
    }

}
