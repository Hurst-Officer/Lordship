package io.github.lordship.tenancyterms;

import io.github.lordship.audit.AuditContext;
import io.github.lordship.audit.AuditService;
import io.github.lordship.lots.Lot;
import io.github.lordship.lots.LotService;
import io.github.lordship.lots.PermissibleAgreementType;
import io.github.lordship.shared.*;
import io.github.lordship.tenancy.Tenancy;
import io.github.lordship.tenancy.TenancyService;
import io.github.lordship.tenancyterms.internal.TenancyChargeTermRepository;
import io.github.lordship.tenancyterms.internal.TenancyChargeTermRow;
import io.github.lordship.termstemplate.TermsTemplate;
import io.github.lordship.termstemplate.TermsTemplateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
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
public class TenancyChargeTermServiceTest {

    @Mock
    TenancyChargeTermRepository tenancyChargeTermRepository;

    @Mock
    TenancyService tenancyService;

    @Mock
    LotService lotService;

    @Mock
    TermsTemplateService termsTemplateService;

    @Mock
    AuditService auditService;

    @Mock
    AuditContext auditContext;

    @InjectMocks
    TenancyChargeTermService tenancyChargeTermService;

    private static final UUID TENANCY = UUID.randomUUID();
    private static final UUID LOT = UUID.randomUUID();
    private static final UUID PROPERTY = UUID.randomUUID();
    private static final UUID TEMPLATE = UUID.randomUUID();
    private static final LocalDate VALID_AT = LocalDate.of(2026, 9, 1);

    // ---- createFromTemplate --------------------------------------------------

