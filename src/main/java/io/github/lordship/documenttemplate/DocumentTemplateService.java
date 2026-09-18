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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final TenancyChargeTermService tenancyChargeTermService;
    private final AuditService auditService;
    private final AuditContext auditContext;

    public DocumentTemplateService(DocumentTemplateRepository documentTemplateRepository,
                                   DocumentSectionRepository documentSectionRepository,
                                   TemplateClauseRepository templateClauseRepository,
                                   DocumentStyleRepository documentStyleRepository,
                                   PropertyDocumentCustomizationRepository customizationRepository,
                                   TenancyChargeTermService tenancyChargeTermService,
                                   AuditService auditService,
                                   AuditContext auditContext) {
        this.documentTemplateRepository = documentTemplateRepository;
        this.documentSectionRepository = documentSectionRepository;
        this.templateClauseRepository = templateClauseRepository;
        this.documentStyleRepository = documentStyleRepository;
        this.customizationRepository = customizationRepository;
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
        DocumentTemplateRow saved = documentTemplateRepository.save(
                name, agreementType, instrumentType, ActingAgent.resolve(auditContext));

        auditService.recordInsert("document_template", saved.uuid(), AuditMapper.toMap(saved));
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

        Map<String, Object> effective = new HashMap<>(changes);
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
        List<Problem> was = new ArrayList<>(ClauseLinks.problemsWith(beforeDoc, clauseUuid));
        was.addAll(ClauseLinks.brokenPairsIn(beforeDoc, before.section()));
        List<Problem> now = new ArrayList<>(ClauseLinks.problemsWith(afterDoc, clauseUuid));
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

}
