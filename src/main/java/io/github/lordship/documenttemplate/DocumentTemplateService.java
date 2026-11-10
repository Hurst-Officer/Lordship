package io.github.lordship.documenttemplate;

import io.github.lordship.audit.ActingAgent;
import io.github.lordship.audit.AuditContext;
import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.documenttemplate.internal.*;
import io.github.lordship.shared.ClauseBodyRules;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.DocumentToken;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.RuleConflict;
import io.github.lordship.shared.StyleTarget;
import io.github.lordship.shared.DomainProblem.Problem;
import io.github.lordship.shared.InstrumentType;
import io.github.lordship.tenancyterms.TenancyChargeTerm;
import io.github.lordship.tenancyterms.TenancyChargeTermService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The global document pool. Editing anything here reaches every property the
 * document is assigned to and every future render; documents already generated
 * are untouched, because their wording was snapshotted onto the instrument.
 *
 * <p>Two rules do the real work, and both live at save time rather than at
 * generate time -- a legal document is the wrong place to discover a typo:
 * a clause body may only name tokens {@link DocumentToken} can actually
 * resolve, and a clause may only branch on a token whose format is ENUM, using
 * values that column actually permits.
 */
@Service
public class DocumentTemplateService {

    private final DocumentTemplateRepository documentTemplateRepository;
    private final DocumentSectionRepository documentSectionRepository;
    private final TemplateClauseRepository templateClauseRepository;
    private final DocumentStyleRepository documentStyleRepository;
    private final PropertyDocumentCustomizationRepository customizationRepository;
    private final DocumentCopyRepository documentCopyRepository;
    private final TenancyChargeTermService tenancyChargeTermService;
    private final AuditService auditService;
    private final AuditContext auditContext;

    public DocumentTemplateService(DocumentTemplateRepository documentTemplateRepository,
                                   DocumentSectionRepository documentSectionRepository,
                                   TemplateClauseRepository templateClauseRepository,
                                   DocumentStyleRepository documentStyleRepository,
                                   PropertyDocumentCustomizationRepository customizationRepository,
                                   DocumentCopyRepository documentCopyRepository,
                                   TenancyChargeTermService tenancyChargeTermService,
                                   AuditService auditService,
                                   AuditContext auditContext) {
        this.documentTemplateRepository = documentTemplateRepository;
        this.documentSectionRepository = documentSectionRepository;
        this.templateClauseRepository = templateClauseRepository;
        this.documentStyleRepository = documentStyleRepository;
        this.customizationRepository = customizationRepository;
        this.documentCopyRepository = documentCopyRepository;
        this.tenancyChargeTermService = tenancyChargeTermService;
        this.auditService = auditService;
        this.auditContext = auditContext;
    }

    // ---- templates -----------------------------------------------------------

    // The list view. No children on purpose -- sixty clause bodies per row is
    // not what an admin picking a document from the pool needs.
    public List<DocumentTemplate> findAll(AgreementType agreementType, InstrumentType instrumentType) {
        return documentTemplateRepository.findAll(agreementType, instrumentType).stream()
                .map(DocumentTemplateRow::toDocumentTemplate)
                .toList();
    }

    public Optional<DocumentTemplate> findById(UUID uuid) {
        return documentTemplateRepository.findById(uuid).map(this::hydrate);
    }

    /**
     * The document as it would come out for a set of method values typed by
     * hand. Works on a document that has never been assigned anywhere, which
     * matters because an author usually finishes writing before deciding which
     * parks get it.
     */
    public Optional<DocumentTemplate.Preview> preview(UUID uuid, Map<String, String> methodValues) {
        validateMethodValues(methodValues);
        return findById(uuid).map(template -> template.preview(methodValues));
    }

    /**
     * The same preview against a real deal. Reads the methods off a charge term
     * rather than trusting a hand-typed map, so the combination previewed is
     * one that actually exists.
     */
    public Optional<DocumentTemplate.Preview> previewForChargeTerm(UUID uuid, UUID chargeTermUuid) {
        TenancyChargeTerm term = tenancyChargeTermService.findById(chargeTermUuid)
                .orElseThrow(() -> new EntityNotFoundException("Charge term not found: " + chargeTermUuid));

        return findById(uuid).map(template -> template.preview(methodValuesOf(term)));
    }

    // The subset of a charge term a clause may branch on. Anything not here is
    // a figure, and figures do not decide whether a paragraph prints.
    private static Map<String, String> methodValuesOf(TenancyChargeTerm term) {
        Map<String, String> values = new LinkedHashMap<>();
        put(values, DocumentToken.LATE_FEE_METHOD, term.lateFeeMethod());
        put(values, DocumentToken.NSF_FEE_METHOD, term.nsfFeeMethod());
        put(values, DocumentToken.RULE_VIOLATION_FEE_METHOD, term.ruleViolationFeeMethod());
        put(values, DocumentToken.WATER_METHOD, term.waterMethod());
        put(values, DocumentToken.POWER_METHOD, term.powerMethod());
        put(values, DocumentToken.SEWER_METHOD, term.sewerMethod());
        put(values, DocumentToken.TRASH_METHOD, term.trashMethod());
        put(values, DocumentToken.SECURITY_DEPOSIT_METHOD, term.securityDepositMethod());
        put(values, DocumentToken.AGREEMENT_TYPE, term.agreementType());
        return values;
    }

