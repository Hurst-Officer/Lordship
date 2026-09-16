package io.github.lordship.instruments;

import io.github.lordship.documenttemplate.DocumentSection;
import io.github.lordship.documenttemplate.PropertyDocumentCustomization;
import io.github.lordship.documenttemplate.TemplateClause;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Chooses which clauses this deal gets, and turns them into the rows that will
 * become {@code instrument_section} and {@code instrument_clause}.
 *
 * <p>The freeze. After this, the wording belongs to the document rather than to
 * the template: edit the template next year and a lease already generated is
 * untouched, which is the point of copying rather than referencing.
 *
 * <p>Pure, like the renderer and the resolver -- three lists in, rows out, no
 * database. Persisting them is a service's job.
 *
 * <p>Three inputs because a lease is written by three people who never meet.
 * An admin writes the document; a park adjusts it once, for every tenant it
 * will ever have; an office worker adds a sentence to one agreement and no
 * other. All three are inputs rather than edits, so re-freezing a draft after
 * the deal changes is always safe: nothing typed is stored in the output this
 * method throws away and rebuilds.
 *
 * <p>Each clause keeps both forms. {@code body} is the words on the page,
 * amounts and all; {@code bodyTemplate} is the same words with the tokens still
 * in them. Keeping the second makes verification mechanical later: re-resolve
 * the term's current values into bodyTemplate and compare to body, and a
 * mismatch names the clause where the paper and the deal diverged.
 */
public final class DocumentFreeze {

    private DocumentFreeze() {}

    /**
     * One clause as it will be stored. uuid and instrument are the repository's
     * to assign.
     *
     * <p>{@code ordinal} is NOT the template's ordinal. After the freeze it is
     * 1, 2, 3 within the section -- the number this clause prints under. The
     * template's ordinals are sparse and a dropped clause would leave a gap, so
     * a lease numbered 1, 3, 7 is what renumbering here prevents. Provenance is
     * not lost: {@code sourceClause} still names the row it came from.
     *
     * <p>{@code origin} says which table that row is in. The three sources have
     * three different tables and {@code sourceClause} is one column, so without
     * this a uuid cannot be looked up at all -- a template clause joined against
     * {@code template_clause} matches, a park's clause matches nothing, and
     * "nothing matched" reads as "no drift" when the truth is "not comparable".
     */
    public record FrozenClause(
            BigDecimal ordinal,
            String clauseKey,
            String title,
            String body,
            String bodyTemplate,
            String statuteRef,
            UUID sourceClause,
            ClauseOrigin origin
    ) {}

    /**
     * One sub-document as it will be stored, with the clauses that survived
     * selection. {@code ordinal} is renumbered 1..n the same way, and each
     * section's clauses start again at 1 -- a section is a document in its own
     * right, so the Pet Agreement does not continue the lease's numbering.
     */
    public record FrozenSection(
            BigDecimal ordinal,
            String name,
            String sectionKey,
            boolean signatureBlock,
            boolean listedAsAddendum,
            String statuteRef,
            List<FrozenClause> clauses
    ) {
        public FrozenSection {
            clauses = List.copyOf(clauses);
        }
    }

    /**
     * What the freeze produced, and everything wrong with it.
     *
     * @param unresolved      tokens no clause could fill. Generation refuses on
     *                        these; a preview shows them standing in the text.
     * @param omittedRequired sections that exist to satisfy a statute and ended
     *                        up with nothing in them. Reported by name and
     *                        statute rather than silently dropped -- a lease
     *                        missing a mandated disclosure is a legal problem,
     *                        not a formatting one.
     */
    public record Frozen(
            List<FrozenSection> sections,
            List<String> unresolved,
            List<String> omittedRequired
    ) {
        public Frozen {
            sections = List.copyOf(sections);
            unresolved = List.copyOf(unresolved);
            omittedRequired = List.copyOf(omittedRequired);
        }

        /** Whether this is fit to put in front of a tenant. */
        public boolean isComplete() {
            return unresolved.isEmpty() && omittedRequired.isEmpty();
        }
    }

    /** The document as the admin wrote it, with no park adjustments and nothing typed on. */
    public static Frozen freeze(List<DocumentSection> sections, TokenValues values) {
        return freeze(sections, List.of(), List.of(), values);
    }

