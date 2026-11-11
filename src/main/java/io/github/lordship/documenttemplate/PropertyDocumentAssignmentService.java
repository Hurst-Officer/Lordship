package io.github.lordship.documenttemplate;

import io.github.lordship.audit.ActingAgent;
import io.github.lordship.audit.AuditContext;
import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.documenttemplate.internal.PropertyDocumentAssignmentRepository;
import io.github.lordship.documenttemplate.internal.PropertyDocumentAssignmentRow;
import io.github.lordship.documenttemplate.internal.PropertyDocumentCustomizationRepository;
import io.github.lordship.documenttemplate.internal.PropertyDocumentCustomizationRow;
import io.github.lordship.properties.PropertyService;
import io.github.lordship.shared.ClauseBodyRules;
import io.github.lordship.shared.DomainProblem;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.RuleConflict;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Which documents a park may generate, and what it changes about them.
 *
 * <p>Assigning is the act that authorizes a property to produce a kind of paper
 * at all -- a park with no LEASE assignment cannot generate a lease, however
 * complete the global template is.
 *
 * <p>Deliberately a reference rather than a copy, so an edit to the global
 * wording reaches every park. That is the opposite of {@code terms_template},
 * which is copied in, and the asymmetry is the point: a statutory correction
 * should land everywhere, a price change should not rewrite what existing
 * tenants already agreed.
 *
 * <p>Customizations are the escape hatch that keeps the reference honest. A
 * park on city sewer drops the septic section; a park with a rule of its own
 * adds a clause. What it cannot do is rewrite a global body locally, because
 * then the statutory correction stops landing.
 */
@Service
public class PropertyDocumentAssignmentService {

    private final PropertyDocumentAssignmentRepository assignmentRepository;
    private final PropertyDocumentCustomizationRepository customizationRepository;
    private final DocumentTemplateService documentTemplateService;
    private final PropertyService propertyService;
    private final AuditService auditService;
    private final AuditContext auditContext;

    public PropertyDocumentAssignmentService(PropertyDocumentAssignmentRepository assignmentRepository,
                                             PropertyDocumentCustomizationRepository customizationRepository,
                                             DocumentTemplateService documentTemplateService,
                                             PropertyService propertyService,
                                             AuditService auditService,
                                             AuditContext auditContext) {
        this.assignmentRepository = assignmentRepository;
        this.customizationRepository = customizationRepository;
        this.documentTemplateService = documentTemplateService;
        this.propertyService = propertyService;
        this.auditService = auditService;
        this.auditContext = auditContext;
    }

    // ---- reading -------------------------------------------------------------

    /** Everything this park can generate. Two queries, not one per row. */
    public List<PropertyDocumentAssignment> findByProperty(UUID propertyId) {
        List<PropertyDocumentAssignmentRow> rows = assignmentRepository.findByProperty(propertyId);
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<PropertyDocumentCustomization>> byAssignment =
                customizationsFor(rows.stream().map(PropertyDocumentAssignmentRow::uuid).toList());

        return rows.stream()
                .map(row -> row.toPropertyDocumentAssignment(
                        summaryFor(row),
                        byAssignment.getOrDefault(row.uuid(), List.of())))
                .toList();
    }

    public Optional<PropertyDocumentAssignment> findById(UUID uuid) {
        return assignmentRepository.findById(uuid).map(this::hydrate);
    }

    /**
     * The document this park uses for one kind of deal. This is what the setup
     * screen resolves, and why the office worker is never asked to pick a
     * document: the unique index guarantees at most one answer.
     *
     * <p>The template comes back as a summary. For the clause bodies, which is
     * what the freeze needs, use {@link #findForGenerate}.
     */
    public Optional<PropertyDocumentAssignment> findForProperty(UUID propertyId,
                                                                AgreementType agreementType,
                                                                InstrumentType instrumentType) {
        return assignmentRepository
                .findByPropertyAndKind(propertyId, agreementType, instrumentType)
                .map(this::hydrate);
    }

    /**
     * The same answer with the whole document in it -- every section, every
     * clause body, and this park's changes to them.
     *
     * <p>Separate from {@link #findForProperty} because the two callers want
     * opposite things. A park's document list wants names and versions and
     * would drag sixty clause bodies per row; the freeze wants exactly those
     * bodies and would be wrong without them.
     */
    public Optional<PropertyDocumentAssignment> findForGenerate(UUID propertyId,
                                                                AgreementType agreementType,
                                                                InstrumentType instrumentType) {
        return assignmentRepository
                .findByPropertyAndKind(propertyId, agreementType, instrumentType)
                .map(row -> row.toPropertyDocumentAssignment(
                        fullTemplateFor(row),
                        customizationsOf(row.uuid())));
    }

