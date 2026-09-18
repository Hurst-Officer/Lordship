package io.github.lordship.documenttemplate.internal;

import io.github.lordship.documenttemplate.DocumentSection;
import io.github.lordship.documenttemplate.DocumentStyle;
import io.github.lordship.documenttemplate.DocumentTemplate;
import io.github.lordship.documenttemplate.TemplateClause;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;
import io.github.lordship.shared.StyleTarget;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A document template, optionally with its sections and their clauses.
 *
 * <p>Two shapes on purpose. {@link #summary} is the list view -- the pool of
 * documents an admin picks from, where sixty clause bodies per row would be
 * noise. {@link #from} is the editor view. Both come through here so a list row
 * and a detail row can never disagree about a field.
 */
public record DocumentTemplateResponse(
        UUID uuid,
        String name,
        AgreementType agreementType,
        InstrumentType instrumentType,
        int version,
        String note,
        OffsetDateTime createdAt,
        List<DocumentTemplate.MethodCoverage> conditionWorklist,
        List<SectionResponse> sections,
        List<StyleResponse> styles
) {

    /** With a target it restyles that part of every page; without one a clause or section picks it. */
    public record StyleResponse(UUID uuid, String name, String css, StyleTarget target, String note) {
        public static StyleResponse from(DocumentStyle style) {
            return new StyleResponse(style.uuid(), style.name(), style.css(), style.target(), style.note());
        }
    }

    /** One sub-document within the packet: signed on its own, listed on its own. */
    public record SectionResponse(
            UUID uuid,
            BigDecimal ordinal,
            String name,
            String sectionKey,
            boolean signatureBlock,
            boolean listedAsAddendum,
            boolean required,
            String statuteRef,
            String note,
            List<String> numberFormats,
            List<String> citeFormats,
            UUID styleId,
            UUID titleStyleId,
            List<DocumentSection.Coverage> conditionCoverage,
            List<ClauseResponse> clauses
    ) {
        public static SectionResponse from(DocumentSection section) {
            return new SectionResponse(
                    section.uuid(),
                    section.ordinal(),
                    section.name(),
                    section.sectionKey(),
                    section.signatureBlock(),
                    section.listedAsAddendum(),
                    section.required(),
                    section.statuteRef(),
                    section.note(),
                    section.numberFormats(),
                    section.citeFormats(),
                    section.style(),
                    section.titleStyle(),
                    section.conditionCoverage(),
                    section.clausesInOrder().stream().map(ClauseResponse::from).toList());
        }
    }

    /**
     * {@code body} carries tokens, never a literal amount. When
     * {@code conditionField} is set the clause prints only where the term's
     * value for that field is one of {@code conditionValues} -- which is how a
     * fee whose wording depends on its method is authored once per method
     * instead of branched inside the sentence.
     *
     * <p>{@code tokenNames} and {@code unguardedTokens} are both derived rather
     * than stored. The first is what the body references. UnguardedTokens is the
     * subset whose value depends on a method this clause does not condition on,
     * so the editor can flag a clause that will print a figure that is not
     * really there -- computed on every read, not only after an edit, which is
     * what lets it surface on clauses that were seeded rather than authored.
     */
    public record ClauseResponse(
            UUID uuid,
            BigDecimal ordinal,
            String clauseKey,
            String title,
            String body,
            List<String> tokenNames,
            List<String> unguardedTokens,
            String conditionField,
            List<String> conditionValues,
            boolean required,
            String statuteRef,
            String note,
            UUID parentId,
            UUID variantOfId,
            boolean numbered,
            UUID requiresNextId,
            UUID styleId,
            List<UUID> refs
    ) {
        public static ClauseResponse from(TemplateClause clause) {
            return new ClauseResponse(
                    clause.uuid(),
                    clause.ordinal(),
                    clause.clauseKey(),
                    clause.title(),
                    clause.body(),
                    List.copyOf(clause.tokenNames()),
                    clause.unguardedTokens(),
                    clause.conditionField(),
                    clause.conditionValues(),
                    clause.required(),
                    clause.statuteRef(),
                    clause.note(),
                    clause.parent(),
                    clause.variantOf(),
                    clause.numbered(),
                    clause.requiresNext(),
                    clause.style(),
                    clause.refs());
        }
    }

    /** The editor view: everything, in print order. */
    public static DocumentTemplateResponse from(DocumentTemplate template) {
        return new DocumentTemplateResponse(
                template.uuid(),
                template.name(),
                template.agreementType(),
                template.instrumentType(),
                template.version(),
                template.note(),
                template.createdAt(),
                template.conditionWorklist(),
                template.sectionsInOrder().stream().map(SectionResponse::from).toList(),
                template.styles().stream().map(StyleResponse::from).toList());
    }

    /** The list view: no children. */
    public static DocumentTemplateResponse summary(DocumentTemplate template) {
        return new DocumentTemplateResponse(
                template.uuid(),
                template.name(),
                template.agreementType(),
                template.instrumentType(),
                template.version(),
                template.note(),
                template.createdAt(),
                List.of(),
                List.of(),
                List.of());
    }
}