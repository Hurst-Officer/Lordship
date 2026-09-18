package io.github.lordship.instruments;

import io.github.lordship.documenttemplate.DocumentSection;
import io.github.lordship.documenttemplate.PropertyDocumentCustomization;
import io.github.lordship.documenttemplate.TemplateClause;
import io.github.lordship.shared.ClauseNumbering;
import io.github.lordship.shared.DocumentToken;
import io.github.lordship.shared.TokenSyntax;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;

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
     * <p>{@code ordinal} is print position, 1..n within the section. {@code number}
     * is what prints beside the clause ("A."), {@code label} is what a reference
     * to it prints ("9A"); both null for an unnumbered clause. {@code depth} is 1
     * for a top-level clause, 2 under it, and so on.
     *
     * <p>{@code unresolved} is what THIS clause asked for and nothing could
     * fill, kept rather than only counted. The renderer reports it per clause
     * and the document-wide list is a rollup of these; merging them and
     * discarding the attribution is how a preview ends up able to say a value
     * is missing but not which paragraph wanted it.
     *
     * <p>{@code origin} says which table that row is in. The three sources have
     * three different tables and {@code sourceClause} is one column, so without
     * this a uuid cannot be looked up at all.
     *
     * <p>{@code keepWithNext} and {@code style} are for whatever draws the page.
     * Neither is stored: the PDF is the record of how it looked.
     */
    public record FrozenClause(
            BigDecimal ordinal,
            String clauseKey,
            String title,
            String number,
            String label,
            int depth,
            String body,
            String bodyTemplate,
            String statuteRef,
            UUID sourceClause,
            ClauseOrigin origin,
            List<String> unresolved,
            boolean keepWithNext,
            UUID style
    ) {
        public FrozenClause {
            unresolved = List.copyOf(unresolved);
        }

        /** Whether this clause is fit to print as it stands. */
        public boolean isComplete() {
            return unresolved.isEmpty();
        }
    }

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
            List<FrozenClause> clauses,
            UUID style,
            UUID titleStyle
    ) {
        public FrozenSection {
            clauses = List.copyOf(clauses);
        }
    }

    /**
     * What the freeze produced, and everything wrong with it.
     *
     * @param unresolved        every token no clause could fill, deduplicated.
     *                          Which clause wanted each one is on the clause.
     * @param omittedRequired   sections that exist to satisfy a statute and ended
     *                          up with nothing in them, by name and statute.
     * @param brokenReferences  "citing -> cited" for every {{ref}} whose clause
     *                          did not print, or printed without a number. The
     *                          ref is left standing in the text.
     * @param separatedPairs    "clause -> clause it requires next (statute)" for
     *                          every requires_next that did not hold.
     */
    public record Frozen(
            List<FrozenSection> sections,
            List<String> unresolved,
            List<String> omittedRequired,
            List<String> brokenReferences,
            List<String> separatedPairs
    ) {
        public Frozen {
            sections = List.copyOf(sections);
            unresolved = List.copyOf(unresolved);
            omittedRequired = List.copyOf(omittedRequired);
            brokenReferences = List.copyOf(brokenReferences);
            separatedPairs = List.copyOf(separatedPairs);
        }

        /** Whether this is fit to put in front of a tenant. */
        public boolean isComplete() {
            return unresolved.isEmpty() && omittedRequired.isEmpty()
                    && brokenReferences.isEmpty() && separatedPairs.isEmpty();
        }
    }

    /** The document as the admin wrote it, with no park adjustments and nothing typed on. */
    public static Frozen freeze(List<DocumentSection> sections, TokenValues values) {
        return freeze(sections, List.of(), List.of(), values);
    }

    /**
     * Selects, numbers and renders, in print order.
     *
     * <p>A conditional clause prints only when the deal's value for its
     * condition field is one it was written for -- so a tenant on RUBS water
     * never sees the flat-water paragraph, and a clause is dropped outright
     * rather than printed with a blank in it. A clause whose parent dropped
     * goes with it.
     *
     * <p>A section whose clauses all dropped is dropped too: an empty heading
     * reading "SEPTIC ADDENDUM" with nothing under it is worse than no section.
     * A signature block is the exception, having no clauses by nature.
     *
     * <p>Numbering is assigned here, not authored, because whether a clause is
     * the fifth depends on which conditional clauses above it printed for this
     * particular deal. References are resolved after every section is numbered,
     * since a clause may cite one in another section.
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

        Clauses all = Clauses.of(sections);

        List<String> omittedRequired = new ArrayList<>();
        List<PlacedSection> placedSections = new ArrayList<>();

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

            List<Candidate> selected = new ArrayList<>();
            for (Candidate candidate : candidatesFor(section, excludedClauses, extraBySection)) {
                if (candidate.appliesTo(values)) {
                    selected.add(candidate);
                }
            }

            List<Placed> placed = place(selected, section, all);

            // A required section that came up empty is reported whatever kind of
            // section it is -- a signature block once hid this. An empty signature
            // block is still kept, having nothing to print but its own page.
            if (placed.isEmpty()) {
                if (section.required()) {
                    omittedRequired.add(describe(section));
                }
                if (!section.signatureBlock()) {
                    continue;
                }
            }
            placedSections.add(new PlacedSection(section, placed));
        }

        // Every label in the document, by variant group, before any body cites one.
        Map<UUID, String> labelByGroup = new LinkedHashMap<>();
        for (PlacedSection section : placedSections) {
            for (Placed placed : section.clauses()) {
                labelByGroup.putIfAbsent(placed.candidate().group(), placed.label());
            }
        }

        values = withAddenda(values, placedSections);

        List<FrozenSection> frozen = new ArrayList<>();
        Set<String> unresolved = new LinkedHashSet<>();
        Set<String> brokenReferences = new LinkedHashSet<>();
        List<String> separatedPairs = new ArrayList<>();

        for (PlacedSection section : placedSections) {
            List<Placed> placed = section.clauses();
            List<FrozenClause> clauses = new ArrayList<>();

            for (int i = 0; i < placed.size(); i++) {
                Placed here = placed.get(i);
                Candidate candidate = here.candidate();

                BodyRenderer.Rendered rendered = BodyRenderer.render(candidate.body(), values);
                unresolved.addAll(rendered.unresolved());
                String body = substituteRefs(rendered.text(), candidate, all, labelByGroup, brokenReferences);

                boolean keepWithNext = false;
                if (candidate.requiresNext() != null) {
                    Placed next = (i + 1 < placed.size()) ? placed.get(i + 1) : null;
                    if (next != null && next.candidate().group().equals(all.groupOf(candidate.requiresNext()))) {
                        keepWithNext = true;
                    } else {
                        separatedPairs.add(candidate.describe() + " -> " + all.describe(candidate.requiresNext())
                                + (candidate.statuteRef() == null ? "" : " (" + candidate.statuteRef() + ")"));
                    }
                }

                clauses.add(new FrozenClause(
                        BigDecimal.valueOf(i + 1L),
                        candidate.clauseKey(),
                        candidate.title(),
                        here.number(),
                        here.label(),
                        here.depth(),
                        body,
                        candidate.body(),
                        candidate.statuteRef(),
                        candidate.source(),
                        candidate.origin(),
                        rendered.unresolved(),
                        keepWithNext,
                        candidate.style()));
            }

            DocumentSection source = section.section();
            frozen.add(new FrozenSection(
                    BigDecimal.valueOf(frozen.size() + 1L),
                    source.name(),
                    source.sectionKey(),
                    source.signatureBlock(),
                    source.listedAsAddendum(),
                    source.statuteRef(),
                    clauses,
                    source.style(),
                    source.titleStyle()));
        }

        return new Frozen(frozen, List.copyOf(unresolved), omittedRequired,
                List.copyOf(brokenReferences), separatedPairs);
    }

    /**
     * {@code packet.addenda}: the attached sub-documents, by name, as they came out
     * of this freeze -- after park exclusions and empty sections are gone -- so a
     * clause listing them can never name one that is not in the envelope.
     */
    private static TokenValues withAddenda(TokenValues values, List<PlacedSection> placed) {
        List<String> names = new ArrayList<>();
        for (PlacedSection section : placed) {
            if (section.section().listedAsAddendum() && !section.clauses().isEmpty()) {
                names.add(section.section().name());
            }
        }
        if (names.isEmpty()) {
            return values;
        }
        Map<String, String> scalars = new LinkedHashMap<>(values.scalars());
        scalars.put(DocumentToken.PACKET_ADDENDA.token(), TokenFormatter.list(names));
        return new TokenValues(scalars, values.lists());
    }

    /**
     * The selected clauses of one section as a tree, walked depth first, each
     * with its number. A clause whose parent did not make it is dropped along
     * with its whole subtree.
     */
    private static List<Placed> place(List<Candidate> selected, DocumentSection section, Clauses all) {
        // A parent is found by variant group, so a child written under the FLAT
        // late-fee clause still sits under the PERCENT one when that is what printed.
        Map<UUID, Candidate> selectedByGroup = new LinkedHashMap<>();
        for (Candidate candidate : selected) {
            selectedByGroup.putIfAbsent(candidate.group(), candidate);
        }

        Map<UUID, List<Candidate>> childrenOf = new LinkedHashMap<>();
        List<Candidate> roots = new ArrayList<>();
        for (Candidate candidate : selected) {
            if (candidate.parent() == null) {
                roots.add(candidate);
                continue;
            }
            Candidate parent = selectedByGroup.get(all.groupOf(candidate.parent()));
            if (parent != null && parent != candidate) {
                childrenOf.computeIfAbsent(parent.source(), key -> new ArrayList<>()).add(candidate);
            }
            // else: the parent did not print, so neither does this
        }

        List<Placed> out = new ArrayList<>();
        walk(roots, List.of(), 1, childrenOf, section, out);
        return out;
    }

    /** {@code selected} is already in ordinal order, and so is every child list built from it. */
    private static void walk(List<Candidate> siblings, List<Integer> path, int depth,
                             Map<UUID, List<Candidate>> childrenOf, DocumentSection section,
                             List<Placed> out) {
        int counter = 0;
        for (Candidate candidate : siblings) {
            // An unnumbered heading still takes a level, as 0, so the items under
            // it letter from A the way they do under a numbered one.
            List<Integer> childPath = new ArrayList<>(path);
            childPath.add(0);
            String number = null;
            String label = null;
            if (candidate.numbered()) {
                counter++;
                childPath = new ArrayList<>(path);
                childPath.add(counter);
                number = ClauseNumbering.format(formatFor(section.numberFormats(), childPath.size()), childPath);
                label = ClauseNumbering.format(formatFor(section.citeFormats(), childPath.size()), childPath);
            }
            out.add(new Placed(candidate, depth, number, label));
            walk(childrenOf.getOrDefault(candidate.source(), List.of()), childPath, depth + 1,
                    childrenOf, section, out);
        }
    }

    /** Deeper than the section has formats for: digits, rather than nothing. */
    private static String formatFor(List<String> formats, int level) {
        return level <= formats.size() ? formats.get(level - 1) : "{" + level + "}";
    }

    /**
     * Replaces each {{ref}} with the cited clause's label. One that cannot be
     * resolved stays in the text, like an unfilled token, and is reported.
     */
    private static String substituteRefs(String text, Candidate citing, Clauses all,
                                         Map<UUID, String> labelByGroup, Set<String> broken) {
        Matcher ref = TokenSyntax.REF.matcher(text);
        StringBuilder out = new StringBuilder();
        while (ref.find()) {
            UUID cited = UUID.fromString(ref.group(1));
            String label = labelByGroup.get(all.groupOf(cited));
            if (label == null) {
                broken.add(citing.describe() + " -> " + all.describe(cited));
                ref.appendReplacement(out, Matcher.quoteReplacement(ref.group(0)));
            } else {
                ref.appendReplacement(out, Matcher.quoteReplacement(label));
            }
        }
        ref.appendTail(out);
        return out.toString();
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

    /** A clause with its place in the tree decided. */
    private record Placed(Candidate candidate, int depth, String number, String label) { }

    private record PlacedSection(DocumentSection section, List<Placed> clauses) { }

    /**
     * Every template clause in the document, printed or not -- a reference or a
     * parent may name a clause that did not print, and still has to be resolved
     * through its variant group and named in a report.
     */
    private record Clauses(Map<UUID, TemplateClause> byUuid) {

        static Clauses of(List<DocumentSection> sections) {
            Map<UUID, TemplateClause> byUuid = new LinkedHashMap<>();
            for (DocumentSection section : sections) {
                for (TemplateClause clause : section.clauses()) {
                    byUuid.put(clause.uuid(), clause);
                }
            }
            return new Clauses(byUuid);
        }

        /** Unknown uuids are their own group, so they resolve to nothing rather than throwing. */
        UUID groupOf(UUID uuid) {
            TemplateClause clause = byUuid.get(uuid);
            return clause == null ? uuid : clause.variantGroup();
        }

        String describe(UUID uuid) {
            TemplateClause clause = byUuid.get(uuid);
            return clause == null ? uuid.toString() : name(clause.title(), clause.clauseKey(), clause.body());
        }
    }

    /** A title if the clause has one, then its key, then the start of its body. */
    private static String name(String title, String clauseKey, String body) {
        if (title != null && !title.isBlank()) {
            return title;
        }
        if (clauseKey != null && !clauseKey.isBlank()) {
            return clauseKey;
        }
        String text = body == null ? "" : body.strip();
        return text.length() <= 40 ? text : text.substring(0, 40) + "...";
    }

    /**
     * One clause that might print, from whichever of the three sources wrote
     * it. The freeze does the same thing to all three, so flattening them here
     * is what keeps the selection loop from branching on provenance.
     *
     * <p>{@code group} is the variant group for a template clause and the
     * clause's own uuid otherwise.
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
            ClauseOrigin origin,
            UUID group,
            UUID parent,
            boolean numbered,
            UUID requiresNext,
            UUID style
    ) {

        static Candidate of(TemplateClause clause) {
            return new Candidate(clause.ordinal(), clause.clauseKey(), clause.title(),
                    clause.body(), clause.conditionField(), clause.conditionValues(),
                    clause.statuteRef(), clause.uuid(), ClauseOrigin.TEMPLATE,
                    clause.variantGroup(), clause.parent(), clause.numbered(),
                    clause.requiresNext(), clause.style());
        }

        static Candidate of(PropertyDocumentCustomization customization) {
            return new Candidate(customization.ordinal(), null, customization.title(),
                    customization.body(), customization.conditionField(),
                    customization.conditionValues(), null,
                    customization.uuid(), ClauseOrigin.PROPERTY,
                    customization.uuid(), customization.parent(), true, null, null);
        }

        /**
         * No condition and no statute: a clause typed onto one agreement is
         * true of that agreement or it would not have been typed, and nothing
         * one office worker writes satisfies a statute.
         */
        static Candidate of(InstrumentAddition addition) {
            return new Candidate(addition.ordinal(), null, addition.title(),
                    addition.body(), null, List.of(), null,
                    addition.uuid(), ClauseOrigin.INSTRUMENT,
                    addition.uuid(), addition.parent(), true, null, null);
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

        String describe() {
            return name(title, clauseKey, body);
        }
    }
}