    @Test
    void createFromTemplate_shouldReturnEmpty_whenTenancyNotFound() {
        // Arrange
        when(tenancyService.findTenancyById(TENANCY)).thenReturn(Optional.empty());

        // Act
        Optional<TenancyChargeTerm> result = tenancyChargeTermService.createFromTemplate(
                TENANCY, AgreementType.LAND, VALID_AT, TenancyTermSource.LEASE, null);

        // Assert
        assertTrue(result.isEmpty());
        verify(tenancyChargeTermRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void createFromTemplate_shouldThrow_whenPropertyHasNoTemplateForThatAgreementType() {
        // Arrange -- copying a template into a property is what authorizes that
        // property to offer the agreement type at all
        when(tenancyService.findTenancyById(TENANCY)).thenReturn(Optional.of(tenancy()));
        when(lotService.findById(LOT)).thenReturn(Optional.of(
                lot(new PermissibleAgreementType(AgreementType.STORAGE, null, null))));
        when(termsTemplateService.findForProperty(PROPERTY, AgreementType.STORAGE))
                .thenReturn(Optional.empty());

        // Act / Assert
        assertThrows(IllegalStateException.class, () -> tenancyChargeTermService.createFromTemplate(
                TENANCY, AgreementType.STORAGE, VALID_AT, TenancyTermSource.LEASE, null));
        verify(tenancyChargeTermRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void createFromTemplate_shouldThrow_whenTheLotDoesNotPermitThatAgreementType() {
        // Arrange -- the park may well offer LAND agreements; this space cannot
        // host one. The two gates are independent.
        when(tenancyService.findTenancyById(TENANCY)).thenReturn(Optional.of(tenancy()));
        when(lotService.findById(LOT)).thenReturn(Optional.of(
                lot(new PermissibleAgreementType(AgreementType.STORAGE, null, null))));

        // Act / Assert
        assertThrows(IllegalStateException.class, () -> tenancyChargeTermService.createFromTemplate(
                TENANCY, AgreementType.LAND, VALID_AT, TenancyTermSource.LEASE, null));
        verifyNoInteractions(termsTemplateService);
        verify(tenancyChargeTermRepository, never()).save(any());
    }

    @Test
    void createFromTemplate_shouldPreferTheLotRate_overTheTemplateRate() {
        // Arrange -- rates are set while looking at lots on the map, so the lot wins
        arrangeCreate(lot(new PermissibleAgreementType(AgreementType.LAND, new BigDecimal("725.00"), new BigDecimal("725.00"))),
                template(new BigDecimal("650.00")));

        // Act
        tenancyChargeTermService.createFromTemplate(
                TENANCY, AgreementType.LAND, VALID_AT, TenancyTermSource.LEASE, null);

        // Assert
        verify(tenancyChargeTermRepository).save(argThat(
                row -> new BigDecimal("725.00").compareTo(row.rate()) == 0));
    }

    @Test
    void createFromTemplate_shouldFallBackToTheTemplateRate_whenTheLotHasNone() {
        // Arrange -- the lot permits the agreement type but nobody set a rate on it
        arrangeCreate(lot(new PermissibleAgreementType(AgreementType.LAND, null, null)),
                template(new BigDecimal("650.00")));

        // Act
        tenancyChargeTermService.createFromTemplate(
                TENANCY, AgreementType.LAND, VALID_AT, TenancyTermSource.LEASE, null);

        // Assert
        verify(tenancyChargeTermRepository).save(argThat(
                row -> new BigDecimal("650.00").compareTo(row.rate()) == 0));
    }

    @Test
    void createFromTemplate_shouldFallBackToZero_whenNeitherTierHasARate() {
        // Arrange -- legal for a draft; submit() is what refuses it
        arrangeCreate(lotPermittingLand(), template(null));

        // Act
        Optional<TenancyChargeTerm> result = tenancyChargeTermService.createFromTemplate(
                TENANCY, AgreementType.LAND, VALID_AT, TenancyTermSource.LEASE, null);

        // Assert
        assertTrue(result.isPresent(), "an unpriced draft is still created");
        verify(tenancyChargeTermRepository).save(argThat(row -> row.rate().signum() == 0));
    }

    @Test
    void createFromTemplate_shouldLandInProposedWithNoInstrument() {
        // Arrange
        arrangeCreate(lotPermittingLand(), template(new BigDecimal("650.00")));

        // Act
        tenancyChargeTermService.createFromTemplate(
                TENANCY, AgreementType.LAND, VALID_AT, TenancyTermSource.LEASE, null);

        // Assert
        verify(tenancyChargeTermRepository).save(argThat(row ->
                row.status() == TenancyTermStatus.PROPOSED
                        && row.sourceUuid() == null
                        && TEMPLATE.equals(row.termsTemplate())));
    }

    @Test
    void createFromTemplate_shouldCarryTheBatch_soABulkRunCanBeReviewedTogether() {
        // Arrange
        UUID batch = UUID.randomUUID();
        arrangeCreate(lotPermittingLand(), template(new BigDecimal("650.00")));

        // Act
        tenancyChargeTermService.createFromTemplate(
                TENANCY, AgreementType.LAND, VALID_AT, TenancyTermSource.INCREASE_NOTICE, batch);

        // Assert
        verify(tenancyChargeTermRepository).save(argThat(row -> batch.equals(row.batch())));
    }

    @Test
    void createFromTemplate_shouldRecordAnInsert() {
        // Arrange
        arrangeCreate(lotPermittingLand(), template(new BigDecimal("650.00")));

        // Act
        tenancyChargeTermService.createFromTemplate(
                TENANCY, AgreementType.LAND, VALID_AT, TenancyTermSource.LEASE, null);

        // Assert
        verify(auditService).recordInsert(eq("tenancy_charge_term"), any(), any());
    }

    @Test
    void createFromTemplate_shouldAttributeToSystem_whenThereIsNoRequest() {
        // Arrange -- a unit test has no bound request, so ActingAgent falls back
        arrangeCreate(lotPermittingLand(), template(new BigDecimal("650.00")));

        // Act
        tenancyChargeTermService.createFromTemplate(
                TENANCY, AgreementType.LAND, VALID_AT, TenancyTermSource.LEASE, null);

        // Assert
        verify(tenancyChargeTermRepository).save(argThat(
                row -> SystemPrincipal.AGENT_UUID.equals(row.createdBy())));
    }

    // ---- patchChargeTerm -----------------------------------------------------

    @Test
    void patchChargeTerm_shouldReturnEmpty_whenNotFound() {
        // Arrange
        UUID unknown = UUID.randomUUID();
        when(tenancyChargeTermRepository.findById(unknown)).thenReturn(Optional.empty());

        // Act
        Optional<TenancyChargeTerm> result =
                tenancyChargeTermService.patchChargeTerm(unknown, changes("note", "hi"));

        // Assert
        assertTrue(result.isEmpty());
        verify(tenancyChargeTermRepository, never()).patch(any(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    void patchChargeTerm_shouldThrow_onceTheTermHasLeftProposed() {
        // Arrange -- a document is out for signature; the paper and the row must agree
        UUID uuid = UUID.randomUUID();
        when(tenancyChargeTermRepository.findById(uuid))
                .thenReturn(Optional.of(row(uuid, TenancyTermStatus.PENDING)));

        // Act / Assert
        assertThrows(IllegalArgumentException.class,
                () -> tenancyChargeTermService.patchChargeTerm(uuid, changes("rate", new BigDecimal("700.00"))));
        verify(tenancyChargeTermRepository, never()).patch(any(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    void patchChargeTerm_shouldAllowAFlatMethodWithNoAmountYet() {
        // Arrange -- THE point of PROPOSED: a draft is filled in one field at a
        // time, so picking FLAT before typing the amount must not be refused
        UUID uuid = UUID.randomUUID();
        TenancyChargeTermRow before = rowWithWater(uuid, UtilityMethod.NONE, BigDecimal.ZERO);
        when(tenancyChargeTermRepository.findById(uuid)).thenReturn(Optional.of(before));
        when(tenancyChargeTermRepository.patch(eq(uuid), any())).thenReturn(Optional.of(before));

        Map<String, Object> changes = changes("water_method", "FLAT");

        // Act
        assertDoesNotThrow(() -> tenancyChargeTermService.patchChargeTerm(uuid, changes));

        // Assert -- the amount is left alone for the office worker to fill in
        assertFalse(changes.containsKey("water_flat_amount"));
    }

    @Test
    void patchChargeTerm_shouldZeroTheAmount_whenTheMethodStopsCarryingOne() {
        // Arrange -- mechanical and safe, so it happens at patch time
        UUID uuid = UUID.randomUUID();
        TenancyChargeTermRow before = rowWithWater(uuid, UtilityMethod.FLAT, new BigDecimal("40.00"));
        when(tenancyChargeTermRepository.findById(uuid)).thenReturn(Optional.of(before));
        when(tenancyChargeTermRepository.patch(eq(uuid), any())).thenReturn(Optional.of(before));

        Map<String, Object> changes = changes("water_method", "RUBS");

        // Act
        tenancyChargeTermService.patchChargeTerm(uuid, changes);

        // Assert
        assertEquals(BigDecimal.ZERO, changes.get("water_flat_amount"));
    }

    @Test
    void patchChargeTerm_shouldRejectSubmeteredTrash() {
        // Arrange -- trash is collected per container; there is nothing to meter
        UUID uuid = UUID.randomUUID();
        when(tenancyChargeTermRepository.findById(uuid))
                .thenReturn(Optional.of(row(uuid, TenancyTermStatus.PROPOSED)));

        // Act / Assert
        assertThrows(IllegalArgumentException.class, () -> tenancyChargeTermService
                .patchChargeTerm(uuid, changes("trash_method", "SUBMETERED")));
        verify(tenancyChargeTermRepository, never()).patch(any(), any());
    }

    @Test
    void patchChargeTerm_shouldRejectAMethodOutsideTheColumnsSubset() {
        // Arrange -- PERCENT_OF_RENT is a late fee method, never an NSF one
        UUID uuid = UUID.randomUUID();
        when(tenancyChargeTermRepository.findById(uuid))
                .thenReturn(Optional.of(row(uuid, TenancyTermStatus.PROPOSED)));

        // Act / Assert
        assertThrows(IllegalArgumentException.class, () -> tenancyChargeTermService
                .patchChargeTerm(uuid, changes("nsf_fee_method", "PERCENT_OF_RENT")));
    }

    @Test
    void patchChargeTerm_shouldNormaliseMethodCase() {
        // Arrange
        UUID uuid = UUID.randomUUID();
        TenancyChargeTermRow before = rowWithWater(uuid, UtilityMethod.FLAT, new BigDecimal("40.00"));
        when(tenancyChargeTermRepository.findById(uuid)).thenReturn(Optional.of(before));
        when(tenancyChargeTermRepository.patch(eq(uuid), any())).thenReturn(Optional.of(before));

        Map<String, Object> changes = changes("water_method", "  rubs  ");

        // Act
        tenancyChargeTermService.patchChargeTerm(uuid, changes);

        // Assert
        assertEquals("RUBS", changes.get("water_method"));
    }

    @Test
    void patchChargeTerm_shouldNotRecordAudit_whenNothingActuallyChanged() {
        // Arrange -- the log is a record of state changes, not of attempts
        UUID uuid = UUID.randomUUID();
        TenancyChargeTermRow unchanged = row(uuid, TenancyTermStatus.PROPOSED);
        when(tenancyChargeTermRepository.findById(uuid)).thenReturn(Optional.of(unchanged));
        when(tenancyChargeTermRepository.patch(eq(uuid), any())).thenReturn(Optional.of(unchanged));

        // Act
        tenancyChargeTermService.patchChargeTerm(uuid, changes("note", unchanged.note()));

        // Assert
        verify(auditService, never()).recordUpdate(any(), any(), any(), any());
    }

    @Test
    void patchChargeTerm_shouldRecordAudit_whenSomethingChanged() {
        // Arrange
        UUID uuid = UUID.randomUUID();
        TenancyChargeTermRow before = row(uuid, TenancyTermStatus.PROPOSED);
        TenancyChargeTermRow after = rowWithRate(uuid, new BigDecimal("700.00"));
        when(tenancyChargeTermRepository.findById(uuid)).thenReturn(Optional.of(before));
        when(tenancyChargeTermRepository.patch(eq(uuid), any())).thenReturn(Optional.of(after));

        // Act
        tenancyChargeTermService.patchChargeTerm(uuid, changes("rate", new BigDecimal("700.00")));

        // Assert
        verify(auditService).recordUpdate(eq("tenancy_charge_term"), eq(uuid), any(), any());
    }

    // ---- submit --------------------------------------------------------------

    @Test
    void submit_shouldReturnEmpty_whenNotFound() {
        // Arrange
        UUID unknown = UUID.randomUUID();
        when(tenancyChargeTermRepository.findById(unknown)).thenReturn(Optional.empty());

        // Act
        Optional<TenancyChargeTerm> result = tenancyChargeTermService.submit(unknown);

        // Assert
        assertTrue(result.isEmpty());
        verify(tenancyChargeTermRepository, never()).updateStatus(any(), any(), any());
    }

    @Test
    void submit_shouldThrow_whenTheTermIsNotProposed() {
        // Arrange
        UUID uuid = UUID.randomUUID();
        when(tenancyChargeTermRepository.findById(uuid))
                .thenReturn(Optional.of(row(uuid, TenancyTermStatus.ACTIVE)));

        // Act / Assert
        assertThrows(IllegalArgumentException.class, () -> tenancyChargeTermService.submit(uuid));
        verify(tenancyChargeTermRepository, never()).updateStatus(any(), any(), any());
    }

    @Test
    void submit_shouldRefuseAnUnpricedTerm_namingTheRate() {
        // Arrange -- the 0.00 that createFromTemplate allowed as a draft
        UUID uuid = UUID.randomUUID();
        when(tenancyChargeTermRepository.findById(uuid))
                .thenReturn(Optional.of(rowWithRate(uuid, BigDecimal.ZERO)));

        // Act
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> tenancyChargeTermService.submit(uuid));

        // Assert
        assertTrue(e.getMessage().contains("rate"), e.getMessage());
        verify(tenancyChargeTermRepository, never()).updateStatus(any(), any(), any());
    }

    @Test
    void submit_shouldRefuseCarsMaxBelowAllowedCars() {
        // Arrange -- term_cars_max_at_least_allowed, escaped while PROPOSED
        UUID uuid = UUID.randomUUID();
        when(tenancyChargeTermRepository.findById(uuid))
                .thenReturn(Optional.of(rowWithCars(uuid, 4, 2)));

        // Act
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> tenancyChargeTermService.submit(uuid));

        // Assert
        assertTrue(e.getMessage().contains("carsMax"), e.getMessage());
    }

    @Test
    void submit_shouldRefuseAFlatFeeWithNoAmount() {
        // Arrange
        UUID uuid = UUID.randomUUID();
        when(tenancyChargeTermRepository.findById(uuid))
                .thenReturn(Optional.of(rowWithLateFee(uuid, FeeMethod.FLAT, BigDecimal.ZERO)));

        // Act
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> tenancyChargeTermService.submit(uuid));

        // Assert
        assertTrue(e.getMessage().contains("late_fee_amount"), e.getMessage());
    }

    @Test
    void submit_shouldRefuseAPercentOfRentLateFeeWithNoAmount() {
        // Arrange -- the case a "requiresAmount() == FLAT" helper would have missed
        UUID uuid = UUID.randomUUID();
        when(tenancyChargeTermRepository.findById(uuid))
                .thenReturn(Optional.of(rowWithLateFee(uuid, FeeMethod.PERCENT_OF_RENT, BigDecimal.ZERO)));

        // Act
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> tenancyChargeTermService.submit(uuid));

        // Assert
        assertTrue(e.getMessage().contains("late_fee_amount"), e.getMessage());
    }

    @Test
    void submit_shouldRefuseABankOrFlatNsfFeeWithNoAmount() {
        // Arrange -- BANK_OR_FLAT still needs the flat figure to compare against
        UUID uuid = UUID.randomUUID();
        when(tenancyChargeTermRepository.findById(uuid))
                .thenReturn(Optional.of(rowWithNsfFee(uuid, FeeMethod.BANK_OR_FLAT, BigDecimal.ZERO)));

        // Act
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> tenancyChargeTermService.submit(uuid));

        // Assert
        assertTrue(e.getMessage().contains("nsf_fee_amount"), e.getMessage());
    }

    @Test
    void submit_shouldRefuseAnAmountLeftBehindByANoneMethod() {
        // Arrange -- the other half of the CASE expression
        UUID uuid = UUID.randomUUID();
        when(tenancyChargeTermRepository.findById(uuid))
                .thenReturn(Optional.of(rowWithLateFee(uuid, FeeMethod.NONE, new BigDecimal("65.00"))));

        // Act
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> tenancyChargeTermService.submit(uuid));

        // Assert
        assertTrue(e.getMessage().contains("late_fee_amount"), e.getMessage());
    }

    @Test
    void submit_shouldReportEveryProblemAtOnce() {
        // Arrange -- one round trip per bad field is not a form workflow
        UUID uuid = UUID.randomUUID();
        TenancyChargeTermRow broken = row(uuid, TenancyTermStatus.PROPOSED, TenancyTermSource.LEASE, null,
                BigDecimal.ZERO, 4, 2,
                FeeMethod.FLAT, BigDecimal.ZERO,
                FeeMethod.NONE, BigDecimal.ZERO,
                UtilityMethod.NONE, BigDecimal.ZERO);
        when(tenancyChargeTermRepository.findById(uuid)).thenReturn(Optional.of(broken));

        // Act
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> tenancyChargeTermService.submit(uuid));

        // Assert
        assertTrue(e.getMessage().contains("rate"), e.getMessage());
        assertTrue(e.getMessage().contains("carsMax"), e.getMessage());
        assertTrue(e.getMessage().contains("late_fee_amount"), e.getMessage());
    }

    @Test
    void submit_shouldMoveAConsistentTermToPending_andRecordTheChange() {
        // Arrange
        UUID uuid = UUID.randomUUID();
        TenancyChargeTermRow before = row(uuid, TenancyTermStatus.PROPOSED);
        when(tenancyChargeTermRepository.findById(uuid)).thenReturn(Optional.of(before));
        when(tenancyChargeTermRepository.updateStatus(uuid, TenancyTermStatus.PROPOSED, TenancyTermStatus.PENDING))
                .thenReturn(Optional.of(row(uuid, TenancyTermStatus.PENDING)));

        // Act
        Optional<TenancyChargeTerm> result = tenancyChargeTermService.submit(uuid);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(TenancyTermStatus.PENDING, result.get().status());
        verify(auditService).recordUpdate(eq("tenancy_charge_term"), eq(uuid), any(), any());
    }

    @Test
    void submit_shouldReturnEmpty_whenSomebodyElseMovedItFirst() {
        // Arrange -- the guarded UPDATE is also the concurrency guard
        UUID uuid = UUID.randomUUID();
        when(tenancyChargeTermRepository.findById(uuid))
                .thenReturn(Optional.of(row(uuid, TenancyTermStatus.PROPOSED)));
        when(tenancyChargeTermRepository.updateStatus(any(), any(), any())).thenReturn(Optional.empty());

        // Act
        Optional<TenancyChargeTerm> result = tenancyChargeTermService.submit(uuid);

        // Assert
        assertTrue(result.isEmpty());
        verifyNoInteractions(auditService);
    }

    // ---- activate ------------------------------------------------------------

    @Test
    void activate_shouldRefuseATermWithNoPaperBehindIt() {
        // Arrange -- term_in_force_needs_paper
        UUID uuid = UUID.randomUUID();
        when(tenancyChargeTermRepository.findById(uuid)).thenReturn(Optional.of(
                row(uuid, TenancyTermStatus.PENDING, TenancyTermSource.LEASE, null,
                        new BigDecimal("650.00"), 2, 4,
                        FeeMethod.NONE, BigDecimal.ZERO,
                        FeeMethod.NONE, BigDecimal.ZERO,
                        UtilityMethod.NONE, BigDecimal.ZERO)));

        // Act / Assert
        assertThrows(IllegalArgumentException.class, () -> tenancyChargeTermService.activate(uuid));
        verify(tenancyChargeTermRepository, never()).updateStatus(any(), any(), any());
    }

    @Test
    void activate_shouldAllowAMigratedTermWithNoInstrument() {
        // Arrange -- the one source the constraint exempts
        UUID uuid = UUID.randomUUID();
        TenancyChargeTermRow migrated = row(uuid, TenancyTermStatus.PENDING, TenancyTermSource.MIGRATION, null,
                new BigDecimal("650.00"), 2, 4,
                FeeMethod.NONE, BigDecimal.ZERO,
                FeeMethod.NONE, BigDecimal.ZERO,
                UtilityMethod.NONE, BigDecimal.ZERO);
        when(tenancyChargeTermRepository.findById(uuid)).thenReturn(Optional.of(migrated));
        when(tenancyChargeTermRepository.updateStatus(uuid, TenancyTermStatus.PENDING, TenancyTermStatus.ACTIVE))
                .thenReturn(Optional.of(migrated));

        // Act / Assert
        assertTrue(tenancyChargeTermService.activate(uuid).isPresent());
    }

    @Test
    void activate_shouldAllowATermWithAnInstrumentAttached() {
        // Arrange
        UUID uuid = UUID.randomUUID();
        TenancyChargeTermRow papered = row(uuid, TenancyTermStatus.PENDING, TenancyTermSource.LEASE,
                UUID.randomUUID(),
                new BigDecimal("650.00"), 2, 4,
                FeeMethod.NONE, BigDecimal.ZERO,
                FeeMethod.NONE, BigDecimal.ZERO,
                UtilityMethod.NONE, BigDecimal.ZERO);
        when(tenancyChargeTermRepository.findById(uuid)).thenReturn(Optional.of(papered));
        when(tenancyChargeTermRepository.updateStatus(uuid, TenancyTermStatus.PENDING, TenancyTermStatus.ACTIVE))
                .thenReturn(Optional.of(papered));

        // Act / Assert
        assertTrue(tenancyChargeTermService.activate(uuid).isPresent());
        verify(auditService).recordUpdate(eq("tenancy_charge_term"), eq(uuid), any(), any());
    }

    // ---- cancel --------------------------------------------------------------

    @Test
    void cancel_shouldThrow_whenNoReasonIsGiven() {
        // Arrange -- term_cancel_facts requires all three cancel columns
        // Act / Assert
        assertThrows(IllegalArgumentException.class,
                () -> tenancyChargeTermService.cancel(UUID.randomUUID(), "   "));
        verifyNoInteractions(tenancyChargeTermRepository);
        verifyNoInteractions(auditService);
    }

    @Test
    void cancel_shouldReturnEmpty_whenTheTermWasNeverInForce() {
        // Arrange -- the repository guards on ACTIVE; a draft is deleted instead
        UUID uuid = UUID.randomUUID();
        when(tenancyChargeTermRepository.findById(uuid))
                .thenReturn(Optional.of(row(uuid, TenancyTermStatus.PROPOSED)));
        when(tenancyChargeTermRepository.cancel(eq(uuid), any(), eq("wrong lot")))
                .thenReturn(Optional.empty());

        // Act
        Optional<TenancyChargeTerm> result = tenancyChargeTermService.cancel(uuid, "wrong lot");

        // Assert
        assertTrue(result.isEmpty());
        verifyNoInteractions(auditService);
    }

    @Test
    void cancel_shouldRecordTheChange() {
        // Arrange
        UUID uuid = UUID.randomUUID();
        when(tenancyChargeTermRepository.findById(uuid))
                .thenReturn(Optional.of(row(uuid, TenancyTermStatus.ACTIVE)));
        when(tenancyChargeTermRepository.cancel(eq(uuid), any(), eq("tenant moved out")))
                .thenReturn(Optional.of(row(uuid, TenancyTermStatus.CANCELLED)));

        // Act
        Optional<TenancyChargeTerm> result = tenancyChargeTermService.cancel(uuid, "  tenant moved out  ");

        // Assert
        assertTrue(result.isPresent());
        verify(auditService).recordUpdate(eq("tenancy_charge_term"), eq(uuid), any(), any());
    }

    // ---- deleteChargeTerm ----------------------------------------------------

    @Test
    void deleteChargeTerm_shouldReturnFalse_whenNotFound() {
        // Arrange
        UUID unknown = UUID.randomUUID();
        when(tenancyChargeTermRepository.findById(unknown)).thenReturn(Optional.empty());

        // Act / Assert
        assertFalse(tenancyChargeTermService.deleteChargeTerm(unknown));
        verify(tenancyChargeTermRepository, never()).softDelete(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void deleteChargeTerm_shouldNotRecordAudit_whenNoRowWasAffected() {
        // Arrange -- an in-force term: the repository's status guard refuses it
        UUID uuid = UUID.randomUUID();
        when(tenancyChargeTermRepository.findById(uuid))
                .thenReturn(Optional.of(row(uuid, TenancyTermStatus.ACTIVE)));
        when(tenancyChargeTermRepository.softDelete(uuid)).thenReturn(false);

        // Act / Assert
        assertFalse(tenancyChargeTermService.deleteChargeTerm(uuid));
        verifyNoInteractions(auditService);
    }

    @Test
    void deleteChargeTerm_shouldRecordADelete_whenARowWasAffected() {
        // Arrange
        UUID uuid = UUID.randomUUID();
        when(tenancyChargeTermRepository.findById(uuid))
                .thenReturn(Optional.of(row(uuid, TenancyTermStatus.PROPOSED)));
        when(tenancyChargeTermRepository.softDelete(uuid)).thenReturn(true);

        // Act / Assert
        assertTrue(tenancyChargeTermService.deleteChargeTerm(uuid));
        verify(auditService).recordDelete(eq("tenancy_charge_term"), eq(uuid), any());
    }

    // ---- security deposit ----------------------------------------------------

    @Test
    void patchChargeTerm_shouldKeepTheMultiplier_whenTheDepositIsMultipleOfRent() {
        // Arrange -- a multiplier is an amount; FLAT_ONLY would have zeroed it
        TenancyChargeTermRow before = row(UUID.randomUUID(), TenancyTermStatus.PROPOSED);
        when(tenancyChargeTermRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(tenancyChargeTermRepository.patch(eq(before.uuid()), any())).thenReturn(Optional.of(before));

        // Act
        Map<String, Object> changes = new HashMap<>();
        changes.put("security_deposit_method", "MULTIPLE_OF_RENT");
        changes.put("security_deposit_amount", new BigDecimal("1.00"));
        tenancyChargeTermService.patchChargeTerm(before.uuid(), changes);

        // Assert
        verify(tenancyChargeTermRepository).patch(eq(before.uuid()), argThat(
                map -> new BigDecimal("1.00")
                        .compareTo((BigDecimal) map.get("security_deposit_amount")) == 0));
    }

    @Test
    void patchChargeTerm_shouldZeroTheDeposit_whenTheMethodBecomesNone() {
        // Arrange
        TenancyChargeTermRow before =
                rowWithDeposit(UUID.randomUUID(), SecurityDepositMethod.FLAT, new BigDecimal("500.00"));
        when(tenancyChargeTermRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(tenancyChargeTermRepository.patch(eq(before.uuid()), any())).thenReturn(Optional.of(before));

        // Act
        Map<String, Object> changes = new HashMap<>();
        changes.put("security_deposit_method", "NONE");
        tenancyChargeTermService.patchChargeTerm(before.uuid(), changes);

        // Assert
        verify(tenancyChargeTermRepository).patch(eq(before.uuid()), argThat(
                map -> BigDecimal.ZERO.equals(map.get("security_deposit_amount"))));
    }

    @Test
    void patchChargeTerm_shouldRejectAFeeMethodOnTheDepositColumn() {
        // Arrange -- PERCENT_OF_RENT is a real FeeMethod, but not a deposit method
        TenancyChargeTermRow before = row(UUID.randomUUID(), TenancyTermStatus.PROPOSED);
        when(tenancyChargeTermRepository.findById(before.uuid())).thenReturn(Optional.of(before));

        // Act / Assert
        Map<String, Object> changes = new HashMap<>();
        changes.put("security_deposit_method", "PERCENT_OF_RENT");
        assertThrows(IllegalArgumentException.class,
                () -> tenancyChargeTermService.patchChargeTerm(before.uuid(), changes));
        verify(tenancyChargeTermRepository, never()).patch(any(), any());
    }

    @Test
    void submit_shouldRefuseADepositMultiplierAboveTheCeiling() {
        // Arrange -- 500 typed into a field labelled "amount" while the method
        // is MULTIPLE_OF_RENT: a $325,000 deposit on a $650 lot
        TenancyChargeTermRow before = rowWithDeposit(
                UUID.randomUUID(), SecurityDepositMethod.MULTIPLE_OF_RENT, new BigDecimal("500.00"));
        when(tenancyChargeTermRepository.findById(before.uuid())).thenReturn(Optional.of(before));

        // Act / Assert -- the message has to show the resulting dollars, or the
        // office worker cannot see what they actually typed
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tenancyChargeTermService.submit(before.uuid()));
        assertTrue(thrown.getMessage().contains("325000.00"), thrown.getMessage());
        verify(tenancyChargeTermRepository, never()).updateStatus(any(), any(), any());
    }

    @Test
    void submit_shouldAllowAMultiplierAtTheCeiling() {
        // Arrange -- the bound is inclusive; five months' rent is legal
        TenancyChargeTermRow before = rowWithDeposit(
                UUID.randomUUID(), SecurityDepositMethod.MULTIPLE_OF_RENT, new BigDecimal("5"));
        when(tenancyChargeTermRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(tenancyChargeTermRepository.updateStatus(
                before.uuid(), TenancyTermStatus.PROPOSED, TenancyTermStatus.PENDING))
                .thenReturn(Optional.of(before));

        // Act
        tenancyChargeTermService.submit(before.uuid());

        // Assert
        verify(tenancyChargeTermRepository).updateStatus(
                before.uuid(), TenancyTermStatus.PROPOSED, TenancyTermStatus.PENDING);
    }

    @Test
    void submit_shouldNotApplyTheMultiplierCeilingToAFlatDeposit() {
        // Arrange -- $500 is an ordinary flat deposit, not 500 months' rent
        TenancyChargeTermRow before = rowWithDeposit(
                UUID.randomUUID(), SecurityDepositMethod.FLAT, new BigDecimal("500.00"));
        when(tenancyChargeTermRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(tenancyChargeTermRepository.updateStatus(
                before.uuid(), TenancyTermStatus.PROPOSED, TenancyTermStatus.PENDING))
                .thenReturn(Optional.of(before));

        // Act
        tenancyChargeTermService.submit(before.uuid());

        // Assert
        verify(tenancyChargeTermRepository).updateStatus(
                before.uuid(), TenancyTermStatus.PROPOSED, TenancyTermStatus.PENDING);
    }

    @Test
    void submit_shouldRefuseAMultipleOfRentDepositWithNoAmount() {
        // Arrange -- the floor, from the pair loop rather than the ceiling above
        TenancyChargeTermRow before = rowWithDeposit(
                UUID.randomUUID(), SecurityDepositMethod.MULTIPLE_OF_RENT, BigDecimal.ZERO);
        when(tenancyChargeTermRepository.findById(before.uuid())).thenReturn(Optional.of(before));

        // Act / Assert
        assertThrows(IllegalArgumentException.class,
                () -> tenancyChargeTermService.submit(before.uuid()));
        verify(tenancyChargeTermRepository, never()).updateStatus(any(), any(), any());
    }


    // ---- Fixtures ------------------------------------------------------------

    private static TenancyChargeTermRow rowWithDeposit(
            UUID uuid, SecurityDepositMethod method, BigDecimal amount) {
        return row(uuid, TenancyTermStatus.PROPOSED, TenancyTermSource.LEASE, null,
                new BigDecimal("650.00"), 2, 4,
                FeeMethod.FLAT, new BigDecimal("65.00"),
                FeeMethod.FLAT, new BigDecimal("35.00"),
                UtilityMethod.NONE, BigDecimal.ZERO,
                method, amount);
    }


    private void arrangeCreate(Lot lot, TermsTemplate template) {
        when(tenancyService.findTenancyById(TENANCY)).thenReturn(Optional.of(tenancy()));
        when(lotService.findById(LOT)).thenReturn(Optional.of(lot));
        when(termsTemplateService.findForProperty(PROPERTY, AgreementType.LAND))
                .thenReturn(Optional.of(template));
        when(tenancyChargeTermRepository.save(any()))
                .thenAnswer(call -> call.getArgument(0, TenancyChargeTermRow.class));
    }

    private static Map<String, Object> changes(String column, Object value) {
        Map<String, Object> changes = new HashMap<>();
        changes.put(column, value);
        return changes;
    }

    private static Tenancy tenancy() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new Tenancy(TENANCY, LOT, LocalDate.of(2026, 8, 1), null,
                false, false, true, false, now, null);
    }

    /** A lot that permits LAND but has no rate set on it yet. */
    private static Lot lotPermittingLand() {
        return lot(new PermissibleAgreementType(AgreementType.LAND, null, null));
    }

    private static Lot lot(PermissibleAgreementType... permissible) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new Lot(LOT, PROPERTY, true, null, "42", null, null, null, null,
                1, null, now, null, List.of(permissible));
    }

    /**
     * A property-level template, as TermsTemplateService would hand it over.
     * One figure covers both rates: these tests are about which tier the rate
     * came from, not which of the two rates was picked.
     */
    private static TermsTemplate template(BigDecimal rate) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new TermsTemplate(
                TEMPLATE, PROPERTY, null, "Test Land Lease", AgreementType.LAND, rate, rate,
                new BigDecimal("45.00"), 2, 4,
                new BigDecimal("45.00"), 2,
                1, 5,
                FeeMethod.NONE, BigDecimal.ZERO,
                FeeMethod.NONE, BigDecimal.ZERO,
                FeeMethod.NONE, BigDecimal.ZERO,
                UtilityMethod.NONE, BigDecimal.ZERO,
                UtilityMethod.NONE, BigDecimal.ZERO,
                UtilityMethod.NONE, BigDecimal.ZERO,
                UtilityMethod.NONE, BigDecimal.ZERO,
                SecurityDepositMethod.NONE, BigDecimal.ZERO,
                null, now, now, SystemPrincipal.AGENT_UUID, null);
    }

    /** A consistent term that would satisfy every CHECK constraint on submission. */
    private static TenancyChargeTermRow row(UUID uuid, TenancyTermStatus status) {
        return row(uuid, status, TenancyTermSource.LEASE, UUID.randomUUID(),
                new BigDecimal("650.00"), 2, 4,
                FeeMethod.FLAT, new BigDecimal("65.00"),
                FeeMethod.FLAT, new BigDecimal("35.00"),
                UtilityMethod.NONE, BigDecimal.ZERO);
    }

    private static TenancyChargeTermRow rowWithRate(UUID uuid, BigDecimal rate) {
        return row(uuid, TenancyTermStatus.PROPOSED, TenancyTermSource.LEASE, null,
                rate, 2, 4,
                FeeMethod.FLAT, new BigDecimal("65.00"),
                FeeMethod.FLAT, new BigDecimal("35.00"),
                UtilityMethod.NONE, BigDecimal.ZERO);
    }

    private static TenancyChargeTermRow rowWithCars(UUID uuid, int allowedCars, int carsMax) {
        return row(uuid, TenancyTermStatus.PROPOSED, TenancyTermSource.LEASE, null,
                new BigDecimal("650.00"), allowedCars, carsMax,
                FeeMethod.FLAT, new BigDecimal("65.00"),
                FeeMethod.FLAT, new BigDecimal("35.00"),
                UtilityMethod.NONE, BigDecimal.ZERO);
    }

    private static TenancyChargeTermRow rowWithLateFee(UUID uuid, FeeMethod method, BigDecimal amount) {
        return row(uuid, TenancyTermStatus.PROPOSED, TenancyTermSource.LEASE, null,
                new BigDecimal("650.00"), 2, 4,
                method, amount,
                FeeMethod.FLAT, new BigDecimal("35.00"),
                UtilityMethod.NONE, BigDecimal.ZERO);
    }

    private static TenancyChargeTermRow rowWithNsfFee(UUID uuid, FeeMethod method, BigDecimal amount) {
        return row(uuid, TenancyTermStatus.PROPOSED, TenancyTermSource.LEASE, null,
                new BigDecimal("650.00"), 2, 4,
                FeeMethod.FLAT, new BigDecimal("65.00"),
                method, amount,
                UtilityMethod.NONE, BigDecimal.ZERO);
    }

    private static TenancyChargeTermRow rowWithWater(UUID uuid, UtilityMethod method, BigDecimal amount) {
        return row(uuid, TenancyTermStatus.PROPOSED, TenancyTermSource.LEASE, null,
                new BigDecimal("650.00"), 2, 4,
                FeeMethod.FLAT, new BigDecimal("65.00"),
                FeeMethod.FLAT, new BigDecimal("35.00"),
                method, amount);
    }

    /** The common case: no deposit. Delegates so the big constructor stays in one place. */
    private static TenancyChargeTermRow row(
            UUID uuid,
            TenancyTermStatus status,
            TenancyTermSource source,
            UUID sourceUuid,
            BigDecimal rate,
            int allowedCars,
            int carsMax,
            FeeMethod lateFeeMethod,
            BigDecimal lateFeeAmount,
            FeeMethod nsfFeeMethod,
            BigDecimal nsfFeeAmount,
            UtilityMethod waterMethod,
            BigDecimal waterFlatAmount) {

        return row(uuid, status, source, sourceUuid, rate, allowedCars, carsMax,
                lateFeeMethod, lateFeeAmount, nsfFeeMethod, nsfFeeAmount,
                waterMethod, waterFlatAmount,
                SecurityDepositMethod.NONE, BigDecimal.ZERO);
    }

    /**
     * The one place the 38-component constructor is written out. Rule violation,
     * power, sewer and trash stay NONE/zero -- the tests that exercise those
     * columns go through the patch map, not the row.
     */
    private static TenancyChargeTermRow row(
            UUID uuid,
            TenancyTermStatus status,
            TenancyTermSource source,
            UUID sourceUuid,
            BigDecimal rate,
            int allowedCars,
            int carsMax,
            FeeMethod lateFeeMethod,
            BigDecimal lateFeeAmount,
            FeeMethod nsfFeeMethod,
            BigDecimal nsfFeeAmount,
            UtilityMethod waterMethod,
            BigDecimal waterFlatAmount,
            SecurityDepositMethod securityDepositMethod,
            BigDecimal securityDepositAmount) {

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean cancelled = status == TenancyTermStatus.CANCELLED;

        return new TenancyChargeTermRow(
                uuid,
                TENANCY,
                VALID_AT,
                AgreementType.LAND,
                rate,
                new BigDecimal("45.00"),
                allowedCars,
                carsMax,
                new BigDecimal("45.00"),
                2,
                1,
                5,
                FeeMethod.NONE, BigDecimal.ZERO,          // rule violation
                nsfFeeMethod, nsfFeeAmount,
                lateFeeMethod, lateFeeAmount,
                waterMethod, waterFlatAmount,
                UtilityMethod.NONE, BigDecimal.ZERO,      // power
                UtilityMethod.NONE, BigDecimal.ZERO,      // sewer
                UtilityMethod.NONE, BigDecimal.ZERO,      // trash
                securityDepositMethod, securityDepositAmount, // security deposit
                status,
                source,
                sourceUuid,
                TEMPLATE,
                null,                                      // batch
                cancelled ? now : null,
                cancelled ? SystemPrincipal.AGENT_UUID : null,
                cancelled ? "test" : null,
                null,                                      // deletedAt
                "test note",
                now,
                SystemPrincipal.AGENT_UUID);
    }
}