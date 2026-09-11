package io.github.lordship.termstemplate;

import io.github.lordship.audit.AuditContext;
import io.github.lordship.audit.AuditService;
import io.github.lordship.shared.*;
import io.github.lordship.termstemplate.internal.TermsTemplateRepository;
import io.github.lordship.termstemplate.internal.TermsTemplateRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TermsTemplateServiceTest {

    @Mock
    TermsTemplateRepository termsTemplateRepository;

    @Mock
    AuditService auditService;

    @Mock
    AuditContext auditContext;

    @InjectMocks
    TermsTemplateService termsTemplateService;

    // ── Global templates ─────────────────────────────────────────────────────

    @Test
    void createGlobalTemplate_shouldSaveAndRecordAudit() {
        // Arrange
        TermsTemplateRow stubRow = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findGlobalByName("WA Land Lease 2026")).thenReturn(Optional.empty());
        when(termsTemplateRepository.save(any())).thenReturn(stubRow);

        // Act
        TermsTemplate result = termsTemplateService.createGlobalTemplate("WA Land Lease 2026", AgreementType.LAND);

        // Assert
        assertEquals("WA Land Lease 2026", result.name());
        assertNull(result.property(), "a global template has no property");
        verify(auditService).recordInsert(eq("terms_template"), eq(stubRow.uuid()), any());
    }

    @Test
    void createGlobalTemplate_shouldAttributeToSystem_whenThereIsNoRequest() {
        // Arrange -- a unit test has no bound request, so ActingAgent falls back
        TermsTemplateRow stubRow = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findGlobalByName(any())).thenReturn(Optional.empty());
        when(termsTemplateRepository.save(any())).thenReturn(stubRow);

        // Act
        termsTemplateService.createGlobalTemplate("WA Land Lease 2026", AgreementType.LAND);

        // Assert
        verify(termsTemplateRepository).save(argThat(
                row -> SystemPrincipal.AGENT_UUID.equals(row.createdBy())));
    }

    @Test
    void createGlobalTemplate_shouldThrow_whenNameAlreadyTaken() {
        // Arrange
        TermsTemplateRow existing = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findGlobalByName("WA Land Lease 2026")).thenReturn(Optional.of(existing));

        // Act / Assert
        assertThrows(IllegalStateException.class,
                () -> termsTemplateService.createGlobalTemplate("WA Land Lease 2026", AgreementType.LAND));
        verify(termsTemplateRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    // ── Copying a template into a property ───────────────────────────────────

    @Test
    void copyTemplateToProperty_shouldReturnEmpty_whenTemplateNotFound() {
        // Arrange
        UUID unknownUUID = UUID.randomUUID();
        when(termsTemplateRepository.findById(unknownUUID)).thenReturn(Optional.empty());

        // Act
        Optional<TermsTemplate> result =
                termsTemplateService.copyTemplateToProperty(unknownUUID, UUID.randomUUID());

        // Assert
        assertTrue(result.isEmpty());
        verify(termsTemplateRepository, never()).saveCopy(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void copyTemplateToProperty_shouldThrow_whenSourceIsAlreadyPropertyLevel() {
        // Arrange -- only a global template may be copied in
        UUID sourceProperty = UUID.randomUUID();
        TermsTemplateRow propertyLevel =
                template(UUID.randomUUID(), "Copied Set", AgreementType.LAND).copyTo(sourceProperty, null);
        when(termsTemplateRepository.findById(any())).thenReturn(Optional.of(propertyLevel));

        // Act / Assert
        assertThrows(IllegalArgumentException.class,
                () -> termsTemplateService.copyTemplateToProperty(UUID.randomUUID(), UUID.randomUUID()));
        verify(termsTemplateRepository, never()).saveCopy(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void copyTemplateToProperty_shouldThrow_whenPropertyAlreadyHasThatAgreementType() {
        // Arrange -- a property may hold at most one set per agreement type
        UUID property = UUID.randomUUID();
        TermsTemplateRow templateRow = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findById(templateRow.uuid())).thenReturn(Optional.of(templateRow));
        when(termsTemplateRepository.findByPropertyAndAgreementType(property, AgreementType.LAND))
                .thenReturn(Optional.of(templateRow.copyTo(property, null)));

        // Act / Assert
        assertThrows(IllegalStateException.class,
                () -> termsTemplateService.copyTemplateToProperty(templateRow.uuid(), property));
        verify(termsTemplateRepository, never()).saveCopy(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void copyTemplateToProperty_shouldCarryEveryTermValue_andRecordProvenance() {
        // Arrange
        UUID property = UUID.randomUUID();
        TermsTemplateRow templateRow = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findById(templateRow.uuid())).thenReturn(Optional.of(templateRow));
        when(termsTemplateRepository.findByPropertyAndAgreementType(property, AgreementType.LAND))
                .thenReturn(Optional.empty());
        when(termsTemplateRepository.saveCopy(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        termsTemplateService.copyTemplateToProperty(templateRow.uuid(), property);

        // Assert -- the copy must land on the property with its identity dropped,
        // its provenance recorded, and every term value intact.
        verify(termsTemplateRepository).saveCopy(argThat(copy -> {
            assertNull(copy.uuid(), "the copy gets a new identity from the database");
            assertEquals(property, copy.property());
            assertEquals(templateRow.uuid(), copy.copiedFrom(), "provenance link back to the template");
            assertEquals(templateRow.name(), copy.name());
            assertEquals(templateRow.agreementType(), copy.agreementType());
            assertEquals(templateRow.targetRate(), copy.targetRate());
            assertEquals(templateRow.carFee(), copy.carFee());
            assertEquals(templateRow.allowedCars(), copy.allowedCars());
            assertEquals(templateRow.carsMax(), copy.carsMax()); // the column saveCopy used to drop
            assertEquals(templateRow.petFee(), copy.petFee());
            assertEquals(templateRow.allowedPets(), copy.allowedPets());
            assertEquals(templateRow.paymentDueDay(), copy.paymentDueDay());
            assertEquals(templateRow.gracePeriodDays(), copy.gracePeriodDays());
            assertEquals(templateRow.lateFeeMethod(), copy.lateFeeMethod());
            assertEquals(templateRow.lateFeeAmount(), copy.lateFeeAmount());
            assertEquals(templateRow.nsfFeeMethod(), copy.nsfFeeMethod());
            assertEquals(templateRow.nsfFeeAmount(), copy.nsfFeeAmount());
            assertEquals(templateRow.waterMethod(), copy.waterMethod());
            assertEquals(templateRow.trashMethod(), copy.trashMethod());
            return true;
        }));
        verify(auditService).recordInsert(eq("terms_template"), any(), any());
    }

    // ── Patching ─────────────────────────────────────────────────────────────

    @Test
    void patchTermsTemplate_shouldReturnEmpty_whenSetDoesNotExist() {
        // Arrange
        UUID unknownUUID = UUID.randomUUID();
        when(termsTemplateRepository.findById(unknownUUID)).thenReturn(Optional.empty());

        // Act
        Optional<TermsTemplate> result =
                termsTemplateService.patchTermsTemplate(unknownUUID, Map.of("name", "Nope"));

        // Assert
        assertTrue(result.isEmpty());
        verify(termsTemplateRepository, never()).patch(any(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    void patchTermsTemplate_shouldNotRecordAudit_whenOnlyUpdatedAtChanged() {
        // Arrange -- patch always bumps updated_at, which is housekeeping, not a change
        TermsTemplateRow before = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        TermsTemplateRow after = withUpdatedAt(before, before.updatedAt().plusMinutes(1));
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(termsTemplateRepository.patch(eq(before.uuid()), any())).thenReturn(Optional.of(after));

        // Act
        Map<String, Object> changes = new HashMap<>();
        changes.put("name", "WA Land Lease 2026"); // same value
        termsTemplateService.patchTermsTemplate(before.uuid(), changes);

        // Assert
        verifyNoInteractions(auditService);
    }

    @Test
    void patchTermsTemplate_shouldRecordAudit_whenFieldActuallyChanges() {
        // Arrange
        TermsTemplateRow before = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        TermsTemplateRow after = withTargetRate(before, new BigDecimal("700.00"));
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(termsTemplateRepository.patch(eq(before.uuid()), any())).thenReturn(Optional.of(after));

        // Act
        Map<String, Object> changes = new HashMap<>();
        changes.put("target_rate", new BigDecimal("700.00"));
        termsTemplateService.patchTermsTemplate(before.uuid(), changes);

        // Assert
        verify(auditService).recordUpdate(eq("terms_template"), eq(before.uuid()), any(), any());
    }

    @Test
    void patchTermsTemplate_shouldZeroTheAmount_whenMethodBecomesNone() {
        // Arrange
        TermsTemplateRow before = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(termsTemplateRepository.patch(eq(before.uuid()), any())).thenReturn(Optional.of(before));

        // Act
        Map<String, Object> changes = new HashMap<>();
        changes.put("late_fee_method", "NONE");
        termsTemplateService.patchTermsTemplate(before.uuid(), changes);

        // Assert -- the CHECK constraint requires the amount to be zero
        verify(termsTemplateRepository).patch(eq(before.uuid()), argThat(
                map -> BigDecimal.ZERO.equals(map.get("late_fee_amount"))));
    }

    @Test
    void patchTermsTemplate_shouldNormaliseMethodCase() {
        // Arrange
        TermsTemplateRow before = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(termsTemplateRepository.patch(eq(before.uuid()), any())).thenReturn(Optional.of(before));

        // Act
        Map<String, Object> changes = new HashMap<>();
        changes.put("water_method", "  submetered  ");
        termsTemplateService.patchTermsTemplate(before.uuid(), changes);

        // Assert
        verify(termsTemplateRepository).patch(eq(before.uuid()), argThat(
                map -> "SUBMETERED".equals(map.get("water_method"))));
    }

    @Test
    void patchTermsTemplate_shouldKeepTheExistingAmount_whenOnlyTheMethodIsPatched() {
        // Arrange -- before already has a FLAT late fee of 65
        TermsTemplateRow before = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(termsTemplateRepository.patch(eq(before.uuid()), any())).thenReturn(Optional.of(before));

        // Act
        Map<String, Object> changes = new HashMap<>();
        changes.put("late_fee_method", "FLAT");
        termsTemplateService.patchTermsTemplate(before.uuid(), changes);

        // Assert -- patching one half of the pair must not wipe the other
        verify(termsTemplateRepository).patch(eq(before.uuid()), argThat(
                map -> new BigDecimal("65.00").compareTo((BigDecimal) map.get("late_fee_amount")) == 0));
    }

    @Test
    void patchTermsTemplate_shouldThrow_whenFlatMethodIsGivenZero() {
        // Arrange
        TermsTemplateRow before = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));

        // Act / Assert
        Map<String, Object> changes = new HashMap<>();
        changes.put("late_fee_method", "FLAT");
        changes.put("late_fee_amount", BigDecimal.ZERO);
        assertThrows(IllegalArgumentException.class,
                () -> termsTemplateService.patchTermsTemplate(before.uuid(), changes));
        verify(termsTemplateRepository, never()).patch(any(), any());
    }

    @Test
    void patchTermsTemplate_shouldThrow_whenMethodIsNotInTheVocabulary() {
        // Arrange
        TermsTemplateRow before = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));

        // Act / Assert
        Map<String, Object> changes = new HashMap<>();
        changes.put("water_method", "BANANA");
        assertThrows(IllegalArgumentException.class,
                () -> termsTemplateService.patchTermsTemplate(before.uuid(), changes));
        verify(termsTemplateRepository, never()).patch(any(), any());
    }

    @Test
    void patchTermsTemplate_shouldThrow_whenTrashIsSetToSubmetered() {
        // Arrange -- trash has no submetered option
        TermsTemplateRow before = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));

        // Act / Assert
        Map<String, Object> changes = new HashMap<>();
        changes.put("trash_method", "SUBMETERED");
        assertThrows(IllegalArgumentException.class,
                () -> termsTemplateService.patchTermsTemplate(before.uuid(), changes));
    }

    // FAILS TODAY. FEE_METHODS in the service is Set.of("NONE","FLAT"), but FeeMethod
    // now carries PERCENT_OF_RENT and BANK_OR_FLAT -- the two methods the real WA lease
    // actually uses. This is the test that says so.
    @Test
    void patchTermsTemplate_shouldAcceptPercentOfRent_andKeepItsAmount() {
        // Arrange
        TermsTemplateRow before = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(termsTemplateRepository.patch(eq(before.uuid()), any())).thenReturn(Optional.of(before));

        // Act -- "one and a half percent of the monthly lot rent"
        Map<String, Object> changes = new HashMap<>();
        changes.put("late_fee_method", "PERCENT_OF_RENT");
        changes.put("late_fee_amount", new BigDecimal("1.5"));
        termsTemplateService.patchTermsTemplate(before.uuid(), changes);

        // Assert -- the percentage must survive, not be zeroed as a non-FLAT method
        verify(termsTemplateRepository).patch(eq(before.uuid()), argThat(
                map -> new BigDecimal("1.5").compareTo((BigDecimal) map.get("late_fee_amount")) == 0));
    }

    // FAILS TODAY, same cause.
    @Test
    void patchTermsTemplate_shouldAcceptBankOrFlat_andKeepItsFloor() {
        // Arrange
        TermsTemplateRow before = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(termsTemplateRepository.patch(eq(before.uuid()), any())).thenReturn(Optional.of(before));

        // Act -- "$35.00, or as charged by the financial institution, whichever is greater"
        Map<String, Object> changes = new HashMap<>();
        changes.put("nsf_fee_method", "BANK_OR_FLAT");
        changes.put("nsf_fee_amount", new BigDecimal("35.00"));
        termsTemplateService.patchTermsTemplate(before.uuid(), changes);

        // Assert
        verify(termsTemplateRepository).patch(eq(before.uuid()), argThat(
                map -> new BigDecimal("35.00").compareTo((BigDecimal) map.get("nsf_fee_amount")) == 0));
    }

    // ── Fee method vocabularies ──────────────────────────────────────────────
    // Each fee column accepts a different subset of FeeMethod, matching its column
    // CHECK in V1. These tests state those subsets independently of the service, so
    // a change in either place has to be a deliberate change in both.

    @Test
    void lateFeeVocabulary_shouldAcceptPercentOfRent_andRejectBankOrFlat() {
        // Arrange
        TermsTemplateRow before = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        stubFor(before);

        // Act / Assert -- a percentage of rent is what the real WA lease charges;
        // a bank fee is meaningless for lateness.
        assertTrue(accepts(before, "late_fee_method", "late_fee_amount", FeeMethod.NONE));
        assertTrue(accepts(before, "late_fee_method", "late_fee_amount", FeeMethod.FLAT));
        assertTrue(accepts(before, "late_fee_method", "late_fee_amount", FeeMethod.PERCENT_OF_RENT));
        assertFalse(accepts(before, "late_fee_method", "late_fee_amount", FeeMethod.BANK_OR_FLAT),
                "a late fee cannot be whatever the bank charged");
    }

    @Test
    void nsfFeeVocabulary_shouldAcceptBankOrFlat_andRejectPercentOfRent() {
        // Arrange
        TermsTemplateRow before = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        stubFor(before);

        // Act / Assert -- "$35.00, or as charged by the financial institution,
        // whichever is greater" is BANK_OR_FLAT; a returned check has no relation to rent.
        assertTrue(accepts(before, "nsf_fee_method", "nsf_fee_amount", FeeMethod.NONE));
        assertTrue(accepts(before, "nsf_fee_method", "nsf_fee_amount", FeeMethod.FLAT));
        assertTrue(accepts(before, "nsf_fee_method", "nsf_fee_amount", FeeMethod.BANK_OR_FLAT));
        assertFalse(accepts(before, "nsf_fee_method", "nsf_fee_amount", FeeMethod.PERCENT_OF_RENT),
                "an NSF fee is not a percentage of rent");
    }

    @Test
    void violationFeeVocabulary_shouldAcceptOnlyNoneAndFlat() {
        // Arrange
        TermsTemplateRow before = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        stubFor(before);

        // Act / Assert -- one flat fee for all violations, if charged at all
        assertTrue(accepts(before, "rule_violation_fee_method", "rule_violation_fee_amount", FeeMethod.NONE));
        assertTrue(accepts(before, "rule_violation_fee_method", "rule_violation_fee_amount", FeeMethod.FLAT));
        assertFalse(accepts(before, "rule_violation_fee_method", "rule_violation_fee_amount", FeeMethod.PERCENT_OF_RENT));
        assertFalse(accepts(before, "rule_violation_fee_method", "rule_violation_fee_amount", FeeMethod.BANK_OR_FLAT));
    }

    // The drift guard: add a constant to FeeMethod and this fails until some column
    // is taught to accept it. Without it, a new method compiles, passes every other
    // test, and is silently unreachable.
    @Test
    void everyFeeMethod_shouldBeAcceptedBySomeFeeColumn() {
        // Arrange
        TermsTemplateRow before = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        stubFor(before);

        // Act / Assert
        for (FeeMethod method : FeeMethod.values()) {
            boolean usableSomewhere =
                    accepts(before, "late_fee_method", "late_fee_amount", method)
                            || accepts(before, "nsf_fee_method", "nsf_fee_amount", method)
                            || accepts(before, "rule_violation_fee_method", "rule_violation_fee_amount", method);

            assertTrue(usableSomewhere,
                    "FeeMethod." + method + " is not accepted on any fee column -- "
                            + "either wire it into a vocabulary or drop it from the enum");
        }
    }

    // ── Vocabulary helpers ───────────────────────────────────────────────────

    private void stubFor(TermsTemplateRow before) {
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        // lenient: a rejected method throws before patch is ever reached
        lenient().when(termsTemplateRepository.patch(eq(before.uuid()), any()))
                .thenReturn(Optional.of(before));
    }

    /** Does the service let this method onto this column? */
    private boolean accepts(TermsTemplateRow before, String methodColumn, String amountColumn, FeeMethod method) {
        Map<String, Object> changes = new HashMap<>();
        changes.put(methodColumn, method.name());
        if (method != FeeMethod.NONE) {
            changes.put(amountColumn, new BigDecimal("10.00"));
        }
        try {
            termsTemplateService.patchTermsTemplate(before.uuid(), changes);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ── Reads and delete ─────────────────────────────────────────────────────

    @Test
    void findForProperty_shouldReturnEmpty_whenThePropertyHasNoSetForThatType() {
        // Arrange
        UUID property = UUID.randomUUID();
        when(termsTemplateRepository.findByPropertyAndAgreementType(property, AgreementType.STORAGE))
                .thenReturn(Optional.empty());

        // Act
        Optional<TermsTemplate> result =
                termsTemplateService.findForProperty(property, AgreementType.STORAGE);

        // Assert -- no set copied in means the property may not offer that agreement
        assertTrue(result.isEmpty());
    }

    @Test
    void findGlobalTemplates_shouldMapEveryRow() {
        // Arrange
        when(termsTemplateRepository.findGlobalTemplates()).thenReturn(List.of(
                template(UUID.randomUUID(), "Standard Manufactured Home Lot Terms", AgreementType.LAND),
                template(UUID.randomUUID(), "Standard Residential Terms", AgreementType.RESIDENTIAL)));

        // Act
        List<TermsTemplate> result = termsTemplateService.findGlobalTemplates();

        // Assert
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(t -> t.property() == null));
    }

    @Test
    void deleteTerms_Template_shouldReturnTrue_andRecordAudit_whenSetExists() {
        // Arrange
        TermsTemplateRow stubRow = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findById(stubRow.uuid())).thenReturn(Optional.of(stubRow));
        when(termsTemplateRepository.softDelete(stubRow.uuid())).thenReturn(true);

        // Act
        boolean result = termsTemplateService.deleteTermsTemplate(stubRow.uuid());

        // Assert
        assertTrue(result);
        verify(auditService).recordDelete(eq("terms_template"), eq(stubRow.uuid()), any());
    }

    @Test
    void deleteTerms_Template_shouldReturnFalse_andNotRecordAudit_whenSetDoesNotExist() {
        // Arrange
        UUID unknownUUID = UUID.randomUUID();
        when(termsTemplateRepository.findById(unknownUUID)).thenReturn(Optional.empty());

        // Act
        boolean result = termsTemplateService.deleteTermsTemplate(unknownUUID);

        // Assert
        assertFalse(result);
        verify(termsTemplateRepository, never()).softDelete(any());
        verifyNoInteractions(auditService);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /** A global template carrying the V1 defaults, as the database would return it. */
    private static TermsTemplateRow template(UUID uuid, String name, AgreementType agreementType) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new TermsTemplateRow(
                uuid, null, null, name, agreementType, null, null,
                new BigDecimal("45.00"), 2, 4,
                new BigDecimal("45.00"), 2,
                1, 7,
                FeeMethod.FLAT, new BigDecimal("65.00"),
                FeeMethod.FLAT, new BigDecimal("35.00"),
                FeeMethod.FLAT, new BigDecimal("65.00"),
                UtilityMethod.NONE, BigDecimal.ZERO,
                UtilityMethod.NONE, BigDecimal.ZERO,
                UtilityMethod.NONE, BigDecimal.ZERO,
                UtilityMethod.NONE, BigDecimal.ZERO,
                SecurityDepositMethod.NONE, BigDecimal.ZERO,
                null, now, now, SystemPrincipal.AGENT_UUID, null);
    }

    private static TermsTemplateRow withUpdatedAt(TermsTemplateRow row, OffsetDateTime updatedAt) {
        return new TermsTemplateRow(
                row.uuid(), row.property(), row.copiedFrom(), row.name(), row.agreementType(),
                row.targetRate(), row.askingRate(),
                row.carFee(), row.allowedCars(), row.carsMax(), row.petFee(), row.allowedPets(),
                row.paymentDueDay(), row.gracePeriodDays(),
                row.ruleViolationFeeMethod(), row.ruleViolationFeeAmount(),
                row.nsfFeeMethod(), row.nsfFeeAmount(),
                row.lateFeeMethod(), row.lateFeeAmount(),
                row.waterMethod(), row.waterFlatAmount(),
                row.powerMethod(), row.powerFlatAmount(),
                row.sewerMethod(), row.sewerFlatAmount(),
                row.trashMethod(), row.trashFlatAmount(),
                row.securityDepositMethod(), row.securityDepositAmount(),
                row.note(), row.createdAt(), updatedAt, row.createdBy(), row.deletedAt());
    }

    private static TermsTemplateRow withTargetRate(TermsTemplateRow row, BigDecimal targetRate) {
        return new TermsTemplateRow(
                row.uuid(), row.property(), row.copiedFrom(), row.name(), row.agreementType(),
                targetRate, row.askingRate(),
                row.carFee(), row.allowedCars(), row.carsMax(), row.petFee(), row.allowedPets(),
                row.paymentDueDay(), row.gracePeriodDays(),
                row.ruleViolationFeeMethod(), row.ruleViolationFeeAmount(),
                row.nsfFeeMethod(), row.nsfFeeAmount(),
                row.lateFeeMethod(), row.lateFeeAmount(),
                row.waterMethod(), row.waterFlatAmount(),
                row.powerMethod(), row.powerFlatAmount(),
                row.sewerMethod(), row.sewerFlatAmount(),
                row.trashMethod(), row.trashFlatAmount(),
                row.securityDepositMethod(), row.securityDepositAmount(),
                row.note(), row.createdAt(), row.updatedAt(), row.createdBy(), row.deletedAt());
    }

    private static TermsTemplateRow withSecurityDeposit(
            TermsTemplateRow row, SecurityDepositMethod method, BigDecimal amount) {
        return new TermsTemplateRow(
                row.uuid(), row.property(), row.copiedFrom(), row.name(), row.agreementType(),
                row.targetRate(), row.askingRate(),
                row.carFee(), row.allowedCars(), row.carsMax(), row.petFee(), row.allowedPets(),
                row.paymentDueDay(), row.gracePeriodDays(),
                row.ruleViolationFeeMethod(), row.ruleViolationFeeAmount(),
                row.nsfFeeMethod(), row.nsfFeeAmount(),
                row.lateFeeMethod(), row.lateFeeAmount(),
                row.waterMethod(), row.waterFlatAmount(),
                row.powerMethod(), row.powerFlatAmount(),
                row.sewerMethod(), row.sewerFlatAmount(),
                row.trashMethod(), row.trashFlatAmount(),
                method, amount,
                row.note(), row.createdAt(), row.updatedAt(), row.createdBy(), row.deletedAt());
    }

    // ── Security deposit ─────────────────────────────────────────────────────

    @Test
    void patchTermsTemplate_shouldKeepTheMultiplier_whenMethodIsMultipleOfRent() {
        // Arrange -- a multiplier is an amount; it must survive reconciliation
        TermsTemplateRow before = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(termsTemplateRepository.patch(eq(before.uuid()), any())).thenReturn(Optional.of(before));

        // Act
        Map<String, Object> changes = new HashMap<>();
        changes.put("security_deposit_method", "MULTIPLE_OF_RENT");
        changes.put("security_deposit_amount", new BigDecimal("1.00"));
        termsTemplateService.patchTermsTemplate(before.uuid(), changes);

        // Assert -- zeroing this would fail the amount-matches-method CHECK
        verify(termsTemplateRepository).patch(eq(before.uuid()), argThat(
                map -> new BigDecimal("1.00")
                        .compareTo((BigDecimal) map.get("security_deposit_amount")) == 0));
    }

    @Test
    void patchTermsTemplate_shouldZeroTheDeposit_whenMethodBecomesNone() {
        // Arrange -- a land lease that stops taking deposits
        TermsTemplateRow before = withSecurityDeposit(
                template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND),
                SecurityDepositMethod.FLAT, new BigDecimal("500.00"));
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(termsTemplateRepository.patch(eq(before.uuid()), any())).thenReturn(Optional.of(before));

        // Act
        Map<String, Object> changes = new HashMap<>();
        changes.put("security_deposit_method", "NONE");
        termsTemplateService.patchTermsTemplate(before.uuid(), changes);

        // Assert
        verify(termsTemplateRepository).patch(eq(before.uuid()), argThat(
                map -> BigDecimal.ZERO.equals(map.get("security_deposit_amount"))));
    }

    @Test
    void patchTermsTemplate_shouldKeepTheExistingAmount_whenOnlyTheDepositMethodIsPatched() {
        // Arrange -- before already has a FLAT deposit of 500
        TermsTemplateRow before = withSecurityDeposit(
                template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND),
                SecurityDepositMethod.FLAT, new BigDecimal("500.00"));
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(termsTemplateRepository.patch(eq(before.uuid()), any())).thenReturn(Optional.of(before));

        // Act
        Map<String, Object> changes = new HashMap<>();
        changes.put("security_deposit_method", "FLAT");
        termsTemplateService.patchTermsTemplate(before.uuid(), changes);

        // Assert
        verify(termsTemplateRepository).patch(eq(before.uuid()), argThat(
                map -> new BigDecimal("500.00")
                        .compareTo((BigDecimal) map.get("security_deposit_amount")) == 0));
    }

    @Test
    void patchTermsTemplate_shouldThrow_whenMultipleOfRentIsGivenZero() {
        // Arrange
        TermsTemplateRow before = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));

        // Act / Assert
        Map<String, Object> changes = new HashMap<>();
        changes.put("security_deposit_method", "MULTIPLE_OF_RENT");
        changes.put("security_deposit_amount", BigDecimal.ZERO);
        assertThrows(IllegalArgumentException.class,
                () -> termsTemplateService.patchTermsTemplate(before.uuid(), changes));
        verify(termsTemplateRepository, never()).patch(any(), any());
    }

    @Test
    void patchTermsTemplate_shouldThrow_whenDepositIsGivenAFeeMethod() {
        // Arrange -- PERCENT_OF_RENT is a real FeeMethod, but not a deposit method
        TermsTemplateRow before = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));

        // Act / Assert
        Map<String, Object> changes = new HashMap<>();
        changes.put("security_deposit_method", "PERCENT_OF_RENT");
        assertThrows(IllegalArgumentException.class,
                () -> termsTemplateService.patchTermsTemplate(before.uuid(), changes));
        verify(termsTemplateRepository, never()).patch(any(), any());
    }

    @Test
    void patchTermsTemplate_shouldThrow_whenMethodIsExplicitlyNull() {
        // Arrange -- Set.of(...).contains(null) throws, so this must be caught first
        TermsTemplateRow before = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));

        // Act / Assert
        Map<String, Object> changes = new HashMap<>();
        changes.put("security_deposit_method", null);
        assertThrows(IllegalArgumentException.class,
                () -> termsTemplateService.patchTermsTemplate(before.uuid(), changes));
        verify(termsTemplateRepository, never()).patch(any(), any());
    }

    @Test
    void copyTemplateToProperty_shouldCarryTheDepositTerms() {
        // Arrange -- residential asks one month's rent; the copy must inherit it
        UUID property = UUID.randomUUID();
        TermsTemplateRow global = withSecurityDeposit(
                template(UUID.randomUUID(), "Standard Residential Terms", AgreementType.RESIDENTIAL),
                SecurityDepositMethod.MULTIPLE_OF_RENT, new BigDecimal("1.00"));
        when(termsTemplateRepository.findById(global.uuid())).thenReturn(Optional.of(global));
        when(termsTemplateRepository.findByPropertyAndAgreementType(property, AgreementType.RESIDENTIAL))
                .thenReturn(Optional.empty());
        when(termsTemplateRepository.saveCopy(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        termsTemplateService.copyTemplateToProperty(global.uuid(), property);

        // Assert
        verify(termsTemplateRepository).saveCopy(argThat(row ->
                row.securityDepositMethod() == SecurityDepositMethod.MULTIPLE_OF_RENT
                        && new BigDecimal("1.00").compareTo(row.securityDepositAmount()) == 0));
    }
}