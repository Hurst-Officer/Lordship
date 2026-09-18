package io.github.lordship.documenttemplate;

import io.github.lordship.audit.AuditContext;
import io.github.lordship.audit.AuditService;
import io.github.lordship.documenttemplate.internal.DocumentSectionRepository;
import io.github.lordship.documenttemplate.internal.DocumentSectionRow;
import io.github.lordship.documenttemplate.internal.DocumentStyleRepository;
import io.github.lordship.documenttemplate.internal.PropertyDocumentCustomizationRepository;
import io.github.lordship.documenttemplate.internal.DocumentTemplateRepository;
import io.github.lordship.documenttemplate.internal.DocumentTemplateRow;
import io.github.lordship.documenttemplate.internal.TemplateClauseRepository;
import io.github.lordship.documenttemplate.internal.TemplateClauseRow;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.tenancyterms.TenancyChargeTermService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The save-time rules. Everything here refuses before it writes, which is the
 * whole point -- a legal document is the wrong place to discover that a token
 * was misspelled or a clause conditioned on a money amount.
 *
 * <p>Deliberately no create tests: those resolve an acting agent from the audit
 * context, which is real infrastructure rather than logic. They are covered end
 * to end in {@code DocumentTemplateControllerIT}.
 */
@ExtendWith(MockitoExtension.class)
public class DocumentTemplateServiceTest {

    private DocumentTemplateRepository documentTemplateRepository;
    private DocumentSectionRepository documentSectionRepository;
    private TemplateClauseRepository templateClauseRepository;
    private DocumentStyleRepository documentStyleRepository;
    private PropertyDocumentCustomizationRepository customizationRepository;
    private TenancyChargeTermService tenancyChargeTermService;
    private AuditService auditService;
    private AuditContext auditContext;
    private DocumentTemplateService documentTemplateService;

    private UUID clauseId;
    private UUID sectionId;
    private UUID templateId;

    @BeforeEach
    void setup() {
        documentTemplateRepository = mock(DocumentTemplateRepository.class);
        documentSectionRepository = mock(DocumentSectionRepository.class);
        templateClauseRepository = mock(TemplateClauseRepository.class);
        documentStyleRepository = mock(DocumentStyleRepository.class);
        customizationRepository = mock(PropertyDocumentCustomizationRepository.class);
        tenancyChargeTermService = mock(TenancyChargeTermService.class);
        auditService = mock(AuditService.class);
        auditContext = mock(AuditContext.class);

        documentTemplateService = new DocumentTemplateService(
                documentTemplateRepository,
                documentSectionRepository,
                templateClauseRepository,
                documentStyleRepository,
                customizationRepository,
                tenancyChargeTermService,
                auditService,
                auditContext);

        clauseId = UUID.randomUUID();
        sectionId = UUID.randomUUID();
        templateId = UUID.randomUUID();
    }

    private TemplateClauseRow clauseRow(String conditionField, List<String> conditionValues) {
        return new TemplateClauseRow(
                clauseId, sectionId, BigDecimal.valueOf(1000), "TEST", null, "body",
                conditionField, conditionValues, false, null, null, null, null, null,
                null, null, true, null, null);
    }

    private DocumentSectionRow sectionRow(boolean required) {
        return new DocumentSectionRow(
                sectionId, templateId, BigDecimal.valueOf(1000), "Septic", "SEPTIC",
                false, false, required, "RCW 59.20", null, null, null, null,
                List.of(), List.of(), null, null);
    }

    private DocumentTemplateRow templateRow() {
        return new DocumentTemplateRow(
                templateId, "WA Land Lease 2026",
                AgreementType.LAND, InstrumentType.LEASE, 1, null, null, null, null);
    }

    /**
     * Every clause and section write ends by rebuilding the whole document, and
     * -- when a printed field actually moved -- bumping the version first.
     *
     * <p>Which of those two paths a given test takes depends on what its patch
     * stub returns, so neither can be a strict stub: whichever one the test does
     * not use would be reported as unnecessary.
     */
    private void stubReload() {
        when(documentSectionRepository.findById(sectionId)).thenReturn(Optional.of(sectionRow(false)));
        when(documentSectionRepository.findByTemplate(templateId)).thenReturn(List.of());

        lenient().when(documentTemplateRepository.bumpVersion(templateId))
                .thenReturn(Optional.of(templateRow()));
        lenient().when(documentTemplateRepository.findById(templateId))
                .thenReturn(Optional.of(templateRow()));
    }

