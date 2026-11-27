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
import java.util.Set;
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
        TermsTemplate result =
                termsTemplateService.createGlobalTemplate("WA Land Lease 2026", AgreementType.LAND, "WA");

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
        termsTemplateService.createGlobalTemplate("WA Land Lease 2026", AgreementType.LAND, "WA");

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
                () -> termsTemplateService.createGlobalTemplate("WA Land Lease 2026", AgreementType.LAND, "WA"));
        verify(termsTemplateRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void createGlobalTemplate_shouldRefuse_whenNoStateIsGiven() {
        // Act -- the state comes first: the state's law decides what the template may hold
        InvalidRequest refused = assertThrows(InvalidRequest.class,
                () -> termsTemplateService.createGlobalTemplate("Land Lease 2026", AgreementType.LAND, " "));

        // Assert
        assertEquals("value.required", refused.problem().code());
        assertEquals("state", refused.problem().field());
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
        when(termsTemplateRepository.findPropertyState(property)).thenReturn(Optional.of("WA"));
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
        assertTrue(accepts(before, "late_fee_method", "late_fee_amount", FeeMethod.TIERED));
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
        assertFalse(accepts(before, "nsf_fee_method", "nsf_fee_amount", FeeMethod.TIERED),
                "an NSF fee does not depend on the day the payment arrives");
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
        assertFalse(accepts(before, "rule_violation_fee_method", "rule_violation_fee_amount", FeeMethod.TIERED));
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

    // ── Late fee tiers ───────────────────────────────────────────────────────

    @Test
    void patchTermsTemplate_shouldStoreTheChart_andZeroTheFlatAmount() {
        // Arrange -- before has a FLAT late fee of 65, due on the 1st with 7 grace days
        TermsTemplateRow before = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(termsTemplateRepository.patch(eq(before.uuid()), any())).thenReturn(Optional.of(before));

        Map<String, Object> changes = new HashMap<>();
        changes.put("late_fee_method", "TIERED");
        changes.put("late_fee_tiers", List.of(
                Map.of("fromDay", 9, "amount", 20),
                Map.of("fromDay", 25, "amount", 60)));

        // Act
        termsTemplateService.patchTermsTemplate(before.uuid(), changes);

        // Assert
        verify(termsTemplateRepository).patch(eq(before.uuid()), argThat(map ->
                BigDecimal.ZERO.equals(map.get("late_fee_amount"))
                        && List.of(
                                new LateFeeTier(9, new BigDecimal("20.00")),
                                new LateFeeTier(25, new BigDecimal("60.00")))
                        .equals(map.get("late_fee_tiers"))));
    }

    @Test
    void patchTermsTemplate_shouldRefuseTieredWithNoChart() {
        // Arrange -- unlike a charge term, a template cannot hold a half-done chart
        TermsTemplateRow before = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));

        Map<String, Object> changes = new HashMap<>();
        changes.put("late_fee_method", "TIERED");

        // Act
        InvalidRequest refused = assertThrows(InvalidRequest.class,
                () -> termsTemplateService.patchTermsTemplate(before.uuid(), changes));

        // Assert
        assertEquals("late_fee_tier.chart_not_valid", refused.problem().code());
        assertEquals("late_fee_tier.chart_required", refused.details().getFirst().code());
        verify(termsTemplateRepository, never()).patch(any(), any());
    }

    @Test
    void patchTermsTemplate_shouldRefuseALongerGracePeriod_thatSwallowsTheFirstTier() {
        // Arrange -- a tiered template whose first tier starts on the 9th
        TermsTemplateRow before = withLateFee(
                template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND),
                FeeMethod.TIERED, BigDecimal.ZERO,
                List.of(new LateFeeTier(9, new BigDecimal("20.00"))));
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));

        Map<String, Object> changes = new HashMap<>();
        changes.put("grace_period_days", 10);

        // Act -- due on the 1st plus 10 grace days makes the 11th the last free day
        InvalidRequest refused = assertThrows(InvalidRequest.class,
                () -> termsTemplateService.patchTermsTemplate(before.uuid(), changes));

        // Assert
        assertEquals("late_fee_tier.inside_grace", refused.details().getFirst().code());
        verify(termsTemplateRepository, never()).patch(any(), any());
    }

    @Test
    void patchTermsTemplate_shouldEmptyTheChart_whenTheMethodStopsBeingTiered() {
        // Arrange
        TermsTemplateRow before = withLateFee(
                template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND),
                FeeMethod.TIERED, BigDecimal.ZERO,
                List.of(new LateFeeTier(9, new BigDecimal("20.00"))));
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(termsTemplateRepository.patch(eq(before.uuid()), any())).thenReturn(Optional.of(before));

        Map<String, Object> changes = new HashMap<>();
        changes.put("late_fee_method", "FLAT");
        changes.put("late_fee_amount", new BigDecimal("65.00"));

        // Act
        termsTemplateService.patchTermsTemplate(before.uuid(), changes);

        // Assert
        verify(termsTemplateRepository).patch(eq(before.uuid()), argThat(
                map -> List.of().equals(map.get("late_fee_tiers"))));
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
        if (method == FeeMethod.TIERED) {
            // A template has no draft status, so TIERED needs its chart in the same patch
            changes.put("late_fee_tiers", List.of(Map.of("fromDay", 9, "amount", 20)));
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

    // ── State law: payment order and Counts as rent ──────────────────────────

    @Test
    void patchTermsTemplate_shouldRefuseALateFeeAsRent_onAWashingtonDeal() {
        // Arrange -- a property's deal follows the law of its own state, set when the deal was made
        TermsTemplateRow before = atProperty(
                template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND), UUID.randomUUID());
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));

        Map<String, Object> changes = new HashMap<>();
        changes.put("rent_kinds", List.of("RENT", "LATE_FEE"));

        // Act
        InvalidRequest refused = assertThrows(InvalidRequest.class,
                () -> termsTemplateService.patchTermsTemplate(before.uuid(), changes));

        // Assert -- RCW 59.20.030(25) keeps late fees out of rent
        assertEquals("rent_kinds.not_allowed_in_state", refused.problem().code());
        assertEquals("rent_kinds", refused.problem().field());
        verify(termsTemplateRepository, never()).patch(any(), any());
    }

    @Test
    void patchTermsTemplate_shouldAcceptFeesFirstAndLateFeesAsRent_onAMontanaDeal() {
        // Arrange -- Montana counts every charge as rent and allows either payment order
        TermsTemplateRow before = atProperty(
                template(UUID.randomUUID(), "MT Land Lease 2026", AgreementType.LAND), UUID.randomUUID())
                .withRentRules("MT", List.of(ChargeKind.RENT), PaymentOrder.RENT_FIRST);
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(termsTemplateRepository.patch(eq(before.uuid()), any())).thenReturn(Optional.of(before));

        Map<String, Object> changes = new HashMap<>();
        changes.put("rent_kinds", List.of("late_fee", "RENT"));
        changes.put("payment_order", "fees_first");

        // Act
        termsTemplateService.patchTermsTemplate(before.uuid(), changes);

        // Assert -- the repository is handed the kinds as read, in declaration order, and the order's name
        verify(termsTemplateRepository).patch(eq(before.uuid()), argThat(map ->
                List.of(ChargeKind.RENT, ChargeKind.LATE_FEE).equals(map.get("rent_kinds"))
                        && "FEES_FIRST".equals(map.get("payment_order"))));
    }

    @Test
    void copyTemplateToProperty_shouldKeepTheTemplatesState_andItsRentRules() {
        // Arrange -- a Montana template, as the MT packet reads: every charge is rent, fees first
        UUID property = UUID.randomUUID();
        TermsTemplateRow templateRow = template(UUID.randomUUID(), "MT Land Lease 2026", AgreementType.LAND)
                .withRentRules("MT", List.of(ChargeKind.values()), PaymentOrder.FEES_FIRST);
        when(termsTemplateRepository.findById(templateRow.uuid())).thenReturn(Optional.of(templateRow));
        when(termsTemplateRepository.findByPropertyAndAgreementType(property, AgreementType.LAND))
                .thenReturn(Optional.empty());
        when(termsTemplateRepository.findPropertyState(property)).thenReturn(Optional.of("MT"));
        when(termsTemplateRepository.saveCopy(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        termsTemplateService.copyTemplateToProperty(templateRow.uuid(), property);

        // Assert -- the deal's state is set here, from the template, and is never patched afterwards
        verify(termsTemplateRepository).saveCopy(argThat(copy ->
                "MT".equals(copy.state())
                        && copy.paymentOrder() == PaymentOrder.FEES_FIRST
                        && copy.rentKinds().equals(List.of(ChargeKind.values()))));
    }

    @Test
    void copyTemplateToProperty_shouldRefuse_whenTheTemplateIsWrittenForAnotherState() {
        // Arrange -- a Washington template and a Montana property
        UUID property = UUID.randomUUID();
        TermsTemplateRow templateRow = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findById(templateRow.uuid())).thenReturn(Optional.of(templateRow));
        when(termsTemplateRepository.findByPropertyAndAgreementType(property, AgreementType.LAND))
                .thenReturn(Optional.empty());
        when(termsTemplateRepository.findPropertyState(property)).thenReturn(Optional.of("MT"));

        // Act
        RuleConflict refused = assertThrows(RuleConflict.class,
                () -> termsTemplateService.copyTemplateToProperty(templateRow.uuid(), property));

        // Assert
        assertEquals("terms_template.other_state", refused.problem().code());
        verify(termsTemplateRepository, never()).saveCopy(any());
    }

    @Test
    void createGlobalTemplateFrom_shouldStartOver_whenTheNewTemplateIsForAnotherState() {
        // Arrange -- a Montana deal: every charge counts as rent, fees first
        TermsTemplateRow montanaDeal = atProperty(
                template(UUID.randomUUID(), "MT Land Lease 2026", AgreementType.LAND), UUID.randomUUID())
                .withRentRules("MT", List.of(ChargeKind.values()), PaymentOrder.FEES_FIRST);
        when(termsTemplateRepository.findById(montanaDeal.uuid())).thenReturn(Optional.of(montanaDeal));
        when(termsTemplateRepository.saveCopy(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        termsTemplateService.createGlobalTemplateFrom(
                "WA Land From MT", AgreementType.LAND, montanaDeal.uuid(), "WA");

        // Assert -- the Montana choices were made under another law, so the template takes Washington's
        verify(termsTemplateRepository).saveCopy(argThat(copy ->
                "WA".equals(copy.state())
                        && copy.paymentOrder() == PaymentOrder.RENT_FIRST
                        && copy.rentKinds().equals(List.of(ChargeKind.RENT))));
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /** A global Washington template carrying the V1 defaults, as the database would return it. */
    private static TermsTemplateRow template(UUID uuid, String name, AgreementType agreementType) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new TermsTemplateRow(
                uuid, null, null, name, agreementType, null, null,
                null, null,
                new BigDecimal("45.00"), 2, 4,
                new BigDecimal("45.00"), 2,
                1, 7,
                FeeMethod.FLAT, new BigDecimal("65.00"),             // rule violation
                FeeMethod.FLAT, new BigDecimal("35.00"),             // nsf
                FeeMethod.FLAT, new BigDecimal("65.00"), List.of(),  // late fee and its tiers
                UtilityMethod.NONE, BigDecimal.ZERO,
                UtilityMethod.NONE, BigDecimal.ZERO,
                UtilityMethod.NONE, BigDecimal.ZERO,
                UtilityMethod.NONE, BigDecimal.ZERO,
                SecurityDepositMethod.NONE, BigDecimal.ZERO,
                PaymentOrder.RENT_FIRST, List.of(ChargeKind.RENT), RentStarts.NEXT_FIRST, "WA", // payment order, rent kinds, rent starts, state
                null, now, now, SystemPrincipal.AGENT_UUID, null);
    }

    private static TermsTemplateRow withUpdatedAt(TermsTemplateRow row, OffsetDateTime updatedAt) {
        return new TermsTemplateRow(
                row.uuid(), row.property(), row.copiedFrom(), row.name(), row.agreementType(),
                row.targetRate(), row.askingRate(),
                row.escalationPercent(), row.escalationMonths(),
                row.carFee(), row.allowedCars(), row.carsMax(), row.petFee(), row.allowedPets(),
                row.paymentDueDay(), row.gracePeriodDays(),
                row.ruleViolationFeeMethod(), row.ruleViolationFeeAmount(),
                row.nsfFeeMethod(), row.nsfFeeAmount(),
                row.lateFeeMethod(), row.lateFeeAmount(), row.lateFeeTiers(),
                row.waterMethod(), row.waterFlatAmount(),
                row.powerMethod(), row.powerFlatAmount(),
                row.sewerMethod(), row.sewerFlatAmount(),
                row.trashMethod(), row.trashFlatAmount(),
                row.securityDepositMethod(), row.securityDepositAmount(),
                row.paymentOrder(), row.rentKinds(), row.rentStarts(), row.state(),
                row.note(), row.createdAt(), updatedAt, row.createdBy(), row.deletedAt());
    }

    private static TermsTemplateRow withTargetRate(TermsTemplateRow row, BigDecimal targetRate) {
        return new TermsTemplateRow(
                row.uuid(), row.property(), row.copiedFrom(), row.name(), row.agreementType(),
                targetRate, row.askingRate(),
                row.escalationPercent(), row.escalationMonths(),
                row.carFee(), row.allowedCars(), row.carsMax(), row.petFee(), row.allowedPets(),
                row.paymentDueDay(), row.gracePeriodDays(),
                row.ruleViolationFeeMethod(), row.ruleViolationFeeAmount(),
                row.nsfFeeMethod(), row.nsfFeeAmount(),
                row.lateFeeMethod(), row.lateFeeAmount(), row.lateFeeTiers(),
                row.waterMethod(), row.waterFlatAmount(),
                row.powerMethod(), row.powerFlatAmount(),
                row.sewerMethod(), row.sewerFlatAmount(),
                row.trashMethod(), row.trashFlatAmount(),
                row.securityDepositMethod(), row.securityDepositAmount(),
                row.paymentOrder(), row.rentKinds(), row.rentStarts(), row.state(),
                row.note(), row.createdAt(), row.updatedAt(), row.createdBy(), row.deletedAt());
    }

    private static TermsTemplateRow withLateFee(
            TermsTemplateRow row, FeeMethod method, BigDecimal amount, List<LateFeeTier> tiers) {
        return new TermsTemplateRow(
                row.uuid(), row.property(), row.copiedFrom(), row.name(), row.agreementType(),
                row.targetRate(), row.askingRate(),
                row.escalationPercent(), row.escalationMonths(),
                row.carFee(), row.allowedCars(), row.carsMax(), row.petFee(), row.allowedPets(),
                row.paymentDueDay(), row.gracePeriodDays(),
                row.ruleViolationFeeMethod(), row.ruleViolationFeeAmount(),
                row.nsfFeeMethod(), row.nsfFeeAmount(),
                method, amount, tiers,
                row.waterMethod(), row.waterFlatAmount(),
                row.powerMethod(), row.powerFlatAmount(),
                row.sewerMethod(), row.sewerFlatAmount(),
                row.trashMethod(), row.trashFlatAmount(),
                row.securityDepositMethod(), row.securityDepositAmount(),
                row.paymentOrder(), row.rentKinds(), row.rentStarts(), row.state(),
                row.note(), row.createdAt(), row.updatedAt(), row.createdBy(), row.deletedAt());
    }

    private static TermsTemplateRow withSecurityDeposit(
            TermsTemplateRow row, SecurityDepositMethod method, BigDecimal amount) {
        return new TermsTemplateRow(
                row.uuid(), row.property(), row.copiedFrom(), row.name(), row.agreementType(),
                row.targetRate(), row.askingRate(),
                row.escalationPercent(), row.escalationMonths(),
                row.carFee(), row.allowedCars(), row.carsMax(), row.petFee(), row.allowedPets(),
                row.paymentDueDay(), row.gracePeriodDays(),
                row.ruleViolationFeeMethod(), row.ruleViolationFeeAmount(),
                row.nsfFeeMethod(), row.nsfFeeAmount(),
                row.lateFeeMethod(), row.lateFeeAmount(), row.lateFeeTiers(),
                row.waterMethod(), row.waterFlatAmount(),
                row.powerMethod(), row.powerFlatAmount(),
                row.sewerMethod(), row.sewerFlatAmount(),
                row.trashMethod(), row.trashFlatAmount(),
                method, amount,
                row.paymentOrder(), row.rentKinds(), row.rentStarts(), row.state(),
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
        when(termsTemplateRepository.findPropertyState(property)).thenReturn(Optional.of("WA"));
        when(termsTemplateRepository.saveCopy(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        termsTemplateService.copyTemplateToProperty(global.uuid(), property);

        // Assert
        verify(termsTemplateRepository).saveCopy(argThat(row ->
                row.securityDepositMethod() == SecurityDepositMethod.MULTIPLE_OF_RENT
                        && new BigDecimal("1.00").compareTo(row.securityDepositAmount()) == 0));
    }

    // ── Property scope ───────────────────────────────────────────────────────

    @Test
    void inScope_shouldCoverAGlobalTemplate_whateverTheScope() {
        // Arrange -- a global template is tied to no property
        TermsTemplateRow global = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findAnyById(global.uuid())).thenReturn(Optional.of(global));

        // Act / Assert
        assertTrue(termsTemplateService.inScope(global.uuid(), new PropertyScope.Restricted(Set.of())));
        assertTrue(termsTemplateService.inScope(global.uuid(), new PropertyScope.All()));
    }

    @Test
    void inScope_shouldFollowTheProperty_whenTheTemplateIsAPropertysDeal() {
        // Arrange
        UUID property = UUID.randomUUID();
        TermsTemplateRow deal = atProperty(
                template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND), property);
        when(termsTemplateRepository.findAnyById(deal.uuid())).thenReturn(Optional.of(deal));

        // Act / Assert
        assertTrue(termsTemplateService.inScope(deal.uuid(), new PropertyScope.Restricted(Set.of(property))));
        assertTrue(termsTemplateService.inScope(deal.uuid(), new PropertyScope.All()));
        assertFalse(termsTemplateService.inScope(deal.uuid(),
                new PropertyScope.Restricted(Set.of(UUID.randomUUID()))), "another property's agent");
        assertFalse(termsTemplateService.inScope(deal.uuid(), new PropertyScope.Restricted(Set.of())),
                "an agent with no property");
    }

    @Test
    void inScope_shouldCoverAnUnknownTemplate_soTheCallAnswersNotFoundTheUsualWay() {
        // Arrange
        UUID unknownUUID = UUID.randomUUID();
        when(termsTemplateRepository.findAnyById(unknownUUID)).thenReturn(Optional.empty());

        // Act / Assert
        assertTrue(termsTemplateService.inScope(unknownUUID, new PropertyScope.Restricted(Set.of())));
    }

    // ── Renaming a global template ───────────────────────────────────────────

    @Test
    void patchTermsTemplate_shouldThrow_whenAGlobalTemplateTakesAnotherTemplatesName() {
        // Arrange -- a global template is found by name, so two may not share one
        TermsTemplateRow before = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        TermsTemplateRow other = template(UUID.randomUUID(), "OR Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(termsTemplateRepository.findGlobalByName("or land lease 2026")).thenReturn(Optional.of(other));

        // Act / Assert
        Map<String, Object> changes = new HashMap<>();
        changes.put("name", "or land lease 2026");
        RuleConflict refused = assertThrows(RuleConflict.class,
                () -> termsTemplateService.patchTermsTemplate(before.uuid(), changes));
        assertEquals("terms_template.name_taken", refused.problem().code());
        verify(termsTemplateRepository, never()).patch(any(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    void patchTermsTemplate_shouldAllowAGlobalTemplatesOwnName_inAnotherLetterCase() {
        // Arrange -- the name lookup would find the template itself
        TermsTemplateRow before = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(termsTemplateRepository.patch(eq(before.uuid()), any())).thenReturn(Optional.of(before));

        // Act
        Map<String, Object> changes = new HashMap<>();
        changes.put("name", "wa land lease 2026");
        termsTemplateService.patchTermsTemplate(before.uuid(), changes);

        // Assert
        verify(termsTemplateRepository, never()).findGlobalByName(any());
        verify(termsTemplateRepository).patch(eq(before.uuid()), any());
    }

    @Test
    void patchTermsTemplate_shouldLetAPropertysDealTakeAnyName() {
        // Arrange -- a deal is found by property and agreement type, never by name
        TermsTemplateRow deal = atProperty(
                template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND), UUID.randomUUID());
        when(termsTemplateRepository.findById(deal.uuid())).thenReturn(Optional.of(deal));
        when(termsTemplateRepository.patch(eq(deal.uuid()), any())).thenReturn(Optional.of(deal));

        // Act
        Map<String, Object> changes = new HashMap<>();
        changes.put("name", "OR Land Lease 2026");
        termsTemplateService.patchTermsTemplate(deal.uuid(), changes);

        // Assert
        verify(termsTemplateRepository, never()).findGlobalByName(any());
        verify(termsTemplateRepository).patch(eq(deal.uuid()), any());
    }

    // ── A new global template that starts from another ───────────────────────

    @Test
    void createGlobalTemplateFrom_shouldKeepTheTermsAndTheRates_whenTheSourceIsAGlobalTemplate() {
        // Arrange
        TermsTemplateRow source = withTargetRate(
                withSecurityDeposit(
                        template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND),
                        SecurityDepositMethod.FLAT, new BigDecimal("500.00")),
                new BigDecimal("640.00"));
        when(termsTemplateRepository.findById(source.uuid())).thenReturn(Optional.of(source));
        when(termsTemplateRepository.findGlobalByName("ID Land Lease 2026")).thenReturn(Optional.empty());
        when(termsTemplateRepository.saveCopy(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        Optional<TermsTemplate> made = termsTemplateService.createGlobalTemplateFrom(
                "ID Land Lease 2026", AgreementType.LAND, source.uuid(), "ID");

        // Assert -- a template of the template: no property, and copiedFrom stays empty as on every global
        assertTrue(made.isPresent());
        verify(termsTemplateRepository).saveCopy(argThat(row ->
                row.uuid() == null
                        && row.property() == null
                        && row.copiedFrom() == null
                        && "ID Land Lease 2026".equals(row.name())
                        && new BigDecimal("640.00").compareTo(row.targetRate()) == 0
                        && row.securityDepositMethod() == SecurityDepositMethod.FLAT
                        && new BigDecimal("500.00").compareTo(row.securityDepositAmount()) == 0));
        verify(auditService).recordInsert(eq("terms_template"), any(), any());
    }

    @Test
    void createGlobalTemplateFrom_shouldLeaveTheRatesEmpty_whenTheSourceIsAPropertysDeal() {
        // Arrange -- a property's rates belong to that property's market
        TermsTemplateRow deal = atProperty(
                withTargetRate(
                        withSecurityDeposit(
                                template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND),
                                SecurityDepositMethod.FLAT, new BigDecimal("500.00")),
                        new BigDecimal("640.00")),
                UUID.randomUUID());
        when(termsTemplateRepository.findById(deal.uuid())).thenReturn(Optional.of(deal));
        when(termsTemplateRepository.findGlobalByName("ID Land Lease 2026")).thenReturn(Optional.empty());
        when(termsTemplateRepository.saveCopy(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        termsTemplateService.createGlobalTemplateFrom(
                "ID Land Lease 2026", AgreementType.STORAGE, deal.uuid(), "ID");

        // Assert -- every term but the two rates, under the kind asked for
        verify(termsTemplateRepository).saveCopy(argThat(row ->
                row.property() == null
                        && row.copiedFrom() == null
                        && row.targetRate() == null
                        && row.askingRate() == null
                        && row.agreementType() == AgreementType.STORAGE
                        && row.securityDepositMethod() == SecurityDepositMethod.FLAT
                        && new BigDecimal("500.00").compareTo(row.securityDepositAmount()) == 0));
    }

    @Test
    void createGlobalTemplateFrom_shouldReturnEmpty_whenTheSourceDoesNotExist() {
        // Arrange
        UUID unknownUUID = UUID.randomUUID();
        when(termsTemplateRepository.findById(unknownUUID)).thenReturn(Optional.empty());

        // Act
        Optional<TermsTemplate> made = termsTemplateService.createGlobalTemplateFrom(
                "ID Land Lease 2026", AgreementType.LAND, unknownUUID, "ID");

        // Assert
        assertTrue(made.isEmpty());
        verify(termsTemplateRepository, never()).saveCopy(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void createGlobalTemplateFrom_shouldThrow_whenTheNameIsAlreadyTaken() {
        // Arrange
        TermsTemplateRow source = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        TermsTemplateRow existing = template(UUID.randomUUID(), "OR Land Lease 2026", AgreementType.LAND);
        when(termsTemplateRepository.findById(source.uuid())).thenReturn(Optional.of(source));
        when(termsTemplateRepository.findGlobalByName("OR Land Lease 2026")).thenReturn(Optional.of(existing));

        // Act / Assert
        assertThrows(IllegalStateException.class, () -> termsTemplateService.createGlobalTemplateFrom(
                "OR Land Lease 2026", AgreementType.LAND, source.uuid(), "OR"));
        verify(termsTemplateRepository, never()).saveCopy(any());
        verifyNoInteractions(auditService);
    }

    // ── The same changes to several templates ────────────────────────────────

    @Test
    void patchTermsTemplates_shouldPatchEachTemplateOnce_andLeaveTheCallersChangesAlone() {
        // Arrange -- a global template and a property's deal, with the first named twice
        TermsTemplateRow first = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        TermsTemplateRow second = atProperty(
                template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND), UUID.randomUUID());
        when(termsTemplateRepository.findById(first.uuid())).thenReturn(Optional.of(first));
        when(termsTemplateRepository.findById(second.uuid())).thenReturn(Optional.of(second));
        when(termsTemplateRepository.patch(eq(first.uuid()), any())).thenReturn(Optional.of(first));
        when(termsTemplateRepository.patch(eq(second.uuid()), any())).thenReturn(Optional.of(second));

        Map<String, Object> changes = new HashMap<>();
        changes.put("late_fee_method", "PERCENT_OF_RENT");
        changes.put("late_fee_amount", new BigDecimal("1.50"));
        Map<String, Object> asSent = new HashMap<>(changes);

        // Act
        List<TermsTemplate> patched = termsTemplateService.patchTermsTemplates(
                List.of(first.uuid(), second.uuid(), first.uuid()), changes);

        // Assert
        assertEquals(2, patched.size());
        verify(termsTemplateRepository, times(1)).patch(eq(first.uuid()), any());
        verify(termsTemplateRepository, times(1)).patch(eq(second.uuid()), any());
        // Patching one template writes that template's own reading into the map (here: an empty late fee
        // chart). Each template must get a copy, or the first template's reading would reach the second.
        assertEquals(asSent, changes, "the caller's changes must not be written into");
    }

    @Test
    void patchTermsTemplates_shouldThrow_whenATemplateIsGone() {
        // Arrange
        TermsTemplateRow first = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        UUID goneUUID = UUID.randomUUID();
        when(termsTemplateRepository.findById(first.uuid())).thenReturn(Optional.of(first));
        when(termsTemplateRepository.patch(eq(first.uuid()), any())).thenReturn(Optional.of(first));
        when(termsTemplateRepository.findById(goneUUID)).thenReturn(Optional.empty());

        // Act / Assert -- the transaction then undoes the first template (TermsTemplateManyIT)
        Map<String, Object> changes = new HashMap<>();
        changes.put("grace_period_days", 5);
        RuleConflict refused = assertThrows(RuleConflict.class,
                () -> termsTemplateService.patchTermsTemplates(List.of(first.uuid(), goneUUID), changes));
        assertEquals("terms_template.gone", refused.problem().code());
    }

    @Test
    void patchTermsTemplates_shouldStopAtTheTemplateTheChangesAreRefusedFor() {
        // Arrange -- the first template has a flat late fee to keep; the second has no late fee,
        // so "flat" with no amount is refused for the second
        TermsTemplateRow first = template(UUID.randomUUID(), "WA Land Lease 2026", AgreementType.LAND);
        TermsTemplateRow second = withLateFee(
                template(UUID.randomUUID(), "OR Land Lease 2026", AgreementType.LAND),
                FeeMethod.NONE, BigDecimal.ZERO, List.of());
        when(termsTemplateRepository.findById(first.uuid())).thenReturn(Optional.of(first));
        when(termsTemplateRepository.findById(second.uuid())).thenReturn(Optional.of(second));
        when(termsTemplateRepository.patch(eq(first.uuid()), any())).thenReturn(Optional.of(first));

        // Act / Assert -- the transaction then undoes the first template (TermsTemplateManyIT)
        Map<String, Object> changes = new HashMap<>();
        changes.put("late_fee_method", "FLAT");
        assertThrows(IllegalArgumentException.class,
                () -> termsTemplateService.patchTermsTemplates(List.of(first.uuid(), second.uuid()), changes));
        verify(termsTemplateRepository, never()).patch(eq(second.uuid()), any());
    }

    /** The same row as a property's deal: every value kept, the row tied to the property. */
    private static TermsTemplateRow atProperty(TermsTemplateRow row, UUID property) {
        return new TermsTemplateRow(
                row.uuid(), property, row.copiedFrom(), row.name(), row.agreementType(),
                row.targetRate(), row.askingRate(),
                row.escalationPercent(), row.escalationMonths(),
                row.carFee(), row.allowedCars(), row.carsMax(), row.petFee(), row.allowedPets(),
                row.paymentDueDay(), row.gracePeriodDays(),
                row.ruleViolationFeeMethod(), row.ruleViolationFeeAmount(),
                row.nsfFeeMethod(), row.nsfFeeAmount(),
                row.lateFeeMethod(), row.lateFeeAmount(), row.lateFeeTiers(),
                row.waterMethod(), row.waterFlatAmount(),
                row.powerMethod(), row.powerFlatAmount(),
                row.sewerMethod(), row.sewerFlatAmount(),
                row.trashMethod(), row.trashFlatAmount(),
                row.securityDepositMethod(), row.securityDepositAmount(),
                row.paymentOrder(), row.rentKinds(), row.rentStarts(), row.state(),
                row.note(), row.createdAt(), row.updatedAt(), row.createdBy(), row.deletedAt());
    }
}