    /**
     * Selects and renders, in print order.
     *
     * <p>A conditional clause prints only when the deal's value for its
     * condition field is one it was written for -- so a tenant on RUBS water
     * never sees the flat-water paragraph, and a clause is dropped outright
     * rather than printed with a blank in it.
     *
     * <p>A section whose clauses all dropped is dropped too: an empty heading
     * reading "SEPTIC ADDENDUM" with nothing under it is worse than no section.
     * A signature block is the exception, having no clauses by nature.
     *
     * <p>Numbering is assigned here, not authored. A clause body carries the
     * words and nothing else -- no "5." typed at the front -- because whether a
     * clause is the fifth depends on which conditional clauses above it printed
     * for this particular deal, which the author cannot know.
     */
    public static Frozen freeze(List<DocumentSection> sections,
                                List<PropertyDocumentCustomization> customizations,
                                List<InstrumentAddition> additions,
                                TokenValues values) {

        Set<UUID> excludedSections = new LinkedHashSet<>();
        Set<UUID> excludedClauses = new LinkedHashSet<>();
        Map<UUID, List<Candidate>> extraBySection = new LinkedHashMap<>();

        for (PropertyDocumentCustomization customization : customizations) {
            if (customization.isSoftDeleted()) {
                continue;
            }
            if (customization.excludedSection() != null) {
                excludedSections.add(customization.excludedSection());
            }
            if (customization.excludedClause() != null) {
                excludedClauses.add(customization.excludedClause());
            }
            if (customization.addsClause()) {
                extraBySection
                        .computeIfAbsent(customization.section(), key -> new ArrayList<>())
                        .add(Candidate.of(customization));
            }
        }

        for (InstrumentAddition addition : additions) {
            if (addition.isSoftDeleted()) {
                continue;
            }
            extraBySection
                    .computeIfAbsent(addition.section(), key -> new ArrayList<>())
                    .add(Candidate.of(addition));
        }

        List<FrozenSection> frozen = new ArrayList<>();
        Set<String> unresolved = new LinkedHashSet<>();
        List<String> omittedRequired = new ArrayList<>();

        for (DocumentSection section : inOrder(sections)) {
            if (section.isSoftDeleted()) {
                continue;
            }

            // A park may not exclude a required section, and the save path
            // refuses it -- but a row that got in anyway must not quietly
            // remove a mandated disclosure. Reported here so generation refuses
            // by name instead.
            if (excludedSections.contains(section.uuid())) {
                if (section.required()) {
                    omittedRequired.add(describe(section));
                }
                continue;
            }

            List<FrozenClause> clauses = new ArrayList<>();
            for (Candidate candidate : candidatesFor(section, excludedClauses, extraBySection)) {
                if (!candidate.appliesTo(values)) {
                    continue;
                }
                BodyRenderer.Rendered rendered = BodyRenderer.render(candidate.body(), values);
                unresolved.addAll(rendered.unresolved());

                clauses.add(new FrozenClause(
                        BigDecimal.valueOf(clauses.size() + 1L),
                        candidate.clauseKey(),
                        candidate.title(),
                        rendered.text(),
                        candidate.body(),
                        candidate.statuteRef(),
                        candidate.source(),
                        candidate.origin()));
            }

            if (clauses.isEmpty() && !section.signatureBlock()) {
                if (section.required()) {
                    omittedRequired.add(describe(section));
                }
                continue;
            }

            frozen.add(new FrozenSection(
                    BigDecimal.valueOf(frozen.size() + 1L),
                    section.name(),
                    section.sectionKey(),
                    section.signatureBlock(),
                    section.listedAsAddendum(),
                    section.statuteRef(),
                    clauses));
        }

        return new Frozen(frozen, List.copyOf(unresolved), omittedRequired);
    }

    /**
     * Everything that could print in this section, in print order: the
     * template's own clauses, minus the ones this park excluded, plus the ones
     * this park and this agreement added.
     *
     * <p>All three share one ordinal space -- that is what lets a park drop its
     * clause between the template's fourth and fifth rather than at the end. The
     * sort is stable, so a tie leaves the template's clause above the added one,
     * which is the safer reading of "same position".
     */
    private static List<Candidate> candidatesFor(DocumentSection section,
                                                 Set<UUID> excludedClauses,
                                                 Map<UUID, List<Candidate>> extraBySection) {
        List<Candidate> candidates = new ArrayList<>();

        for (TemplateClause clause : section.clauses()) {
            if (clause.isSoftDeleted() || excludedClauses.contains(clause.uuid())) {
                continue;
            }
            candidates.add(Candidate.of(clause));
        }
        candidates.addAll(extraBySection.getOrDefault(section.uuid(), List.of()));

        candidates.sort(Comparator.comparing(Candidate::ordinal));
        return candidates;
    }

    /** Named the way an office worker would recognise it, with the statute it answers to. */
    private static String describe(DocumentSection section) {
        return section.name()
                + (section.statuteRef() == null ? "" : " (" + section.statuteRef() + ")");
    }

    /** Print order. Ordinals are sparse, so never assume they are 1..n. */
    private static List<DocumentSection> inOrder(List<DocumentSection> sections) {
        return sections.stream()
                .sorted(Comparator.comparing(DocumentSection::ordinal))
                .toList();
    }

    /**
     * One clause that might print, from whichever of the three sources wrote
     * it. The freeze does the same thing to all three, so flattening them here
     * is what keeps the selection loop from branching on provenance.
     */
    private record Candidate(
            BigDecimal ordinal,
            String clauseKey,
            String title,
            String body,
            String conditionField,
            List<String> conditionValues,
            String statuteRef,
            UUID source,
            ClauseOrigin origin
    ) {

        static Candidate of(TemplateClause clause) {
            return new Candidate(clause.ordinal(), clause.clauseKey(), clause.title(),
                    clause.body(), clause.conditionField(), clause.conditionValues(),
                    clause.statuteRef(), clause.uuid(), ClauseOrigin.TEMPLATE);
        }

        static Candidate of(PropertyDocumentCustomization customization) {
            return new Candidate(customization.ordinal(), null, customization.title(),
                    customization.body(), customization.conditionField(),
                    customization.conditionValues(), null,
                    customization.uuid(), ClauseOrigin.PROPERTY);
        }

        /**
         * No condition and no statute: a clause typed onto one agreement is
         * true of that agreement or it would not have been typed, and nothing
         * one office worker writes satisfies a statute.
         */
        static Candidate of(InstrumentAddition addition) {
            return new Candidate(addition.ordinal(), null, addition.title(),
                    addition.body(), null, List.of(), null,
                    addition.uuid(), ClauseOrigin.INSTRUMENT);
        }

        /**
         * The condition field is a token name and {@link TokenValues} is keyed
         * by token name, so the deal's answer is a lookup rather than a
         * translation. A null answer means the method was never supplied, and
         * a conditional clause declines rather than guessing.
         */
        boolean appliesTo(TokenValues values) {
            if (conditionField == null) {
                return true;
            }
            String actual = values.scalar(conditionField);
            return actual != null && conditionValues.contains(actual);
        }
    }
}