    // ---- assignment ----------------------------------------------------------

    /**
     * Empty means the property or the document does not exist. A park that
     * already has a document for this kind of deal is a rule violation rather
     * than a missing record, so that comes back as a conflict naming the
     * document already in place.
     */
    @Transactional
    public Optional<PropertyDocumentAssignment> assign(UUID propertyId, UUID documentTemplateId) {
        if (propertyService.findByPropertyId(propertyId).isEmpty()) {
            return Optional.empty();
        }

        // The document cannot be deleted between this check and the save (deleteTemplate takes the same lock)
        documentTemplateService.lockForEdit(documentTemplateId);
        Optional<DocumentTemplate> templateOpt = documentTemplateService.findById(documentTemplateId);
        if (templateOpt.isEmpty()) {
            return Optional.empty();
        }
        DocumentTemplate template = templateOpt.get();

        assignmentRepository
                .findByPropertyAndKind(propertyId, template.agreementType(), template.instrumentType())
                .ifPresent(existing -> {
                    String inUse = documentTemplateService.findById(existing.documentTemplate())
                            .map(DocumentTemplate::name)
                            .orElse(existing.documentTemplate().toString());

                    throw RuleConflict.of("assignment.already_in_use",
                            inUse,
                            template.agreementType(),
                            template.instrumentType());
                });

        PropertyDocumentAssignmentRow saved = assignmentRepository.save(
                propertyId,
                documentTemplateId,
                template.agreementType(),
                template.instrumentType(),
                ActingAgent.resolve(auditContext));

        auditService.recordInsert(
                "property_document_assignment", saved.uuid(), AuditMapper.toMap(saved));

        return Optional.of(saved.toPropertyDocumentAssignment(summaryOf(template)));
    }