    private TemplateClauseRow clauseRowWithNote(String note) {
        return new TemplateClauseRow(
                clauseId, sectionId, BigDecimal.valueOf(1000), "TEST", null, "body",
                null, List.of(), false, null, note, null, null, null,
                null, null, true, null, null);
    }

    private TemplateClauseRow clauseRowWithBody(String body) {
        return new TemplateClauseRow(
                clauseId, sectionId, BigDecimal.valueOf(1000), "TEST", null, body,
                null, List.of(), false, null, null, null, null, null,
                null, null, true, null, null);
    }

    private static Map<String, Object> change(String key, Object value) {
        Map<String, Object> changes = new HashMap<>();
        changes.put(key, value);
        return changes;
    }

    // ---- token validation ----------------------------------------------------

    @Test
    void patchClause_shouldRejectAnUnknownToken_withASuggestion() {
        // Arrange
        when(templateClauseRepository.findById(clauseId)).thenReturn(Optional.of(clauseRow(null, List.of())));

        // Act
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> documentTemplateService.patchClause(clauseId,
                        change("body", "Rent is {{term.raet}}.")));

        // Assert
        assertTrue(e.getMessage().contains("term.raet"));
        assertTrue(e.getMessage().contains("term.rate"), "should suggest the near miss");
        verify(templateClauseRepository, never()).patch(any(), any());
    }

    // One message naming every bad field, so the author fixes the whole clause
    // at once instead of one round trip per typo.
    @Test
    void patchClause_shouldReportEveryUnknownToken_inOneMessage() {
        // Arrange
        when(templateClauseRepository.findById(clauseId)).thenReturn(Optional.of(clauseRow(null, List.of())));

        // Act
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> documentTemplateService.patchClause(clauseId,
                        change("body", "{{term.raet}} and {{lot.numbr}}")));

        // Assert
        assertTrue(e.getMessage().contains("term.raet"));
        assertTrue(e.getMessage().contains("lot.numbr"));
    }

    @Test
    void patchClause_shouldAcceptABodyOfKnownTokens() {
        // Arrange
        String body = "Rent of {{term.rate}} at {{property.remittance_address}}.";
        when(templateClauseRepository.findById(clauseId))
                .thenReturn(Optional.of(clauseRow(null, List.of())));
        when(templateClauseRepository.patch(eq(clauseId), any()))
                .thenReturn(Optional.of(clauseRowWithBody(body)));
        stubReload();

        // Act
        Optional<DocumentTemplate> result = documentTemplateService.patchClause(
                clauseId, change("body", body));

        // Assert
        assertTrue(result.isPresent());
        verify(templateClauseRepository).patch(eq(clauseId), any());
    }

    // A change to the wording is a change to the document, so the number an
    // instrument freezes as template_version has to move.
    //
    // The stub has to return a genuinely different row: the service decides off
    // the audit diff, so a patch stubbed to hand back the row it was given is a
    // patch that changed nothing.
    @Test
    void patchClause_shouldBumpTheTemplateVersion_whenTheBodyChanged() {
        // Arrange
        when(templateClauseRepository.findById(clauseId))
                .thenReturn(Optional.of(clauseRow(null, List.of())));
        when(templateClauseRepository.patch(eq(clauseId), any()))
                .thenReturn(Optional.of(clauseRowWithBody("Rent is {{term.rate}}.")));
        stubReload();

        // Act
        documentTemplateService.patchClause(clauseId, change("body", "Rent is {{term.rate}}."));

        // Assert
        verify(documentTemplateRepository).bumpVersion(templateId);
    }

    // A note is a reminder to whoever edits this next, not wording. An instrument
    // freezes template_version to say which text it was cut from, so two
    // instruments with different numbers should differ in what they printed.
    @Test
    void patchClause_shouldNotBumpTheVersion_whenOnlyTheNoteChanged() {
        // Arrange
        when(templateClauseRepository.findById(clauseId))
                .thenReturn(Optional.of(clauseRow(null, List.of())));
        when(templateClauseRepository.patch(eq(clauseId), any()))
                .thenReturn(Optional.of(clauseRowWithNote("Check against RCW 59.20 before June")));
        stubReload();

        // Act
        documentTemplateService.patchClause(
                clauseId, change("note", "Check against RCW 59.20 before June"));

        // Assert: still audited, just not a new version of the document
        verify(documentTemplateRepository, never()).bumpVersion(templateId);
        verify(auditService).recordUpdate(eq("template_clause"), eq(clauseId), any(), any());
    }

    // A patch that changes nothing is not an edit: no audit row, and no version
    // move either.
    @Test
    void patchClause_shouldDoNeither_whenNothingActuallyChanged() {
        // Arrange
        TemplateClauseRow unchanged = clauseRow(null, List.of());
        when(templateClauseRepository.findById(clauseId)).thenReturn(Optional.of(unchanged));
        when(templateClauseRepository.patch(eq(clauseId), any())).thenReturn(Optional.of(unchanged));
        stubReload();

        // Act
        documentTemplateService.patchClause(clauseId, change("body", "body"));

        // Assert
        verify(documentTemplateRepository, never()).bumpVersion(templateId);
        verify(auditService, never()).recordUpdate(any(), any(), any(), any());
    }

    // ---- condition validation ------------------------------------------------

    @Test
    void patchClause_shouldRefuseAConditionOnAMoneyToken() {
        // Arrange
        when(templateClauseRepository.findById(clauseId)).thenReturn(Optional.of(clauseRow(null, List.of())));
        Map<String, Object> changes = change("condition_field", "term.rate");
        changes.put("condition_values", List.of("FLAT"));

        // Act
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> documentTemplateService.patchClause(clauseId, changes));

        // Assert: "print this when the rate is 725" is not a rule anyone means
        assertTrue(e.getMessage().contains("MONEY"));
        verify(templateClauseRepository, never()).patch(any(), any());
    }

    // The typo that would otherwise become a clause that silently never prints.
    @Test
    void patchClause_shouldRefuseAValueTheColumnDoesNotTake() {
        // Arrange
        when(templateClauseRepository.findById(clauseId)).thenReturn(Optional.of(clauseRow(null, List.of())));
        Map<String, Object> changes = change("condition_field", "term.nsf_fee_method");
        changes.put("condition_values", List.of("BANK_OR_FLTA"));

        // Act
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> documentTemplateService.patchClause(clauseId, changes));

        // Assert
        assertTrue(e.getMessage().contains("BANK_OR_FLTA"));
        assertTrue(e.getMessage().contains("BANK_OR_FLAT"), "should list what is allowed");
    }

    // PERCENT_OF_RENT is legal for the late fee and not for NSF -- the subsets
    // are per column, not per Java enum.
    @Test
    void patchClause_shouldRefuseAValueFromAnotherColumnsSubset() {
        // Arrange
        when(templateClauseRepository.findById(clauseId)).thenReturn(Optional.of(clauseRow(null, List.of())));
        Map<String, Object> changes = change("condition_field", "term.nsf_fee_method");
        changes.put("condition_values", List.of("PERCENT_OF_RENT"));

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> documentTemplateService.patchClause(clauseId, changes));
    }

    @Test
    void patchClause_shouldRefuseAFieldWithNoValues() {
        // Arrange
        when(templateClauseRepository.findById(clauseId)).thenReturn(Optional.of(clauseRow(null, List.of())));

        // Act
        InvalidRequest e = assertThrows(InvalidRequest.class,
                () -> documentTemplateService.patchClause(
                        clauseId, change("condition_field", "term.late_fee_method")));

        // Assert: a field with no values matches nothing, so the clause would never print
        assertEquals("clause.field_without_values", e.problem().code());
        assertEquals("conditionValues", e.problem().field());
    }

    @Test
    void patchClause_shouldRefuseValuesWithNoField() {
        // Arrange
        when(templateClauseRepository.findById(clauseId)).thenReturn(Optional.of(clauseRow(null, List.of())));

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> documentTemplateService.patchClause(
                        clauseId, change("condition_values", List.of("FLAT"))));
    }

    // Both keys together, so a half-cleared condition cannot exist.
    @Test
    void patchClause_shouldAllowClearingBothHalvesAtOnce() {
        // Arrange
        when(templateClauseRepository.findById(clauseId))
                .thenReturn(Optional.of(clauseRow("term.nsf_fee_method", List.of("FLAT"))));
        when(templateClauseRepository.patch(eq(clauseId), any()))
                .thenReturn(Optional.of(clauseRow(null, List.of())));
        stubReload();

        Map<String, Object> changes = change("condition_field", null);
        changes.put("condition_values", List.of());

        // Act
        Optional<DocumentTemplate> result = documentTemplateService.patchClause(clauseId, changes);

        // Assert
        assertTrue(result.isPresent());
        verify(templateClauseRepository).patch(eq(clauseId), any());
    }

    // Unticking the last value is how an author makes a clause unconditional.
    // The two columns are stored together, so clearing either clears the other
    // rather than being refused for leaving the pair half-set.
    @SuppressWarnings("unchecked")
    @Test
    void patchClause_shouldClearBothHalves_whenOnlyTheValuesAreEmptied() {
        // Arrange
        when(templateClauseRepository.findById(clauseId))
                .thenReturn(Optional.of(clauseRow("term.nsf_fee_method", List.of("FLAT"))));
        when(templateClauseRepository.patch(eq(clauseId), any()))
                .thenReturn(Optional.of(clauseRow(null, List.of())));
        stubReload();

        // Act
        documentTemplateService.patchClause(clauseId, change("condition_values", List.of()));

        // Assert: the field is cleared too, even though the caller never named it
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(templateClauseRepository).patch(eq(clauseId), captor.capture());

        Map<String, Object> sent = captor.getValue();
        assertTrue(sent.containsKey("condition_field"));
        assertNull(sent.get("condition_field"));
        assertEquals(List.of(), sent.get("condition_values"));
    }

    @Test
    void patchClause_shouldReturnEmpty_whenTheClauseDoesNotExist() {
        // Arrange
        when(templateClauseRepository.findById(clauseId)).thenReturn(Optional.empty());

        // Act & Assert
        assertTrue(documentTemplateService.patchClause(clauseId, change("body", "x")).isEmpty());
    }

    // ---- preview -------------------------------------------------------------

    @Test
    void preview_shouldRefuseAMethodValueTheColumnDoesNotTake() {
        // Act & Assert: validated before the template is even read
        assertThrows(IllegalArgumentException.class, () -> documentTemplateService.preview(
                templateId, Map.of("term.trash_method", "SUBMETERED")));
        verifyNoInteractions(documentTemplateRepository);
    }

    @Test
    void preview_shouldRefuseANonMethodToken() {
        assertThrows(IllegalArgumentException.class, () -> documentTemplateService.preview(
                templateId, Map.of("term.rate", "725.00")));
    }

    // Omitting a method means "unset"; naming it with no value is a mistake.
    @Test
    void preview_shouldRefuseANamedMethodWithNoValue() {
        // Arrange
        Map<String, String> methodValues = new HashMap<>();
        methodValues.put("term.trash_method", null);

        // Act
        InvalidRequest  e = assertThrows(InvalidRequest.class,
                () -> documentTemplateService.preview(templateId, methodValues));

        // Assert
        assertEquals("token.value_omitted", e.problem().code());
    }

    // ---- delete guards -------------------------------------------------------

    @Test
    void deleteTemplate_shouldRefuse_whileAPropertyStillUsesIt() {
        // Arrange
        when(documentTemplateRepository.findById(templateId)).thenReturn(Optional.of(templateRow()));
        when(documentTemplateRepository.isAssignedToAnyProperty(templateId)).thenReturn(true);

        // Act
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> documentTemplateService.deleteTemplate(templateId));

        // Assert
        assertTrue(e.getMessage().contains("WA Land Lease 2026"));
        verify(documentTemplateRepository, never()).softDelete(any());
    }

    // A required section satisfies a statute; dropping it globally is not the
    // same act as one park excluding it.
    @Test
    void deleteSection_shouldRefuseARequiredSection() {
        // Arrange
        when(documentSectionRepository.findById(sectionId)).thenReturn(Optional.of(sectionRow(true)));

        // Act
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> documentTemplateService.deleteSection(sectionId));

        // Assert
        assertTrue(e.getMessage().contains("RCW 59.20"));
        verify(documentSectionRepository, never()).softDelete(any());
    }

    @Test
    void deleteSection_shouldReturnFalse_whenTheSectionDoesNotExist() {
        // Arrange
        when(documentSectionRepository.findById(sectionId)).thenReturn(Optional.empty());

        // Act & Assert
        assertFalse(documentTemplateService.deleteSection(sectionId));
    }
}
