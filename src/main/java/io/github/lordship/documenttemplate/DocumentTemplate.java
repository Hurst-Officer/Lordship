package io.github.lordship.documenttemplate;

import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.DocumentToken;
import io.github.lordship.shared.InstrumentType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One document in the global pool -- "WA Land Lease 2026" -- as a packet of
 * sub-documents rather than a single sheet.
 *
 * <p>Global by definition: there are no property-scoped templates. A park picks
 * a document up through a document assignment, and an edit here reaches every
 * park that has. Documents already generated are untouched, because their
 * wording was snapshotted onto the instrument at generate.
 */
public record DocumentTemplate(
        UUID uuid,
        String name,
        AgreementType agreementType,
        InstrumentType instrumentType,
        int version,
        String note,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt,
        List<DocumentSection> sections,
        List<DocumentStyle> styles
) {
    public DocumentTemplate {
        sections = List.copyOf(sections);
        styles = (styles == null) ? List.of() : List.copyOf(styles);
    }

    /** One clause anywhere in the document, live or not. */
    public Optional<TemplateClause> clause(UUID uuid) {
        return sections.stream()
                .flatMap(section -> section.clauses().stream())
                .filter(clause -> clause.uuid().equals(uuid))
                .findFirst();
    }

    /** The section a clause sits in. */
    public Optional<DocumentSection> sectionOf(UUID clauseUuid) {
        return sections.stream()
                .filter(section -> section.clauses().stream().anyMatch(c -> c.uuid().equals(clauseUuid)))
                .findFirst();
    }

    public Optional<DocumentStyle> style(UUID uuid) {
        return styles.stream().filter(style -> style.uuid().equals(uuid)).findFirst();
    }

    public boolean isSoftDeleted() {
        return deletedAt != null;
    }

    /** Print order. Ordinals are sparse, so never assume they are 1..n. */
    public List<DocumentSection> sectionsInOrder() {
        return sections.stream()
                .sorted(Comparator.comparing(DocumentSection::ordinal))
                .toList();
    }

    /**
     * By key rather than uuid, because a key is stable across versions and a
     * uuid is not -- this is how "the septic addendum" stays findable after the
     * document has been re-authored.
     */
    public Optional<DocumentSection> section(String sectionKey) {
        return sections.stream()
                .filter(s -> sectionKey != null && sectionKey.equals(s.sectionKey()))
                .findFirst();
    }

    /** Every clause in the document, in print order, sections flattened. */
    public List<TemplateClause> clausesInOrder() {
        return sectionsInOrder().stream()
                .flatMap(section -> section.clausesInOrder().stream())
                .toList();
    }

    // ---- the worklist --------------------------------------------------------

    /** One method a clause can branch on, and how much of it this document handles. */
    public record MethodCoverage(
            String conditionField,
            List<String> allowedValues,
            List<String> covered,
            List<String> uncovered,
            int clauseCount) {

        public boolean untouched() {
            return clauseCount == 0;
        }
    }

    /**
     * Every method a clause could branch on, across the whole document, whether
     * or not anything branches on it yet. This is the authoring worklist --
     * "what have I not written" -- and it answers from the template alone, so
     * it works on a document that has never been assigned to a property.
     *
     * <p>Distinct from {@code DocumentSection.conditionCoverage}, which reports
     * only methods some clause in that section already uses. That one asks "did
     * I finish what I started" and sits next to the clauses; this one asks "what
     * have I not started" and belongs to the document. Nine rows here rather
     * than nine per section, which is the difference between a checklist and
     * noise.
     */
    public List<MethodCoverage> conditionWorklist() {
        List<TemplateClause> clauses = clausesInOrder();

        return java.util.Arrays.stream(DocumentToken.values())
                .filter(DocumentToken::canCondition)
                .sorted(Comparator.comparing(DocumentToken::token))
                .map(method -> {
                    List<TemplateClause> branching = clauses.stream()
                            .filter(clause -> method.token().equals(clause.conditionField()))
                            .toList();

                    Set<String> covered = branching.stream()
                            .flatMap(clause -> clause.conditionValues().stream())
                            .collect(Collectors.toCollection(LinkedHashSet::new));

                    return new MethodCoverage(
                            method.token(),
                            method.allowedValues().stream().sorted().toList(),
                            covered.stream().sorted().toList(),
                            method.allowedValues().stream()
                                    .filter(value -> !covered.contains(value))
                                    .sorted().toList(),
                            branching.size());
                })
                .toList();
    }

    // ---- preview -------------------------------------------------------------

    /** A clause as it would print. Tokens are left in: preview is about structure, not figures. */
    public record PreviewClause(
            UUID uuid,
            BigDecimal ordinal,
            String clauseKey,
            String title,
            String body) { }

    public record PreviewSection(
            UUID uuid,
            BigDecimal ordinal,
            String name,
            String sectionKey,
            boolean signatureBlock,
            boolean listedAsAddendum,
            List<PreviewClause> clauses) { }

    /** A clause that did not print, and the condition that kept it out. */
    public record SkippedClause(
            UUID uuid,
            String clauseKey,
            String conditionField,
            List<String> conditionValues,
            String reason) { }

    public record Preview(
            Map<String, String> methodValues,
            List<PreviewSection> sections,
            List<SkippedClause> skipped) { }

    /**
     * The document as it would come out for one set of method values -- which
     * clauses print, in order, and which were held back and why.
     *
     * <p>Bodies keep their tokens. Preview answers "is this document complete
     * and does it read right", which is a question about structure and prose;
     * substituting figures needs a real tenancy and belongs to generate.
     *
     * <p>A method absent from the map is treated as unset, so every clause
     * branching on it is skipped and says so. That is deliberate: an author
     * previewing a half-specified deal should see the holes rather than a
     * plausible-looking document.
     *
     * <p>A section whose clauses all skip is dropped entirely -- an empty
     * heading in a lease is worse than no heading.
     */
    public Preview preview(Map<String, String> methodValues) {
        List<SkippedClause> skipped = new ArrayList<>();
        List<PreviewSection> sections = new ArrayList<>();

        for (DocumentSection section : sectionsInOrder()) {
            List<PreviewClause> printed = new ArrayList<>();

            for (TemplateClause clause : section.clausesInOrder()) {
                if (!clause.isConditional()) {
                    printed.add(toPreviewClause(clause));
                    continue;
                }

                String actual = methodValues.get(clause.conditionField());
                if (clause.appliesTo(actual)) {
                    printed.add(toPreviewClause(clause));
                } else {
                    skipped.add(new SkippedClause(
                            clause.uuid(),
                            clause.clauseKey(),
                            clause.conditionField(),
                            clause.conditionValues(),
                            actual == null
                                    ? clause.conditionField() + " was not supplied"
                                    : clause.conditionField() + " is " + actual));
                }
            }

            if (!printed.isEmpty()) {
                sections.add(new PreviewSection(
                        section.uuid(),
                        section.ordinal(),
                        section.name(),
                        section.sectionKey(),
                        section.signatureBlock(),
                        section.listedAsAddendum(),
                        List.copyOf(printed)));
            }
        }

        return new Preview(Map.copyOf(methodValues), List.copyOf(sections), List.copyOf(skipped));
    }

    private static PreviewClause toPreviewClause(TemplateClause clause) {
        return new PreviewClause(
                clause.uuid(), clause.ordinal(), clause.clauseKey(), clause.title(), clause.body());
    }
}
