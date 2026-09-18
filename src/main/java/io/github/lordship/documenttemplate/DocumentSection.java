package io.github.lordship.documenttemplate;

import io.github.lordship.shared.DocumentToken;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A sub-document within a packet -- Rules and Regulations, the Pet Agreement,
 * the Septic Addendum. Their real WA lease is seven of these in one mail merge,
 * each with its own signature block and its own line in the agreement's
 * attached-addenda checklist.
 *
 * <p>This is also the unit a park drops: a community on city sewer excludes the
 * whole septic section rather than picking off its clauses one at a time.
 */
public record DocumentSection(
        UUID uuid,
        BigDecimal ordinal,
        String name,
        String sectionKey,
        boolean signatureBlock,
        boolean listedAsAddendum,
        boolean required,
        String statuteRef,
        String note,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt,
        List<TemplateClause> clauses,
        List<String> numberFormats,
        List<String> citeFormats,
        UUID style,
        UUID titleStyle
) {
    // sql: the column defaults in document_section, for a section built in code
    public static final List<String> DEFAULT_NUMBER_FORMATS = List.of("{1}.", "{2:A}.", "({3:i})");
    public static final List<String> DEFAULT_CITE_FORMATS = List.of("{1}", "{1}{2:A}", "{1}{2:A}({3:i})");

    public DocumentSection {
        clauses = List.copyOf(clauses);
        numberFormats = (numberFormats == null || numberFormats.isEmpty()) ? DEFAULT_NUMBER_FORMATS : List.copyOf(numberFormats);
        citeFormats = (citeFormats == null || citeFormats.isEmpty()) ? DEFAULT_CITE_FORMATS : List.copyOf(citeFormats);
    }

    public boolean isSoftDeleted() {
        return deletedAt != null;
    }

    /** Print order. Ordinals are sparse, so never assume they are 1..n. */
    public List<TemplateClause> clausesInOrder() {
        return clauses.stream()
                .sorted(Comparator.comparing(TemplateClause::ordinal))
                .toList();
    }

    /**
     * A required section exists to satisfy the statute in {@code statuteRef},
     * so no park may exclude it and it cannot be deleted globally while
     * documents still reference it.
     */
    public boolean canBeExcludedByAProperty() {
        return !required;
    }

    /** One method this section branches on, and which of its values have a clause. */
    public record Coverage(String conditionField, List<String> covered, List<String> uncovered) { }

    /**
     * The other half of {@code TemplateClause.unguardedTokens}. That one catches
     * a clause that prints for people it was not written for; this catches a
     * value of a method that no clause covers, so those tenants get silence
     * where they should get a paragraph.
     *
     * <p>Worked example. A section has one trash clause, conditioned on FLAT.
     * It is individually correct, so nothing else flags it -- but a tenant on
     * RUBS gets a lease that says nothing about trash while being billed for it
     * every month. This reports {@code trash_method: covered [FLAT],
     * uncovered [NONE, RUBS]}.
     *
     * <p>A prompt, not an error. NONE uncovered is usually right: a park that
     * charges nothing for trash should have no trash paragraph. Only the author
     * can tell which silences are deliberate.
     *
     * <p>Only methods some clause here already branches on are reported --
     * listing every method on every section would be noise nobody reads.
     */
    public List<Coverage> conditionCoverage() {
        Map<String, Set<String>> coveredByField = new LinkedHashMap<>();
        for (TemplateClause clause : clausesInOrder()) {
            if (clause.conditionField() == null) {
                continue;
            }
            coveredByField
                    .computeIfAbsent(clause.conditionField(), field -> new LinkedHashSet<>())
                    .addAll(clause.conditionValues());
        }

        List<Coverage> coverage = new ArrayList<>();
        coveredByField.forEach((field, covered) -> {
            Set<String> allowed = DocumentToken.of(field)
                    .map(DocumentToken::allowedValues)
                    .orElse(Set.of());

            coverage.add(new Coverage(
                    field,
                    covered.stream().sorted().toList(),
                    allowed.stream().filter(value -> !covered.contains(value)).sorted().toList()));
        });
        return List.copyOf(coverage);
    }
}