    private static void put(Map<String, String> values, DocumentToken token, Enum<?> value) {
        if (value != null) {
            values.put(token.token(), value.name());
        }
    }

    // A typo here would come back as a document quietly missing clauses, which
    // is exactly the failure preview exists to catch.
    private static void validateMethodValues(Map<String, String> methodValues) {
        methodValues.forEach((field, value) -> {
            DocumentToken token = DocumentToken.of(field).orElseThrow(() -> ClauseBodyRules.unknownToken(field));

            if (!token.canCondition()) {
                throw InvalidRequest.of("token.not_a_method", ClauseBodyRules.placeholder(field), token.format());
            }
            // Same trap as appliesTo: Set.of(...) throws on a null probe. Leave
            // a method out of the map to say "unset"; naming it with no value
            // is a mistake worth reporting.
            if (value == null) {
                throw InvalidRequest.of("token.value_omitted", ClauseBodyRules.placeholder(field));
            }
            if (!token.allowedValues().contains(value)) {
                throw InvalidRequest.of("token.value_not_allowed",
                        ClauseBodyRules.placeholder(field), value, ClauseBodyRules.sorted(token.allowedValues()));
            }
        });
    }

    @Transactional
    public DocumentTemplate createTemplate(String name,
                                           AgreementType agreementType,
                                           InstrumentType instrumentType) {
        return createTemplate(name, agreementType, instrumentType, null);
    }

    /**
     * A new document, empty or starting from another document's wording (copyFrom): its styles, its
     * sections and their clauses, with every link inside pointed at the copies (DocumentCopyRepository).
     * Parks are not copied; each moves to the new document by being assigned it.
     * 400 template.copy_from_unknown when copyFrom names no live document.
     */
    @Transactional
    public DocumentTemplate createTemplate(String name,
                                           AgreementType agreementType,
                                           InstrumentType instrumentType,
                                           UUID copyFrom) {
        if (copyFrom != null && documentTemplateRepository.findById(copyFrom).isEmpty()) {
            throw InvalidRequest.onField("copyFromId", "template.copy_from_unknown");
        }
        UUID agent = ActingAgent.resolve(auditContext);
        DocumentTemplateRow saved = documentTemplateRepository.save(name, agreementType, instrumentType, agent);
        auditService.recordInsert("document_template", saved.uuid(), AuditMapper.toMap(saved));

        if (copyFrom != null) {
            documentCopyRepository.copyWording(copyFrom, saved.uuid(), agent);
            // each copied row is an insert in the audit log, like one added by hand
            DocumentTemplate copy = hydrate(saved);
            copy.styles().forEach(style -> documentStyleRepository.findById(style.uuid())
                    .ifPresent(row -> auditService.recordInsert("document_style", row.uuid(), AuditMapper.toMap(row))));
            for (DocumentSection section : copy.sections()) {
                documentSectionRepository.findById(section.uuid())
                        .ifPresent(row -> auditService.recordInsert("document_section", row.uuid(), AuditMapper.toMap(row)));
                templateClauseRepository.findBySection(section.uuid())
                        .forEach(row -> auditService.recordInsert("template_clause", row.uuid(), AuditMapper.toMap(row)));
            }
            return copy;
        }
        return hydrate(saved);
    }

