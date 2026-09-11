package io.github.lordship.documenttemplate;

import io.github.lordship.audit.ActingAgent;
import io.github.lordship.audit.AuditContext;
import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.documenttemplate.internal.*;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.DocumentToken;
import io.github.lordship.shared.InstrumentType;
import io.github.lordship.tenancyterms.TenancyChargeTerm;
import io.github.lordship.tenancyterms.TenancyChargeTermService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
    private final TenancyChargeTermService tenancyChargeTermService;
    private final AuditService auditService;
    private final AuditContext auditContext;

    public DocumentTemplateService(DocumentTemplateRepository documentTemplateRepository,
                                   DocumentSectionRepository documentSectionRepository,
                                   TemplateClauseRepository templateClauseRepository,
                                   TenancyChargeTermService tenancyChargeTermService,
                                   AuditService auditService,
                                   AuditContext auditContext) {
        this.documentTemplateRepository = documentTemplateRepository;
        this.documentSectionRepository = documentSectionRepository;
        this.templateClauseRepository = templateClauseRepository;
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
            DocumentToken token = DocumentToken.of(field).orElseThrow(() ->
                    new IllegalArgumentException("No such token: {{" + field + "}}" + suggestionFor(field)));

            if (!token.canCondition()) {
                throw new IllegalArgumentException(
                        "{{" + field + "}} is " + token.format() + ", not a method -- nothing branches on it");
            }
            // Same trap as appliesTo: Set.of(...) throws on a null probe. Leave
            // a method out of the map to say "unset"; naming it with no value
            // is a mistake worth reporting.
            if (value == null) {
                throw new IllegalArgumentException(
                        "{{" + field + "}} was given no value. Omit it entirely to preview it as unset");
            }
            if (!token.allowedValues().contains(value)) {
                throw new IllegalArgumentException(
                        "{{" + field + "}} does not take " + value + ". Allowed: "
                                + token.allowedValues().stream().sorted().collect(Collectors.joining(", ")));
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
                throw new IllegalStateException(
                        "\"" + existing.name() + "\" is still assigned to at least one property."
                                + " Unassign it there first, or the next lease for that park has"
                                + " nothing to generate from");
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

        Optional<DocumentSectionRow> afterOpt = documentSectionRepository.patch(sectionUuid, changes);
        if (afterOpt.isEmpty()) {
            return Optional.empty();
        }

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
                throw new IllegalStateException(
                        "\"" + existing.name() + "\" is a required section"
                                + (existing.statuteRef() == null ? "" : " (" + existing.statuteRef() + ")")
                                + ". Clear its required flag first if it genuinely no longer applies");
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

        Map<String, Object> effective = withConditionClearing(changes);

        validateBody(effective);
        validateCondition(before, effective);

        Optional<TemplateClauseRow> afterOpt = templateClauseRepository.patch(clauseUuid, effective);
        if (afterOpt.isEmpty()) {
            return Optional.empty();
        }

        Set<String> changed = recordUpdate("template_clause", clauseUuid, before, afterOpt.get());

        UUID templateId = documentSectionRepository.findById(before.section())
                .map(DocumentSectionRow::template)
                .orElseThrow(() -> new IllegalStateException(
                        "Clause " + clauseUuid + " points at a section that no longer exists"));

        return Optional.of(reload(templateId, affectsPrintedOutput(changed)));
    }

    @Transactional
    public boolean deleteClause(UUID clauseUuid) {
        return templateClauseRepository.findById(clauseUuid).map(existing -> {
            if (existing.required()) {
                throw new IllegalStateException(
                        "This clause is marked required"
                                + (existing.statuteRef() == null ? "" : " (" + existing.statuteRef() + ")")
                                + ". Clear the flag first if it genuinely no longer applies");
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

    // ---- validation ----------------------------------------------------------

    /**
     * Every {@code {{token}}} in the body has to be one the renderer can
     * resolve. Caught here rather than at generate, and answered with a
     * suggestion, because the person on the other end is writing a lease clause
     * and a bare rejection tells them nothing.
     */
    private static void validateBody(Map<String, Object> changes) {
        if (!changes.containsKey("body")) {
            return;
        }
        Object raw = changes.get("body");
        if (raw == null) {
            return;
        }

        List<String> problems = new ArrayList<>();
        for (String name : TemplateClause.tokenNamesIn(String.valueOf(raw))) {
            if (DocumentToken.of(name).isEmpty()) {
                problems.add("{{" + name + "}}" + suggestionFor(name));
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(
                    (problems.size() == 1 ? "No such token: " : "No such tokens: ")
                            + String.join("; ", problems));
        }
    }

    /**
     * Unchecking the last value and clearing the field are the same act: the
     * clause is no longer conditional. The two columns are stored together, so
     * clearing either one clears the other, rather than being refused for
     * leaving the pair half-set -- an editor that unticks the last checkbox
     * should not have to know it must also null the field.
     *
     * <p>Only when the other half is not being set in the same request. Sending
     * a field with an empty list, or values with a null field, is still a
     * mistake worth reporting: it asks for a clause that could never print.
     */
    private static Map<String, Object> withConditionClearing(Map<String, Object> changes) {
        boolean fieldSet = changes.containsKey("condition_field")
                && asStringOrNull(changes.get("condition_field")) != null;
        boolean valuesSet = changes.containsKey("condition_values")
                && !asStringList(changes.get("condition_values")).isEmpty();

        boolean fieldCleared = changes.containsKey("condition_field") && !fieldSet;
        boolean valuesCleared = changes.containsKey("condition_values") && !valuesSet;

        if (!(fieldCleared && !valuesSet) && !(valuesCleared && !fieldSet)) {
            return changes;
        }

        Map<String, Object> effective = new LinkedHashMap<>(changes);
        effective.put("condition_field", null);
        effective.put("condition_values", List.of());
        return effective;
    }

    /**
     * A clause may only branch on a method, and only on values that column
     * permits. Conditioning on {@code term.rate} is a category error --
     * "print this when the rate is 725" is not a rule anyone means -- and
     * {@code BANK_OR_FLTA} is a typo that would otherwise show up as a clause
     * that silently never prints.
     *
     * <p>Both halves move together: a field with no values matches nothing, and
     * values with no field have nothing to test.
     */
    private static void validateCondition(TemplateClauseRow before, Map<String, Object> changes) {
        boolean touched = changes.containsKey("condition_field") || changes.containsKey("condition_values");
        if (!touched) {
            return;
        }

        String field = changes.containsKey("condition_field")
                ? asStringOrNull(changes.get("condition_field"))
                : before.conditionField();

        List<String> values = changes.containsKey("condition_values")
                ? asStringList(changes.get("condition_values"))
                : before.conditionValues();

        if (field == null && values.isEmpty()) {
            return; // unconditional, which is most clauses
        }
        if (field == null) {
            throw new IllegalArgumentException(
                    "conditionValues were given with no conditionField to test them against");
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException(
                    "conditionField \"" + field + "\" was given with no values, so the clause would never print");
        }

        DocumentToken token = DocumentToken.of(field).orElseThrow(() ->
                new IllegalArgumentException("No such token: {{" + field + "}}" + suggestionFor(field)));

        if (!token.canCondition()) {
            throw new IllegalArgumentException(
                    "A clause cannot be conditioned on {{" + field + "}}: it is "
                            + token.format() + ", and only a method can decide whether a clause prints. "
                            + "Conditionable tokens: " + conditionableTokens());
        }

        Set<String> allowed = token.allowedValues();
        List<String> unknown = values.stream().filter(v -> !allowed.contains(v)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "{{" + field + "}} does not take " + String.join(", ", unknown)
                            + ". Allowed: " + allowed.stream().sorted().collect(Collectors.joining(", ")));
        }
    }

    private static String conditionableTokens() {
        return java.util.Arrays.stream(DocumentToken.values())
                .filter(DocumentToken::canCondition)
                .map(DocumentToken::placeholder)
                .sorted()
                .collect(Collectors.joining(", "));
    }

    // "did you mean" or nothing -- a wrong suggestion is worse than none.
    private static String suggestionFor(String unknown) {
        return DocumentToken.tokenNames().stream()
                .map(known -> Map.entry(known, editDistance(unknown, known)))
                .filter(e -> e.getValue() <= 3)
                .min(Comparator.comparingInt(Map.Entry::getValue))
                .map(e -> " -- did you mean {{" + e.getKey() + "}}?")
                .orElse("");
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitute = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitute, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    // ---- internals -----------------------------------------------------------

    /**
     * One place a DocumentTemplate gets built. Every read and every write
     * returns through here, so the editor can never be handed a clause list
     * that is merely whatever the caller happened to have in hand. Two queries
     * for the children, not one per section.
     */
    private DocumentTemplate hydrate(DocumentTemplateRow row) {
        List<DocumentSectionRow> sectionRows = documentSectionRepository.findByTemplate(row.uuid());
        if (sectionRows.isEmpty()) {
            return row.toDocumentTemplate();
        }

        List<UUID> sectionIds = sectionRows.stream().map(DocumentSectionRow::uuid).toList();
        Map<UUID, List<TemplateClauseRow>> clausesBySection =
                templateClauseRepository.findBySectionIds(sectionIds).stream()
                        .collect(Collectors.groupingBy(TemplateClauseRow::section));

        List<DocumentSection> sections = sectionRows.stream()
                .map(section -> section.toDocumentSection(
                        clausesBySection.getOrDefault(section.uuid(), List.of())))
                .toList();

        return row.toDocumentTemplate(sections);
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
     * Fields that never reach the page. An instrument freezes template_version
     * to say which wording it was cut from, so the number should move when the
     * wording does and not when an author leaves themselves a reminder.
     *
     * <p>Record component names, not column names -- {@link AuditMapper} works
     * off the accessor names. They happen to agree for {@code note}; a field
     * added here whose Java name is camelCase must be spelled that way.
     */
    private static final Set<String> NON_PRINTING_FIELDS = Set.of("note");

    private static boolean affectsPrintedOutput(Set<String> changedFields) {
        return changedFields.stream().anyMatch(field -> !NON_PRINTING_FIELDS.contains(field));
    }

    private static String asStringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static List<String> asStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .toList();
        }
        throw new IllegalArgumentException("conditionValues must be a list of strings");
    }
}