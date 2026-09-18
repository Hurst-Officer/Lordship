package io.github.lordship.documenttemplate;

import io.github.lordship.shared.ClauseNumbering;
import io.github.lordship.shared.DomainProblem.Problem;
import io.github.lordship.shared.StyleRules;
import io.github.lordship.shared.TokenSyntax;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Every rule about clauses pointing at other clauses and at styles: parent,
 * variant, keep-with-next, references, style. Pure -- the document in, problems
 * out -- so the services only decide which exception to throw.
 *
 * <p>The checks run against the document as it would stand after the edit, which
 * is how a change to one clause gets caught when it breaks a rule about another
 * (moving a clause's ordinal can land it between a pair).
 */
public final class ClauseLinks {

    public static final int MAX_DEPTH = 3;

    private ClauseLinks() {}

    // ---- one clause ----------------------------------------------------------

    /** What is wrong with how this clause points at others. Empty when nothing is. */
    public static List<Problem> problemsWith(DocumentTemplate doc, UUID clauseUuid) {
        List<Problem> problems = new ArrayList<>();
        TemplateClause clause = doc.clause(clauseUuid).orElse(null);
        if (clause == null || clause.isSoftDeleted()) {
            return problems;
        }
        DocumentSection section = doc.sectionOf(clauseUuid).orElse(null);
        if (section == null) {
            return problems;
        }

        if (clause.parent() != null) {
            TemplateClause parent = liveIn(section, clause.parent());
            if (clause.parent().equals(clauseUuid)) {
                problems.add(Problem.onField("parent", "clause.parent_is_itself"));
            } else if (parent == null) {
                problems.add(Problem.onField("parent", "clause.parent_not_in_section", section.name()));
            } else if (isAncestor(section, clauseUuid, clause.parent())) {
                problems.add(Problem.onField("parent", "clause.parent_cycle", name(parent)));
            } else if (depthOf(section, clauseUuid) > MAX_DEPTH) {
                problems.add(Problem.onField("parent", "clause.too_deep", MAX_DEPTH));
            }
        }

        if (clause.variantOf() != null) {
            TemplateClause primary = liveIn(section, clause.variantOf());
            if (clause.variantOf().equals(clauseUuid)) {
                problems.add(Problem.onField("variant_of", "clause.variant_is_itself"));
            } else if (primary == null) {
                problems.add(Problem.onField("variant_of", "clause.variant_not_in_section", section.name()));
            } else if (primary.variantOf() != null) {
                problems.add(Problem.onField("variant_of", "clause.variant_of_a_variant", name(primary)));
            } else if (!Objects.equals(primary.parent(), clause.parent())) {
                problems.add(Problem.onField("variant_of", "clause.variant_parent_differs", name(primary)));
            }
        }

        if (clause.requiresNext() != null) {
            TemplateClause next = liveIn(section, clause.requiresNext());
            if (clause.requiresNext().equals(clauseUuid)) {
                problems.add(Problem.onField("requires_next", "clause.next_is_itself"));
            } else if (next == null) {
                problems.add(Problem.onField("requires_next", "clause.next_not_in_section", section.name()));
            } else {
                pairProblem(section, clause, next, List.of()).ifPresent(problems::add);
            }
        }

        if (clause.style() != null && !isNamedStyle(doc, clause.style())) {
            problems.add(Problem.onField("style", "clause.style_not_in_document"));
        }

        for (UUID cited : clause.refs()) {
            if (!isLive(doc, cited)) {
                problems.add(Problem.onField("body", "clause.ref_not_in_document", cited));
            }
        }
        return List.copyOf(problems);
    }

    /**
     * Every pair in this section that some edit may have broken -- run after a
     * clause moves, since a move can land it between two clauses that are not it.
     */
    public static List<Problem> brokenPairsIn(DocumentTemplate doc, UUID sectionUuid) {
        DocumentSection section = sectionById(doc, sectionUuid).orElse(null);
        List<Problem> problems = new ArrayList<>();
        if (section == null) {
            return problems;
        }
        for (TemplateClause clause : live(section)) {
            TemplateClause next = clause.requiresNext() == null ? null : liveIn(section, clause.requiresNext());
            if (next != null) {
                pairProblem(section, clause, next, List.of()).ifPresent(problems::add);
            }
        }
        return List.copyOf(problems);
    }

    // ---- deleting and excluding ----------------------------------------------

    /** Why this clause cannot be deleted from the document, or empty when it can. */
    public static Optional<Problem> deleteBlocker(DocumentTemplate doc, UUID clauseUuid) {
        for (DocumentSection section : doc.sections()) {
            for (TemplateClause other : live(section)) {
                if (other.uuid().equals(clauseUuid)) {
                    continue;
                }
                if (clauseUuid.equals(other.parent())) {
                    return Optional.of(Problem.of("clause.has_sub_clauses", name(other)));
                }
                if (clauseUuid.equals(other.variantOf())) {
                    return Optional.of(Problem.of("clause.has_variants", name(other)));
                }
                if (clauseUuid.equals(other.requiresNext())) {
                    return Optional.of(Problem.of("clause.kept_with_by", name(other)));
                }
                if (other.refs().contains(clauseUuid)) {
                    return Optional.of(Problem.of("clause.cited_by", name(other)));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Why a park may not exclude these clauses, or empty when it may.
     *
     * <p>{@code dropping} is what the exclusion takes out: one clause, or a whole
     * section. Sub-clauses go with their parent, so they are counted too. A clause
     * that is cited or kept with is still fine to drop when another variant of it
     * will print in its place.
     *
     * @param alreadyDropped clauses this park already excludes, directly or by section
     * @param kind           "clause" or "section" -- which message the refusal uses
     */
    public static Optional<Problem> exclusionBlocker(DocumentTemplate doc, Set<UUID> dropping,
                                                     Set<UUID> alreadyDropped, String kind) {
        Set<UUID> gone = new HashSet<>(alreadyDropped);
        gone.addAll(withDescendants(doc, dropping));

        for (DocumentSection section : doc.sections()) {
            for (TemplateClause other : live(section)) {
                if (gone.contains(other.uuid())) {
                    continue;
                }
                if (other.requiresNext() != null && !survives(doc, other.requiresNext(), gone)) {
                    return Optional.of(Problem.of("customization." + kind + "_is_kept_with", name(other)));
                }
                for (UUID cited : other.refs()) {
                    if (!survives(doc, cited, gone)) {
                        return Optional.of(Problem.of("customization." + kind + "_is_cited", name(other)));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Every live clause of a section, for a section exclusion. */
    public static Set<UUID> clausesOf(DocumentTemplate doc, UUID sectionUuid) {
        Set<UUID> out = new LinkedHashSet<>();
        sectionById(doc, sectionUuid).ifPresent(section -> live(section).forEach(clause -> out.add(clause.uuid())));
        return out;
    }

    // ---- a park's own clause -------------------------------------------------

    /**
     * What is wrong with a clause a park added: its parent must be a template
     * clause in the same section, its references must name clauses of this
     * document, and it may not land between a pair.
     */
    public static List<Problem> problemsWithParkClause(DocumentTemplate doc, PropertyDocumentCustomization added) {
        List<Problem> problems = new ArrayList<>();
        DocumentSection section = sectionById(doc, added.section()).orElse(null);
        if (section == null) {
            return problems;
        }

        if (added.parent() != null && liveIn(section, added.parent()) == null) {
            problems.add(Problem.onField("parent", "customization.parent_not_in_section", section.name()));
        } else if (added.parent() != null && depthOf(section, added.parent()) + 1 > MAX_DEPTH) {
            problems.add(Problem.onField("parent", "clause.too_deep", MAX_DEPTH));
        }

        for (UUID cited : TokenSyntax.refsIn(added.body())) {
            if (!isLive(doc, cited)) {
                problems.add(Problem.onField("body", "clause.ref_not_in_document", cited));
            }
        }

        if (added.ordinal() != null && problems.isEmpty()) {
            Node park = new Node(added.uuid(), added.parent(), added.ordinal(), added.uuid(), "");
            for (TemplateClause clause : live(section)) {
                TemplateClause next = clause.requiresNext() == null ? null : liveIn(section, clause.requiresNext());
                if (next != null && pairProblem(section, clause, next, List.of(park)).isPresent()
                        && pairProblem(section, clause, next, List.of()).isEmpty()) {
                    problems.add(Problem.onField("ordinal", "customization.interrupts_pair", name(clause), name(next)));
                }
            }
        }
        return List.copyOf(problems);
    }

    // ---- sections and styles -------------------------------------------------

    /** Number formats that do not parse, and styles that are not this document's. */
    public static List<Problem> problemsWithSection(DocumentTemplate doc, UUID sectionUuid) {
        DocumentSection section = sectionById(doc, sectionUuid).orElse(null);
        List<Problem> problems = new ArrayList<>();
        if (section == null) {
            return problems;
        }
        formatProblems("number_formats", section.numberFormats(), problems);
        formatProblems("cite_formats", section.citeFormats(), problems);
        if (section.style() != null && !isNamedStyle(doc, section.style())) {
            problems.add(Problem.onField("style", "section.style_not_in_document"));
        }
        if (section.titleStyle() != null && !isNamedStyle(doc, section.titleStyle())) {
            problems.add(Problem.onField("title_style", "section.style_not_in_document"));
        }
        return List.copyOf(problems);
    }

    /** Css that could leave its rule or fetch something. */
    public static List<Problem> problemsWithStyle(DocumentStyle style) {
        List<String> refused = StyleRules.problemsIn(style.css());
        return refused.isEmpty()
                ? List.of()
                : List.of(Problem.onField("css", "style.css_not_allowed", String.join("  ", refused)));
    }

    /** A second style restyling the same part of the page would leave which one wins to chance. */
    public static Optional<Problem> duplicateTarget(DocumentTemplate doc, DocumentStyle style) {
        if (style.target() == null) {
            return Optional.empty();
        }
        return doc.styles().stream()
                .filter(other -> !other.isSoftDeleted() && !other.uuid().equals(style.uuid())
                        && other.target() == style.target())
                .findFirst()
                .map(other -> Problem.of("style.target_taken", style.target().name(), other.name()));
    }

    private static void formatProblems(String field, List<String> formats, List<Problem> problems) {
        if (formats.isEmpty() || formats.size() > MAX_DEPTH) {
            problems.add(Problem.onField(field, "section.formats_count", MAX_DEPTH));
            return;
        }
        for (int level = 1; level <= formats.size(); level++) {
            List<String> wrong = ClauseNumbering.problemsIn(formats.get(level - 1), level);
            if (!wrong.isEmpty()) {
                problems.add(Problem.onField(field, "section.format_invalid",
                        level, formats.get(level - 1), String.join("  ", wrong)));
            }
        }
    }

    // ---- the section as the author laid it out -------------------------------

    /**
     * A pair is broken when anything other than a variant of either half sits
     * between them in the order the author laid the section out. Conditions are
     * ignored on purpose: a clause that only prints for some deals still breaks
     * the pair on those deals.
     */
    private static Optional<Problem> pairProblem(DocumentSection section, TemplateClause first,
                                                 TemplateClause second, List<Node> extra) {
        List<Node> order = authoringOrder(section, extra);
        int from = indexOf(order, first.uuid());
        int to = indexOf(order, second.uuid());
        if (from < 0 || to < 0) {
            return Optional.empty();
        }
        if (to < from) {
            return Optional.of(Problem.onField("requires_next", "clause.next_is_above", name(first), name(second)));
        }
        for (int i = from + 1; i < to; i++) {
            Node between = order.get(i);
            if (!between.group().equals(first.variantGroup()) && !between.group().equals(second.variantGroup())) {
                return Optional.of(Problem.onField("requires_next", "clause.pair_interrupted",
                        name(first), name(second), between.name()));
            }
        }
        return Optional.empty();
    }

    private record Node(UUID uuid, UUID parent, BigDecimal ordinal, UUID group, String name) { }

    /** Depth first, siblings by ordinal -- the order the freeze prints in, before conditions. */
    private static List<Node> authoringOrder(DocumentSection section, List<Node> extra) {
        List<Node> nodes = new ArrayList<>();
        for (TemplateClause clause : live(section)) {
            nodes.add(new Node(clause.uuid(), clause.parent(), clause.ordinal(), clause.variantGroup(), name(clause)));
        }
        nodes.addAll(extra);
        nodes.sort(Comparator.comparing(Node::ordinal));

        Set<UUID> present = new HashSet<>();
        nodes.forEach(node -> present.add(node.uuid()));

        Map<UUID, List<Node>> childrenOf = new LinkedHashMap<>();
        List<Node> roots = new ArrayList<>();
        for (Node node : nodes) {
            if (node.parent() == null || !present.contains(node.parent())) {
                roots.add(node);
            } else {
                childrenOf.computeIfAbsent(node.parent(), key -> new ArrayList<>()).add(node);
            }
        }
        List<Node> out = new ArrayList<>();
        walk(roots, childrenOf, out, new HashSet<>());
        return out;
    }

    private static void walk(List<Node> siblings, Map<UUID, List<Node>> childrenOf, List<Node> out, Set<UUID> seen) {
        for (Node node : siblings) {
            if (seen.add(node.uuid())) { // a parent cycle is reported elsewhere; do not loop on it here
                out.add(node);
                walk(childrenOf.getOrDefault(node.uuid(), List.of()), childrenOf, out, seen);
            }
        }
    }

    private static int indexOf(List<Node> order, UUID uuid) {
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).uuid().equals(uuid)) {
                return i;
            }
        }
        return -1;
    }

    // ---- small helpers -------------------------------------------------------

    /** Whether {@code candidate} sits above {@code clause} -- which would make it its own ancestor. */
    private static boolean isAncestor(DocumentSection section, UUID clause, UUID candidate) {
        Set<UUID> seen = new HashSet<>();
        UUID at = candidate;
        while (at != null && seen.add(at)) {
            if (at.equals(clause)) {
                return true;
            }
            TemplateClause up = liveIn(section, at);
            at = up == null ? null : up.parent();
        }
        return false;
    }

    private static int depthOf(DocumentSection section, UUID clause) {
        int depth = 0;
        Set<UUID> seen = new HashSet<>();
        UUID at = clause;
        while (at != null && seen.add(at)) {
            TemplateClause here = liveIn(section, at);
            if (here == null) {
                break;
            }
            depth++;
            at = here.parent();
        }
        return depth;
    }

    private static Set<UUID> withDescendants(DocumentTemplate doc, Set<UUID> roots) {
        Set<UUID> out = new LinkedHashSet<>(roots);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (DocumentSection section : doc.sections()) {
                for (TemplateClause clause : live(section)) {
                    if (clause.parent() != null && out.contains(clause.parent()) && out.add(clause.uuid())) {
                        grew = true;
                    }
                }
            }
        }
        return out;
    }

    /** Whether the cited clause, or another variant of it, is still in the document. */
    private static boolean survives(DocumentTemplate doc, UUID cited, Set<UUID> gone) {
        TemplateClause target = doc.clause(cited).orElse(null);
        if (target == null || target.isSoftDeleted()) {
            return true; // a dangling ref is the save path's problem, not this park's
        }
        UUID group = target.variantGroup();
        for (DocumentSection section : doc.sections()) {
            for (TemplateClause clause : live(section)) {
                if (clause.variantGroup().equals(group) && !gone.contains(clause.uuid())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isLive(DocumentTemplate doc, UUID clause) {
        return doc.clause(clause).filter(found -> !found.isSoftDeleted()).isPresent();
    }

    private static boolean isNamedStyle(DocumentTemplate doc, UUID style) {
        return doc.style(style).filter(found -> !found.isSoftDeleted() && found.isNamed()).isPresent();
    }

    private static TemplateClause liveIn(DocumentSection section, UUID uuid) {
        for (TemplateClause clause : section.clauses()) {
            if (clause.uuid().equals(uuid) && !clause.isSoftDeleted()) {
                return clause;
            }
        }
        return null;
    }

    private static List<TemplateClause> live(DocumentSection section) {
        return section.clauses().stream().filter(clause -> !clause.isSoftDeleted()).toList();
    }

    private static Optional<DocumentSection> sectionById(DocumentTemplate doc, UUID sectionUuid) {
        return doc.sections().stream()
                .filter(section -> section.uuid().equals(sectionUuid))
                .findFirst();
    }

    /** A title if the clause has one, then its key, then the start of its body. */
    static String name(TemplateClause clause) {
        if (clause.title() != null && !clause.title().isBlank()) {
            return clause.title();
        }
        if (clause.clauseKey() != null && !clause.clauseKey().isBlank()) {
            return clause.clauseKey();
        }
        String text = clause.body() == null ? "" : clause.body().strip();
        return text.length() <= 40 ? text : text.substring(0, 40) + "...";
    }
}