    // Name and note only, and no version bump: neither changes a word of what
    // gets printed.
    @Transactional
    public Optional<DocumentTemplate> patchTemplate(UUID uuid, Map<String, Object> changes) {
        Optional<DocumentTemplateRow> beforeOpt = documentTemplateRepository.findById(uuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        DocumentTemplateRow before = beforeOpt.get();

        changes = withName(changes, "template.name_blank");
        Optional<DocumentTemplateRow> afterOpt = documentTemplateRepository.patch(uuid, changes);
        if (afterOpt.isEmpty()) {
            return Optional.empty();
        }

        recordUpdate("document_template", uuid, before, afterOpt.get());
        return Optional.of(hydrate(afterOpt.get()));
    }

    @Transactional
    public boolean deleteTemplate(UUID uuid) {
        return documentTemplateRepository.findById(uuid).map(existing -> {
            if (documentTemplateRepository.isAssignedToAnyProperty(uuid)) {
                throw RuleConflict.of("template.still_assigned", existing.name());
            }
            if (!documentTemplateRepository.softDelete(uuid)) {
                return false;
            }
            auditService.recordDelete("document_template", uuid, AuditMapper.toMap(existing));
            return true;
        }).orElse(false);
    }

    /**
     * The changes with the name trimmed, when they rename. The column is NOT NULL, so a cleared name would come
     * back as the database's bare "conflict"; the worker gets the reason instead (400 on "name").
     */
    private static Map<String, Object> withName(Map<String, Object> changes, String blankCode) {
        if (!changes.containsKey("name")) {
            return changes;
        }
        Object raw = changes.get("name");
        String name = raw == null ? "" : String.valueOf(raw).trim();
        if (name.isEmpty()) {
            throw InvalidRequest.onField("name", blankCode);
        }
        Map<String, Object> trimmed = new HashMap<>(changes);
        trimmed.put("name", name);
        return trimmed;
    }

    // ---- sections ------------------------------------------------------------

    @Transactional
    public Optional<DocumentTemplate> createSection(UUID templateId, String name) {
        Optional<DocumentTemplateRow> templateOpt = documentTemplateRepository.findById(templateId);
        if (templateOpt.isEmpty()) {
            return Optional.empty();
        }

        DocumentSectionRow saved = documentSectionRepository.save(
                templateId, name, ActingAgent.resolve(auditContext));

        auditService.recordInsert("document_section", saved.uuid(), AuditMapper.toMap(saved));
        return Optional.of(reloadAndBump(templateId));
    }

    @Transactional
    public Optional<DocumentTemplate> patchSection(UUID sectionUuid, Map<String, Object> changes) {
        Optional<DocumentSectionRow> beforeOpt = documentSectionRepository.findById(sectionUuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        DocumentSectionRow before = beforeOpt.get();

        Map<String, Object> effective = new HashMap<>(withName(changes, "section.name_blank"));
        asUuids(effective, "style", "title_style");
        asFormats(effective, "number_formats", "cite_formats");

        DocumentTemplate beforeDoc = hydrateById(before.template());
        Optional<DocumentSectionRow> afterOpt = documentSectionRepository.patch(sectionUuid, effective);
        if (afterOpt.isEmpty()) {
            return Optional.empty();
        }

        refuseNew("section.not_saved",
                ClauseLinks.problemsWithSection(beforeDoc, sectionUuid),
                ClauseLinks.problemsWithSection(hydrateById(before.template()), sectionUuid));

        Set<String> changed = recordUpdate("document_section", sectionUuid, before, afterOpt.get());
        return Optional.of(reload(before.template(), affectsPrintedOutput(changed)));
    }

    // A required section is there to satisfy the statute in statute_ref.
    // Removing it globally is a different act from one park excluding it, and
    // this is not the screen for it.
    @Transactional
    public boolean deleteSection(UUID sectionUuid) {
        return documentSectionRepository.findById(sectionUuid).map(existing -> {
            if (existing.required()) {
                throw existing.statuteRef() == null
                        ? RuleConflict.of("section.is_required", existing.name())
                        : RuleConflict.of("section.is_required_by_statute",
                        existing.name(), existing.statuteRef());
            }
            // A clause elsewhere in the document that cites one of this section's clauses, or must sit
            // directly above one, would come out broken on every lease.
            documentTemplateRepository.findById(existing.template()).map(this::hydrate).ifPresent(doc -> {
                ClauseLinks.exclusionBlocker(doc, ClauseLinks.clausesOf(doc, sectionUuid), Set.of(), "section")
                        .ifPresent(problem -> {
                            throw RuleConflict.of(problem.code(), problem.args().toArray());
                        });
                // A park's own rules would quietly drop off that park's paper, or cite a clause no longer there
                for (UUID clause : ClauseLinks.clausesOf(doc, sectionUuid)) {
                    if (customizationRepository.existsLeaningOn(clause)) {
                        throw RuleConflict.of("section.used_by_property");
                    }
                }
            });
            if (customizationRepository.existsInSection(sectionUuid)) {
                throw RuleConflict.of("section.has_park_clauses");
            }
            if (!documentSectionRepository.softDelete(sectionUuid)) {
                return false;
            }
            auditService.recordDelete("document_section", sectionUuid, AuditMapper.toMap(existing));
            documentTemplateRepository.bumpVersion(existing.template());
            return true;
        }).orElse(false);
    }

    // ---- clauses -------------------------------------------------------------

    // Nothing but a section: every other column is nullable, so the editor gets
    // an empty clause to type into rather than a form to fill in first.
    @Transactional
    public Optional<DocumentTemplate> createClause(UUID sectionId) {
        Optional<DocumentSectionRow> sectionOpt = documentSectionRepository.findById(sectionId);
        if (sectionOpt.isEmpty()) {
            return Optional.empty();
        }

        TemplateClauseRow saved = templateClauseRepository.save(
                sectionId, ActingAgent.resolve(auditContext));

        auditService.recordInsert("template_clause", saved.uuid(), AuditMapper.toMap(saved));
        return Optional.of(reloadAndBump(sectionOpt.get().template()));
    }

    @Transactional
    public Optional<DocumentTemplate> patchClause(UUID clauseUuid, Map<String, Object> changes) {
        Optional<TemplateClauseRow> beforeOpt = templateClauseRepository.findById(clauseUuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        TemplateClauseRow before = beforeOpt.get();

        Map<String, Object> effective = ClauseBodyRules.withConditionClearing(changes);

        ClauseBodyRules.validateBody(effective);
        ClauseBodyRules.validateCondition(
                before.conditionField(), before.conditionValues(), effective);
        asUuids(effective, "parent", "variant_of", "requires_next", "style");

        UUID templateId = documentSectionRepository.findById(before.section())
                .map(DocumentSectionRow::template)
                .orElseThrow(() -> new IllegalStateException(
                        "Clause " + clauseUuid + " points at a section that no longer exists"));

        DocumentTemplate beforeDoc = hydrateById(templateId);
        Optional<TemplateClauseRow> afterOpt = templateClauseRepository.patch(clauseUuid, effective);
        if (afterOpt.isEmpty()) {
            return Optional.empty();
        }

        // Checked against the document as it now stands, inside the transaction:
        // a refusal rolls the write back. Only problems this edit introduced are
        // refused, so a clause seeded with a broken link can still have its note fixed.
        DocumentTemplate afterDoc = hydrateById(templateId);
        List<Problem> was = new ArrayList<>(ClauseLinks.problemsWithTree(beforeDoc, clauseUuid));
        was.addAll(ClauseLinks.brokenPairsIn(beforeDoc, before.section()));
        List<Problem> now = new ArrayList<>(ClauseLinks.problemsWithTree(afterDoc, clauseUuid));
        now.addAll(ClauseLinks.brokenPairsIn(afterDoc, before.section()));
        refuseNew("clause.not_saved", was, now);

        Set<String> changed = recordUpdate("template_clause", clauseUuid, before, afterOpt.get());
        return Optional.of(reload(templateId, affectsPrintedOutput(changed)));
    }

    @Transactional
    public boolean deleteClause(UUID clauseUuid) {
        return templateClauseRepository.findById(clauseUuid).map(existing -> {
            if (existing.required()) {
                throw existing.statuteRef() == null
                        ? RuleConflict.of("clause.is_required")
                        : RuleConflict.of("clause.is_required_by_statute", existing.statuteRef());
            }
            documentSectionRepository.findById(existing.section())
                    .map(section -> hydrateById(section.template()))
                    .flatMap(doc -> ClauseLinks.deleteBlocker(doc, clauseUuid))
                    .ifPresent(problem -> {
                        throw RuleConflict.of(problem.code(), problem.args().toArray());
                    });
            if (customizationRepository.existsLeaningOn(clauseUuid)) {
                throw RuleConflict.of("clause.used_by_property");
            }
            if (!templateClauseRepository.softDelete(clauseUuid)) {
                return false;
            }
            auditService.recordDelete("template_clause", clauseUuid, AuditMapper.toMap(existing));
            documentSectionRepository.findById(existing.section())
                    .ifPresent(section -> documentTemplateRepository.bumpVersion(section.template()));
            return true;
        }).orElse(false);
    }

    // ---- styles --------------------------------------------------------------

    // A name and nothing else; the css arrives by PATCH, like a clause body.
    @Transactional
    public Optional<DocumentTemplate> createStyle(UUID templateId, String name) {
        if (documentTemplateRepository.findById(templateId).isEmpty()) {
            return Optional.empty();
        }
        DocumentStyleRow saved = documentStyleRepository.save(templateId, name, ActingAgent.resolve(auditContext));
        auditService.recordInsert("document_style", saved.uuid(), AuditMapper.toMap(saved));
        return Optional.of(reload(templateId, false));
    }

    /**
     * Name, css, target, note. No version bump for any of them: the version says
     * which wording an instrument was cut from, and a style changes no wording.
     */
    @Transactional
    public Optional<DocumentTemplate> patchStyle(UUID styleUuid, Map<String, Object> changes) {
        Optional<DocumentStyleRow> beforeOpt = documentStyleRepository.findById(styleUuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        DocumentStyleRow before = beforeOpt.get();

        changes = withName(changes, "style.name_blank");
        if (changes.containsKey("target") && changes.get("target") != null) {
            String target = String.valueOf(changes.get("target"));
            if (StyleTarget.of(target).isEmpty()) {
                throw InvalidRequest.onField("target", "style.target_unknown", target,
                        Arrays.stream(StyleTarget.values()).map(Enum::name).toList());
            }
            if (before.target() == null && documentStyleRepository.isInUse(styleUuid)) {
                throw RuleConflict.of("style.in_use", before.name());
            }
        }
        if (changes.containsKey("css") && changes.get("css") == null) {
            changes = new HashMap<>(changes);
            changes.put("css", "");
        }

        Optional<DocumentStyleRow> afterOpt = documentStyleRepository.patch(styleUuid, changes);
        if (afterOpt.isEmpty()) {
            return Optional.empty();
        }
        DocumentStyle after = afterOpt.get().toDocumentStyle();

        List<Problem> problems = ClauseLinks.problemsWithStyle(after);
        if (!problems.isEmpty()) {
            throw InvalidRequest.withDetails("style.not_saved", problems);
        }
        ClauseLinks.duplicateTarget(hydrateById(before.template()), after).ifPresent(problem -> {
            throw RuleConflict.of(problem.code(), problem.args().toArray());
        });

        recordUpdate("document_style", styleUuid, before, afterOpt.get());
        return Optional.of(reload(before.template(), false));
    }

    @Transactional
    public boolean deleteStyle(UUID styleUuid) {
        return documentStyleRepository.findById(styleUuid).map(existing -> {
            if (documentStyleRepository.isInUse(styleUuid)) {
                throw RuleConflict.of("style.in_use", existing.name());
            }
            if (!documentStyleRepository.softDelete(styleUuid)) {
                return false;
            }
            auditService.recordDelete("document_style", styleUuid, AuditMapper.toMap(existing));
            return true;
        }).orElse(false);
    }

    // ---- validation ----------------------------------------------------------

    /** Refuses whatever {@code after} has that {@code before} did not. */
    private static void refuseNew(String code, List<Problem> before, List<Problem> after) {
        List<Problem> introduced = after.stream().filter(problem -> !before.contains(problem)).distinct().toList();
        if (!introduced.isEmpty()) {
            throw InvalidRequest.withDetails(code, introduced);
        }
    }

    /** JSON carries a uuid as a string; the driver wants a UUID. Null clears the link. */
    private static void asUuids(Map<String, Object> changes, String... columns) {
        for (String column : columns) {
            if (!changes.containsKey(column) || changes.get(column) == null || changes.get(column) instanceof UUID) {
                continue;
            }
            try {
                changes.put(column, UUID.fromString(String.valueOf(changes.get(column))));
            } catch (IllegalArgumentException e) {
                throw InvalidRequest.onField(column, "request.not_a_uuid", changes.get(column));
            }
        }
    }

    /** One pattern per level, one to three levels. The columns are NOT NULL, so null is refused too. */
    private static void asFormats(Map<String, Object> changes, String... columns) {
        for (String column : columns) {
            if (!changes.containsKey(column)) {
                continue;
            }
            if (!(changes.get(column) instanceof List<?> list) || list.isEmpty()
                    || list.size() > ClauseLinks.MAX_DEPTH || list.stream().anyMatch(item -> !(item instanceof String))) {
                throw InvalidRequest.onField(column, "section.formats_count", ClauseLinks.MAX_DEPTH);
            }
            changes.put(column, list.stream().map(String::valueOf).toArray(String[]::new));
        }
    }

    // ---- internals -----------------------------------------------------------

    /**
     * One place a DocumentTemplate gets built. Every read and every write
     * returns through here, so the editor can never be handed a clause list
     * that is merely whatever the caller happened to have in hand. Two queries
     * for the children, not one per section.
     */
    private DocumentTemplate hydrate(DocumentTemplateRow row) {
        List<DocumentStyle> styles = documentStyleRepository.findByTemplate(row.uuid()).stream()
                .map(DocumentStyleRow::toDocumentStyle)
                .toList();
        List<DocumentSectionRow> sectionRows = documentSectionRepository.findByTemplate(row.uuid());
        if (sectionRows.isEmpty()) {
            return row.toDocumentTemplate(List.of(), styles);
        }

        List<UUID> sectionIds = sectionRows.stream().map(DocumentSectionRow::uuid).toList();
        Map<UUID, List<TemplateClauseRow>> clausesBySection =
                templateClauseRepository.findBySectionIds(sectionIds).stream()
                        .collect(Collectors.groupingBy(TemplateClauseRow::section));

        List<DocumentSection> sections = sectionRows.stream()
                .map(section -> section.toDocumentSection(
                        clausesBySection.getOrDefault(section.uuid(), List.of())))
                .toList();

        return row.toDocumentTemplate(sections, styles);
    }

    private DocumentTemplate hydrateById(UUID templateId) {
        return documentTemplateRepository.findById(templateId)
                .map(this::hydrate)
                .orElseThrow(() -> new IllegalStateException("Template " + templateId + " disappeared mid-edit"));
    }

    /**
     * The template as it now stands, which is what the editor needs back after
     * any edit to a section or a clause.
     *
     * <p>{@code bump} carries the one decision worth making here: a change to
     * the wording moves the version an instrument freezes, and a change to a
     * note does not. A no-op patch does not move it either.
     */
    private DocumentTemplate reload(UUID templateId, boolean bump) {
        Optional<DocumentTemplateRow> row = bump
                ? documentTemplateRepository.bumpVersion(templateId)
                : documentTemplateRepository.findById(templateId);

        return row.map(this::hydrate)
                .orElseThrow(() -> new IllegalStateException(
                        "Template " + templateId + " disappeared mid-edit"));
    }

    // Structural edits always move the version: a clause appearing or
    // disappearing changes the document whatever it says.
    private DocumentTemplate reloadAndBump(UUID templateId) {
        return reload(templateId, true);
    }

    /**
     * Only record a change when the value actually changed: the log is a record
     * of state changes, not of actions attempted.
     *
     * <p>Returns the fields that moved, which is also what decides whether the
     * document's version has to move. Empty means the patch was a no-op.
     */
    private <T extends Record> Set<String> recordUpdate(String table, UUID uuid, T before, T after) {
        AuditMapper.Diff diff = AuditMapper.diff(before, after);
        if (diff.before().isEmpty()) {
            return Set.of();
        }
        auditService.recordUpdate(table, uuid, diff.before(), diff.after());
        return diff.before().keySet();
    }

    /**
     * Fields that change no wording. An instrument freezes template_version to
     * say which wording it was cut from, so the number should move when the
     * wording does -- a number format does, since it prints "9A" -- and not when
     * an author leaves a reminder, restyles, or glues two clauses together.
     *
     * <p>Record component names, not column names -- {@link AuditMapper} works
     * off the accessor names.
     */
    private static final Set<String> NON_PRINTING_FIELDS = Set.of("note", "style", "titleStyle", "requiresNext");

    private static boolean affectsPrintedOutput(Set<String> changedFields) {
        return changedFields.stream().anyMatch(field -> !NON_PRINTING_FIELDS.contains(field));
    }

    // ---- deleted documents, sections, clauses and styles ---------------------
    // Everything here is only marked deleted, so it can come back. A restore of something live is answered
    // as it is (a coworker restored it a moment earlier, or an undo whose answer was lost is sent again),
    // and is written to the audit log as an update of deletedAt back to empty.

    /** Deleted documents, the most recently deleted first. */
    public List<DocumentTemplate> findDeleted() {
        return documentTemplateRepository.findDeleted().stream()
                .map(DocumentTemplateRow::toDocumentTemplate)
                .toList();
    }

    /** Empty for an unknown document. Its sections, clauses and styles were never touched, so they come back with it. */
    @Transactional
    public Optional<DocumentTemplate> restoreTemplate(UUID uuid) {
        Optional<DocumentTemplateRow> found = documentTemplateRepository.findAnyById(uuid);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        DocumentTemplateRow before = found.get();
        if (before.deletedAt() != null) {
            documentTemplateRepository.restore(uuid).ifPresent(after -> recordUpdate("document_template", uuid, before, after));
        }
        return findById(uuid);
    }

    /** One document's deleted sections, the most recently deleted first. Empty for an unknown or deleted document. */
    public List<DocumentSection> findDeletedSections(UUID templateId) {
        if (documentTemplateRepository.findById(templateId).isEmpty()) {
            return List.of();
        }
        return documentSectionRepository.findDeletedByTemplate(templateId).stream()
                .map(DocumentSectionRow::toDocumentSection)
                .toList();
    }

    /**
     * The document with the section back in it, at its old place and with its clauses. Empty for an unknown
     * section. 409 section.document_deleted when its document is deleted (restore that first), and 400
     * section.not_saved when one of its clauses would come back citing, or kept with, a clause deleted since.
     */
    @Transactional
    public Optional<DocumentTemplate> restoreSection(UUID sectionUuid) {
        Optional<DocumentSectionRow> found = documentSectionRepository.findAnyById(sectionUuid);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        DocumentSectionRow before = found.get();
        if (documentTemplateRepository.findById(before.template()).isEmpty()) {
            throw RuleConflict.of("section.document_deleted", before.name());
        }
        if (before.deletedAt() == null) {
            return Optional.of(reload(before.template(), false));
        }
        Optional<DocumentSectionRow> after = documentSectionRepository.restore(sectionUuid);
        if (after.isEmpty()) {
            return Optional.of(reload(before.template(), false));
        }
        // Its clauses come back as they were, so one of them may now cite or sit above a clause deleted since
        DocumentTemplate afterDoc = hydrateById(before.template());
        List<Problem> now = new ArrayList<>();
        for (UUID clause : ClauseLinks.clausesOf(afterDoc, sectionUuid)) {
            now.addAll(ClauseLinks.problemsWith(afterDoc, clause));
        }
        now.addAll(ClauseLinks.brokenPairsIn(afterDoc, sectionUuid));
        refuseNew("section.not_saved", List.of(), now);
        recordUpdate("document_section", sectionUuid, before, after.get());
        return Optional.of(reloadAndBump(before.template()));
    }

    /** One section's deleted clauses, the most recently deleted first. Empty for an unknown or deleted section. */
    public List<TemplateClause> findDeletedClauses(UUID sectionUuid) {
        if (documentSectionRepository.findById(sectionUuid).isEmpty()) {
            return List.of();
        }
        return templateClauseRepository.findDeletedBySection(sectionUuid).stream()
                .map(TemplateClauseRow::toTemplateClause)
                .toList();
    }

    /**
     * The document with the clause back in it, at its old place and under its old parent. Empty for an
     * unknown clause. 409 clause.section_deleted when its section is deleted, and 400 clause.not_saved when it
     * would come back broken (its parent, its variant or the clause it sits above was deleted since).
     */
    @Transactional
    public Optional<DocumentTemplate> restoreClause(UUID clauseUuid) {
        Optional<TemplateClauseRow> found = templateClauseRepository.findAnyById(clauseUuid);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        TemplateClauseRow before = found.get();
        Optional<DocumentSectionRow> section = documentSectionRepository.findById(before.section());
        if (section.isEmpty()) {
            throw RuleConflict.of("clause.section_deleted");
        }
        UUID templateId = section.get().template();
        if (documentTemplateRepository.findById(templateId).isEmpty()) {
            throw RuleConflict.of("clause.section_deleted");
        }
        if (before.deletedAt() == null) {
            return Optional.of(reload(templateId, false));
        }
        DocumentTemplate beforeDoc = hydrateById(templateId);
        Optional<TemplateClauseRow> after = templateClauseRepository.restore(clauseUuid);
        if (after.isEmpty()) {
            return Optional.of(reload(templateId, false));
        }
        DocumentTemplate afterDoc = hydrateById(templateId);
        List<Problem> now = new ArrayList<>(ClauseLinks.problemsWith(afterDoc, clauseUuid));
        now.addAll(ClauseLinks.brokenPairsIn(afterDoc, before.section()));
        refuseNew("clause.not_saved", ClauseLinks.brokenPairsIn(beforeDoc, before.section()), now);

        recordUpdate("template_clause", clauseUuid, before, after.get());
        return Optional.of(reloadAndBump(templateId));
    }

    /** One document's deleted styles, the most recently deleted first. */
    public List<DocumentStyle> findDeletedStyles(UUID templateId) {
        if (documentTemplateRepository.findById(templateId).isEmpty()) {
            return List.of();
        }
        return documentStyleRepository.findDeletedByTemplate(templateId).stream()
                .map(DocumentStyleRow::toDocumentStyle)
                .toList();
    }

    /**
     * The document with the style back. Empty for an unknown style. 409 style.document_deleted when its document
     * is deleted, and style.target_taken when another style restyles the same part of the page since.
     */
    @Transactional
    public Optional<DocumentTemplate> restoreStyle(UUID styleUuid) {
        Optional<DocumentStyleRow> found = documentStyleRepository.findAnyById(styleUuid);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        DocumentStyleRow before = found.get();
        if (documentTemplateRepository.findById(before.template()).isEmpty()) {
            throw RuleConflict.of("style.document_deleted", before.name());
        }
        if (before.deletedAt() == null) {
            return Optional.of(reload(before.template(), false));
        }
        ClauseLinks.duplicateTarget(hydrateById(before.template()), before.toDocumentStyle()).ifPresent(problem -> {
            throw RuleConflict.of(problem.code(), problem.args().toArray());
        });
        documentStyleRepository.restore(styleUuid)
                .ifPresent(after -> recordUpdate("document_style", styleUuid, before, after));
        return Optional.of(reload(before.template(), false));
    }

    // ---- moving --------------------------------------------------------------
    // Ordinals are sparse decimals, so a move puts one number between two neighbours and nothing else moves.
    // When there is no room left between them (four decimals), the section or the document is spread out
    // to 10, 20, 30... first (renumber), keeping every row's order.

    // Less than this between two neighbours is no room: a midpoint needs a fifth decimal
    private static final BigDecimal NO_ROOM = new BigDecimal("0.0002");

    // Print order, as the repositories read it: by ordinal, and by uuid on a tie. The uuid is compared as text,
    // which is Postgres's order (UUID.compareTo compares signed numbers, which is not)
    private static final Comparator<DocumentSectionRow> SECTION_ORDER =
            Comparator.comparing(DocumentSectionRow::ordinal).thenComparing(row -> row.uuid().toString());
    private static final Comparator<TemplateClauseRow> CLAUSE_ORDER =
            Comparator.comparing(TemplateClauseRow::ordinal).thenComparing(row -> row.uuid().toString());

    /**
     * Puts a section directly after another section of its document, or first when afterId is null.
     * Empty for an unknown section. 400 section.move_after_unknown when afterId is not another live section
     * of the same document. Moving it where it already is changes nothing.
     */
    @Transactional
    public Optional<DocumentTemplate> moveSection(UUID sectionUuid, UUID afterId) {
        Optional<DocumentSectionRow> found = documentSectionRepository.findById(sectionUuid);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        DocumentSectionRow before = found.get();
        UUID templateId = before.template();

        BigDecimal ordinal = sectionPlace(templateId, sectionUuid, afterId);
        if (ordinal == null) {
            documentSectionRepository.renumber(templateId);
            ordinal = sectionPlace(templateId, sectionUuid, afterId);
            if (ordinal == null) {
                throw RuleConflict.of("section.move_no_room");
            }
        }
        DocumentSectionRow moving = documentSectionRepository.findById(sectionUuid).orElse(before);
        if (ordinal.compareTo(moving.ordinal()) == 0) {
            return Optional.of(reload(templateId, false));
        }
        Optional<DocumentSectionRow> after = documentSectionRepository.patch(sectionUuid, Map.of("ordinal", ordinal));
        if (after.isEmpty()) {
            return Optional.empty();
        }
        recordUpdate("document_section", sectionUuid, before, after.get());
        return Optional.of(reloadAndBump(templateId));
    }

    // The ordinal that puts the section after afterId, its own ordinal when it is already there,
    // or null when there is no room between the two neighbours
    private BigDecimal sectionPlace(UUID templateId, UUID sectionUuid, UUID afterId) {
        List<DocumentSectionRow> all = documentSectionRepository.findByTemplate(templateId);
        DocumentSectionRow self = all.stream().filter(s -> s.uuid().equals(sectionUuid)).findFirst().orElseThrow();
        List<BigDecimal> others = all.stream()
                .filter(s -> !s.uuid().equals(sectionUuid))
                .sorted(SECTION_ORDER)
                .map(DocumentSectionRow::ordinal)
                .toList();
        List<UUID> otherIds = all.stream()
                .filter(s -> !s.uuid().equals(sectionUuid))
                .sorted(SECTION_ORDER)
                .map(DocumentSectionRow::uuid)
                .toList();
        int at = afterId == null ? -1 : otherIds.indexOf(afterId);
        if (afterId != null && at < 0) {
            throw InvalidRequest.onField("afterId", "section.move_after_unknown");
        }
        return between(at < 0 ? null : others.get(at), at + 1 < others.size() ? others.get(at + 1) : null, self.ordinal());
    }

    /**
     * Puts a clause under parentId (null: the top level of its section), directly after its sibling afterId
     * (null: first under that parent). Held to the same rules as a PATCH of its parent: no cycles, at most
     * three levels, a variant under the same parent as its clause, and a pair kept together (400 clause.not_saved
     * names what broke). Empty for an unknown clause. 400 clause.move_after_unknown when afterId is not another
     * clause under that parent.
     */
    @Transactional
    public Optional<DocumentTemplate> moveClause(UUID clauseUuid, UUID parentId, UUID afterId) {
        Optional<TemplateClauseRow> found = templateClauseRepository.findById(clauseUuid);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        TemplateClauseRow before = found.get();
        UUID sectionId = before.section();
        UUID templateId = documentSectionRepository.findById(sectionId)
                .map(DocumentSectionRow::template)
                .orElseThrow(() -> new IllegalStateException(
                        "Clause " + clauseUuid + " points at a section that no longer exists"));

        BigDecimal ordinal = clausePlace(sectionId, clauseUuid, parentId, afterId);
        if (ordinal == null) {
            templateClauseRepository.renumberSection(sectionId);
            ordinal = clausePlace(sectionId, clauseUuid, parentId, afterId);
            if (ordinal == null) {
                throw RuleConflict.of("clause.move_no_room");
            }
        }
        TemplateClauseRow moving = templateClauseRepository.findById(clauseUuid).orElse(before);
        if (ordinal.compareTo(moving.ordinal()) == 0 && Objects.equals(parentId, moving.parent())) {
            return Optional.of(reload(templateId, false));
        }

        DocumentTemplate beforeDoc = hydrateById(templateId);
        Map<String, Object> changes = new HashMap<>();
        changes.put("parent", parentId);
        changes.put("ordinal", ordinal);
        Optional<TemplateClauseRow> after = templateClauseRepository.patch(clauseUuid, changes);
        if (after.isEmpty()) {
            return Optional.empty();
        }
        DocumentTemplate afterDoc = hydrateById(templateId);
        List<Problem> was = new ArrayList<>(ClauseLinks.problemsWithTree(beforeDoc, clauseUuid));
        was.addAll(ClauseLinks.brokenPairsIn(beforeDoc, sectionId));
        List<Problem> now = new ArrayList<>(ClauseLinks.problemsWithTree(afterDoc, clauseUuid));
        now.addAll(ClauseLinks.brokenPairsIn(afterDoc, sectionId));
        refuseNew("clause.not_saved", was, now);

        recordUpdate("template_clause", clauseUuid, before, after.get());
        return Optional.of(reloadAndBump(templateId));
    }

    // The ordinal that puts the clause after afterId under parentId, or null when there is no room
    private BigDecimal clausePlace(UUID sectionId, UUID clauseUuid, UUID parentId, UUID afterId) {
        List<TemplateClauseRow> all = templateClauseRepository.findBySection(sectionId);
        TemplateClauseRow self = all.stream().filter(c -> c.uuid().equals(clauseUuid)).findFirst().orElseThrow();
        if (parentId != null && all.stream().noneMatch(c -> c.uuid().equals(parentId))) {
            throw InvalidRequest.onField("parentId", "clause.parent_not_in_section", sectionName(sectionId));
        }
        List<TemplateClauseRow> siblings = all.stream()
                .filter(c -> !c.uuid().equals(clauseUuid) && Objects.equals(c.parent(), parentId))
                .sorted(CLAUSE_ORDER)
                .toList();
        int at = -1;
        if (afterId != null) {
            for (int i = 0; i < siblings.size(); i++) {
                if (siblings.get(i).uuid().equals(afterId)) {
                    at = i;
                }
            }
            if (at < 0) {
                throw InvalidRequest.onField("afterId", "clause.move_after_unknown");
            }
        }
        BigDecimal low = at < 0 ? null : siblings.get(at).ordinal();
        BigDecimal high = at + 1 < siblings.size() ? siblings.get(at + 1).ordinal() : null;
        if (high == null) {
            // last under its parent: after every other clause of the section, because the clauses parks added
            // and the ones typed onto a draft share these numbers, and the last one should stay last for them too
            BigDecimal last = all.stream()
                    .filter(c -> !c.uuid().equals(clauseUuid))
                    .map(TemplateClauseRow::ordinal)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
            if (last != null && (low == null || last.compareTo(low) > 0)) {
                low = last;
            }
        }
        return between(low, high, self.ordinal());
    }

    private String sectionName(UUID sectionId) {
        return documentSectionRepository.findById(sectionId).map(DocumentSectionRow::name).orElse("");
    }

    /**
     * A number between low and high (either may be null: before everything, after everything).
     * Its own number when it is already between them; null when there is no room.
     */
    private static BigDecimal between(BigDecimal low, BigDecimal high, BigDecimal own) {
        boolean afterLow = low == null || own.compareTo(low) > 0;
        boolean beforeHigh = high == null || own.compareTo(high) < 0;
        if (afterLow && beforeHigh) {
            return own;
        }
        if (low == null && high == null) {
            return BigDecimal.ONE;
        }
        if (low == null) {
            return high.subtract(BigDecimal.ONE);
        }
        if (high == null) {
            return low.add(BigDecimal.ONE).setScale(0, RoundingMode.FLOOR);
        }
        if (high.subtract(low).compareTo(NO_ROOM) < 0) {
            return null;
        }
        return low.add(high).divide(BigDecimal.TWO, 4, RoundingMode.HALF_UP);
    }
}