    @Transactional
    public Optional<PropertyDocumentAssignment> patchAssignment(UUID uuid, Map<String, Object> changes) {
        Optional<PropertyDocumentAssignmentRow> beforeOpt = assignmentRepository.findById(uuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        PropertyDocumentAssignmentRow before = beforeOpt.get();

        Optional<PropertyDocumentAssignmentRow> afterOpt = assignmentRepository.patch(uuid, changes);
        if (afterOpt.isEmpty()) {
            return Optional.empty();
        }

        AuditMapper.Diff diff = AuditMapper.diff(before, afterOpt.get());
        if (!diff.before().isEmpty()) {
            auditService.recordUpdate("property_document_assignment", uuid, diff.before(), diff.after());
        }

        return Optional.of(hydrate(afterOpt.get()));
    }

    /**
     * Stops this park generating that kind of document from now on. Nothing
     * already generated is affected -- an instrument holds its own wording, and
     * a lease that went out last year does not become unsigned because someone
     * changed a setup screen.
     */
    @Transactional
    public boolean unassign(UUID uuid) {
        return assignmentRepository.findById(uuid).map(existing -> {
            if (!assignmentRepository.softDelete(uuid)) {
                return false;
            }
            auditService.recordDelete(
                    "property_document_assignment", uuid, AuditMapper.toMap(existing));
            return true;
        }).orElse(false);
    }

    // ---- what the park changed -----------------------------------------------

    /**
     * This park does not use that sub-document. Empty means no such assignment.
     *
     * <p>A required section is refused: it exists to satisfy the statute named
     * on it, and no park may drop it. {@code DocumentSection.canBeExcludedByAProperty}
     * is the single statement of that rule.
     */
    @Transactional
    public Optional<PropertyDocumentAssignment> excludeSection(UUID assignmentUuid, UUID sectionUuid) {
        return withAssignment(assignmentUuid, (row, document) -> {
            DocumentSection section = sectionIn(document, sectionUuid);

            if (!section.canBeExcludedByAProperty()) {
                throw section.statuteRef() == null
                        ? RuleConflict.of("section.is_required", section.name())
                        : RuleConflict.of("section.is_required_by_statute",
                        section.name(), section.statuteRef());
            }
            refuseDuplicateExclusion(assignmentUuid, sectionUuid);
            refuseIfNeeded(document, assignmentUuid, ClauseLinks.clausesOf(document, sectionUuid), "section", sectionUuid);

            PropertyDocumentCustomizationRow saved = customizationRepository.excludeSection(
                    assignmentUuid, sectionUuid, ActingAgent.resolve(auditContext));
            recordInsert(saved);
            return null;
        });
    }

    /** This park keeps the section but not that one paragraph of it. */
    @Transactional
    public Optional<PropertyDocumentAssignment> excludeClause(UUID assignmentUuid, UUID clauseUuid) {
        return withAssignment(assignmentUuid, (row, document) -> {
            TemplateClause clause = clauseIn(document, clauseUuid);

            if (clause.required()) {
                throw clause.statuteRef() == null
                        ? RuleConflict.of("clause.is_required")
                        : RuleConflict.of("clause.is_required_by_statute", clause.statuteRef());
            }
            refuseDuplicateExclusion(assignmentUuid, clauseUuid);
            refuseIfNeeded(document, assignmentUuid, Set.of(clauseUuid), "clause", null);

            PropertyDocumentCustomizationRow saved = customizationRepository.excludeClause(
                    assignmentUuid, clauseUuid, ActingAgent.resolve(auditContext));
            recordInsert(saved);
            return null;
        });
    }

    /**
     * A park rule of its own, added empty and filled in by a patch -- the same
     * shape as adding a template clause, so "add clause" is a button on both
     * screens rather than a form on one and a button on the other.
     */
    @Transactional
    public Optional<PropertyDocumentAssignment> addClause(UUID assignmentUuid, UUID sectionUuid) {
        return withAssignment(assignmentUuid, (row, document) -> {
            sectionIn(document, sectionUuid);

            PropertyDocumentCustomizationRow saved = customizationRepository.addClause(
                    assignmentUuid, sectionUuid, ActingAgent.resolve(auditContext));
            recordInsert(saved);
            return null;
        });
    }

    /**
     * The body, title, position and condition of a clause this park wrote.
     *
     * <p>Held to the same rules a template clause is: an unknown token is
     * refused here rather than discovered at generate, and a clause may only
     * branch on a method. A park writing lease wording is writing lease wording.
     *
     * <p>An exclusion has nothing to edit -- it is a row that names a target and
     * says "not this one" -- so patching one is a mistake worth reporting.
     */
    @Transactional
    public Optional<PropertyDocumentAssignment> patchCustomization(UUID customizationUuid,
                                                                   Map<String, Object> changes) {
        Optional<PropertyDocumentCustomizationRow> beforeOpt =
                customizationRepository.findById(customizationUuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        PropertyDocumentCustomizationRow before = beforeOpt.get();

        if (before.action() != CustomizationAction.ADD_CLAUSE) {
            throw InvalidRequest.of("customization.not_editable", before.action());
        }

        Map<String, Object> effective = new HashMap<>(ClauseBodyRules.withConditionClearing(changes));
        ClauseBodyRules.validateBody(effective);
        ClauseBodyRules.validateCondition(
                before.conditionField(), before.conditionValues(), effective);
        // DocumentFreeze sorts a section's clauses by ordinal, so a park clause must always have one
        if (effective.containsKey("ordinal") && effective.get("ordinal") == null) {
            throw InvalidRequest.onField("ordinal", "customization.ordinal_required");
        }
        if (effective.get("parent") != null && !(effective.get("parent") instanceof UUID)) {
            try {
                effective.put("parent", UUID.fromString(String.valueOf(effective.get("parent"))));
            } catch (IllegalArgumentException e) {
                throw InvalidRequest.onField("parent", "request.not_a_uuid", effective.get("parent"));
            }
        }

        // The checks below read the document's clauses: an edit of the document waits until this change is saved
        assignmentRepository.findById(before.assignment())
                .ifPresent(assignment -> documentTemplateService.lockForEdit(assignment.documentTemplate()));

        Optional<PropertyDocumentCustomizationRow> afterOpt =
                customizationRepository.patch(customizationUuid, effective);
        if (afterOpt.isEmpty()) {
            return Optional.empty();
        }

        // Against the document as it stands; a refusal rolls the write back.
        DocumentTemplate document = assignmentRepository.findById(before.assignment())
                .map(this::fullTemplateFor)
                .orElseThrow(() -> new IllegalStateException(
                        "Customization " + customizationUuid + " points at an assignment that no longer exists"));
        // New words may not cite a clause this park leaves out. A change that leaves the words alone (a move, a
        // title) is not held to that, so a clause saved before this rule can still be moved or renamed.
        Set<UUID> parkLeavesOut = effective.containsKey("body")
                ? leftOut(document, customizationsOf(before.assignment()))
                : Set.of();
        List<DomainProblem.Problem> problems = ClauseLinks.problemsWithParkClause(
                document, afterOpt.get().toPropertyDocumentCustomization(), parkLeavesOut);
        if (!problems.isEmpty()) {
            throw InvalidRequest.withDetails("customization.not_saved", problems);
        }

        AuditMapper.Diff diff = AuditMapper.diff(before, afterOpt.get());
        if (!diff.before().isEmpty()) {
            auditService.recordUpdate("property_document_customization", customizationUuid,
                    diff.before(), diff.after());
        }

        return findById(before.assignment());
    }

    /**
     * Undoes a customization, putting the document back the way the template
     * wrote it. Leases already generated keep what they said.
     */
    @Transactional
    public boolean removeCustomization(UUID customizationUuid) {
        return customizationRepository.findById(customizationUuid).map(existing -> {
            if (!customizationRepository.softDelete(customizationUuid)) {
                return false;
            }
            auditService.recordDelete("property_document_customization",
                    customizationUuid, AuditMapper.toMap(existing));
            return true;
        }).orElse(false);
    }

    // ---- internals -----------------------------------------------------------

    /** What a customization edit does, given the assignment and the document it belongs to. */
    private interface Change {
        Void apply(PropertyDocumentAssignmentRow row, DocumentTemplate document);
    }

    /**
     * Every customization edit does the same three things first: find the
     * assignment, load the document behind it, and hand the caller back the
     * assignment as it now stands. Empty means no such assignment.
     *
     * <p>Note what does NOT happen here: the template's version is not bumped.
     * That number says which global wording an instrument was cut from, and one
     * park adding a rule has not changed the global wording.
     */
    private Optional<PropertyDocumentAssignment> withAssignment(UUID assignmentUuid, Change change) {
        Optional<PropertyDocumentAssignmentRow> rowOpt = assignmentRepository.findById(assignmentUuid);
        if (rowOpt.isEmpty()) {
            return Optional.empty();
        }
        PropertyDocumentAssignmentRow row = rowOpt.get();

        // The checks below read the document's clauses: an edit of the document waits until this change is saved,
        // and this change waits for an edit already under way
        documentTemplateService.lockForEdit(row.documentTemplate());
        change.apply(row, fullTemplateFor(row));
        return Optional.of(hydrate(row));
    }

    /**
     * A park may only exclude something its own document actually contains.
     * Without this a stale screen, or a uuid from another park's document,
     * writes a row that silently excludes nothing.
     */
    private static DocumentSection sectionIn(DocumentTemplate document, UUID sectionUuid) {
        return document.sectionsInOrder().stream()
                .filter(section -> section.uuid().equals(sectionUuid))
                .findFirst()
                .orElseThrow(() -> InvalidRequest.onField(
                        "sectionId", "customization.section_not_in_document", document.name()));
    }

    private static TemplateClause clauseIn(DocumentTemplate document, UUID clauseUuid) {
        return document.clausesInOrder().stream()
                .filter(clause -> clause.uuid().equals(clauseUuid))
                .findFirst()
                .orElseThrow(() -> InvalidRequest.onField(
                        "clauseId", "customization.clause_not_in_document", document.name()));
    }

    // Excluding the same thing twice is harmless to the freeze and confusing on
    // the screen -- two rows saying one thing, and undoing one of them appears
    // to do nothing.
    private void refuseDuplicateExclusion(UUID assignmentUuid, UUID target) {
        customizationRepository.findExclusion(assignmentUuid, target).ifPresent(existing -> {
            throw RuleConflict.of("customization.already_excluded");
        });
    }

    /**
     * A park may not drop a clause that another clause it keeps still cites, or
     * must sit directly above -- the lease would come out refused at generate, and
     * it is better to hear that now than when an office worker is in a hurry.
     */
    // droppingSection: the section this exclusion leaves out, or null when the exclusion is of one clause
    private void refuseIfNeeded(DocumentTemplate document, UUID assignmentUuid, Set<UUID> dropping, String kind,
                                UUID droppingSection) {
        List<PropertyDocumentCustomization> changes = customizationsOf(assignmentUuid);
        Set<UUID> sectionsOut = new HashSet<>();
        for (PropertyDocumentCustomization existing : changes) {
            if (existing.excludedSection() != null) {
                sectionsOut.add(existing.excludedSection());
            }
        }
        if (droppingSection != null) {
            sectionsOut.add(droppingSection);
        }
        // The park's own clauses that still print. A clause in a section the park leaves out does not print.
        List<PropertyDocumentCustomization> parkClauses = changes.stream()
                .filter(change -> change.action() == CustomizationAction.ADD_CLAUSE
                        && !sectionsOut.contains(change.section()))
                .toList();
        ClauseLinks.exclusionBlocker(document, dropping, leftOut(document, changes), kind, parkClauses)
                .ifPresent(problem -> {
                    throw RuleConflict.of(problem.code(), problem.args().toArray());
                });
    }

    // The template clauses a park leaves out: each clause excluded by itself, and every clause of an excluded section
    private static Set<UUID> leftOut(DocumentTemplate document, List<PropertyDocumentCustomization> changes) {
        Set<UUID> out = new HashSet<>();
        for (PropertyDocumentCustomization existing : changes) {
            if (existing.excludedClause() != null) {
                out.add(existing.excludedClause());
            }
            if (existing.excludedSection() != null
                    && document.sections().stream().anyMatch(s -> s.uuid().equals(existing.excludedSection()))) {
                out.addAll(ClauseLinks.clausesOf(document, existing.excludedSection()));
            }
        }
        return out;
    }

    private void recordInsert(PropertyDocumentCustomizationRow saved) {
        auditService.recordInsert(
                "property_document_customization", saved.uuid(), AuditMapper.toMap(saved));
    }

    // One place an assignment gets built for a screen. The template comes back
    // as a summary: a park's document list wants names and versions, not sixty
    // clause bodies per row.
    private PropertyDocumentAssignment hydrate(PropertyDocumentAssignmentRow row) {
        return row.toPropertyDocumentAssignment(summaryFor(row), customizationsOf(row.uuid()));
    }

    private DocumentTemplate summaryFor(PropertyDocumentAssignmentRow row) {
        return summaryOf(fullTemplateFor(row));
    }

    private DocumentTemplate fullTemplateFor(PropertyDocumentAssignmentRow row) {
        return documentTemplateService.findById(row.documentTemplate())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Assignment " + row.uuid() + " points at a document that no longer exists"));
    }

    private List<PropertyDocumentCustomization> customizationsOf(UUID assignmentUuid) {
        return customizationRepository.findByAssignment(assignmentUuid).stream()
                .map(PropertyDocumentCustomizationRow::toPropertyDocumentCustomization)
                .toList();
    }

    private Map<UUID, List<PropertyDocumentCustomization>> customizationsFor(List<UUID> assignmentIds) {
        return customizationRepository.findByAssignmentIds(assignmentIds).stream()
                .map(PropertyDocumentCustomizationRow::toPropertyDocumentCustomization)
                .collect(Collectors.groupingBy(PropertyDocumentCustomization::assignment));
    }

    private static DocumentTemplate summaryOf(DocumentTemplate template) {
        return new DocumentTemplate(
                template.uuid(),
                template.name(),
                template.agreementType(),
                template.instrumentType(),
                template.version(),
                template.note(),
                template.createdAt(),
                template.deletedAt(),
                List.of(),
                List.of());
    }

    // ---- every park, and bringing things back ---------------------------------

    /** Every live assignment of every park, each with its customizations and its document as a summary. */
    public List<PropertyDocumentAssignment> findAll() {
        List<PropertyDocumentAssignmentRow> rows = assignmentRepository.findAllLive();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<PropertyDocumentCustomization>> byAssignment =
                customizationsFor(rows.stream().map(PropertyDocumentAssignmentRow::uuid).toList());
        // one document is used by many parks: read each once
        Map<UUID, DocumentTemplate> summaries = new HashMap<>();
        return rows.stream()
                .map(row -> row.toPropertyDocumentAssignment(
                        summaries.computeIfAbsent(row.documentTemplate(), id -> summaryFor(row)),
                        byAssignment.getOrDefault(row.uuid(), List.of())))
                .toList();
    }

    /** The park a customization belongs to, live or removed, for the history's scope check. */
    public Optional<UUID> propertyOfCustomization(UUID customizationUuid) {
        return customizationRepository.findAnyById(customizationUuid)
                .flatMap(row -> assignmentRepository.findAnyById(row.assignment()))
                .map(PropertyDocumentAssignmentRow::property);
    }

    /** The park an assignment belongs to, live or ended, for the history's scope check. */
    public Optional<UUID> propertyOfAssignment(UUID assignmentUuid) {
        return assignmentRepository.findAnyById(assignmentUuid).map(PropertyDocumentAssignmentRow::property);
    }

    /**
     * The park uses the document again, with every change it had made to it (they were never removed).
     * A live assignment is answered as it is. Empty for an unknown one.
     * 409 assignment.document_deleted when the document was deleted since, and assignment.already_in_use when
     * the park took another document for the same kind of paper meanwhile.
     */
    @Transactional
    public Optional<PropertyDocumentAssignment> restoreAssignment(UUID uuid) {
        Optional<PropertyDocumentAssignmentRow> found = assignmentRepository.findAnyById(uuid);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PropertyDocumentAssignmentRow before = found.get();
        if (before.deletedAt() == null) {
            return Optional.of(hydrate(before));
        }
        documentTemplateService.lockForEdit(before.documentTemplate());
        DocumentTemplate template = documentTemplateService.findById(before.documentTemplate())
                .orElseThrow(() -> RuleConflict.of("assignment.document_deleted"));
        assignmentRepository
                .findByPropertyAndKind(before.property(), before.agreementType(), before.instrumentType())
                .ifPresent(existing -> {
                    String inUse = documentTemplateService.findById(existing.documentTemplate())
                            .map(DocumentTemplate::name)
                            .orElse(existing.documentTemplate().toString());
                    throw RuleConflict.of("assignment.already_in_use",
                            inUse, template.agreementType(), template.instrumentType());
                });
        Optional<PropertyDocumentAssignmentRow> after = assignmentRepository.restore(uuid);
        if (after.isEmpty()) {
            return findById(uuid);
        }
        AuditMapper.Diff diff = AuditMapper.diff(before, after.get());
        if (!diff.before().isEmpty()) {
            auditService.recordUpdate("property_document_assignment", uuid, diff.before(), diff.after());
        }
        return Optional.of(hydrate(after.get()));
    }

    /**
     * Brings back one change a park had made: an exclusion, or a clause of its own. Held to the rules the
     * change was first made under -- a required section or clause cannot be excluded, the same thing is not
     * excluded twice, nothing the park keeps may cite what it drops, and a park clause must still fit.
     * A live customization is answered as it is. Empty for an unknown one, or one whose park no longer uses
     * the document.
     */
    @Transactional
    public Optional<PropertyDocumentAssignment> restoreCustomization(UUID customizationUuid) {
        Optional<PropertyDocumentCustomizationRow> found = customizationRepository.findAnyById(customizationUuid);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PropertyDocumentCustomizationRow before = found.get();
        if (before.deletedAt() == null) {
            return findById(before.assignment());
        }
        return withAssignment(before.assignment(), (row, document) -> {
            PropertyDocumentCustomization change = before.toPropertyDocumentCustomization();
            if (change.excludedSection() != null) {
                DocumentSection section = sectionIn(document, change.excludedSection());
                if (!section.canBeExcludedByAProperty()) {
                    throw RuleConflict.of("section.is_required", section.name());
                }
                refuseDuplicateExclusion(before.assignment(), section.uuid());
                refuseIfNeeded(document, before.assignment(), ClauseLinks.clausesOf(document, section.uuid()), "section",
                        section.uuid());
            } else if (change.excludedClause() != null) {
                TemplateClause clause = clauseIn(document, change.excludedClause());
                if (clause.required()) {
                    throw RuleConflict.of("clause.is_required");
                }
                refuseDuplicateExclusion(before.assignment(), clause.uuid());
                refuseIfNeeded(document, before.assignment(), Set.of(clause.uuid()), "clause", null);
            } else {
                sectionIn(document, change.section());
                List<DomainProblem.Problem> problems = ClauseLinks.problemsWithParkClause(
                        document, change, leftOut(document, customizationsOf(before.assignment())));
                if (!problems.isEmpty()) {
                    throw InvalidRequest.withDetails("customization.not_saved", problems);
                }
            }
            customizationRepository.restore(customizationUuid).ifPresent(after -> {
                AuditMapper.Diff diff = AuditMapper.diff(before, after);
                if (!diff.before().isEmpty()) {
                    auditService.recordUpdate("property_document_customization", customizationUuid,
                            diff.before(), diff.after());
                }
            });
            return null;
        });
    }
}
