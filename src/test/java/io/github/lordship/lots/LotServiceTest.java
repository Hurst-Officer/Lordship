package io.github.lordship.lots;

import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.lots.internal.*;
import io.github.lordship.shared.*;
import io.github.lordship.termstemplate.TermsTemplate;
import io.github.lordship.termstemplate.TermsTemplateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class LotServiceTest {

    @Mock
    LotRepository lotRepository;

    @Mock
    LotPermissibleAgreementTypeRepository lotPermissibleAgreementTypeRepository;

    @Mock
    TermsTemplateService termsTemplateService;

    @Mock
    AuditService auditService;

    @InjectMocks
    LotService lotService;

    // ---- create --------------------------------------------------------------

    @Test
    void createLot_shouldInsertMinimalRow_andRecordAudit() {
        // Arrange -- a park that never took a land agreement on
        UUID propertyId = UUID.randomUUID();
        LotRow savedRow = stubRow(propertyId);
        when(lotRepository.save(propertyId, "12")).thenReturn(savedRow);
        when(termsTemplateService.findForProperty(propertyId, AgreementType.LAND)).thenReturn(Optional.empty());
        when(lotPermissibleAgreementTypeRepository.findByLotId(savedRow.uuid())).thenReturn(List.of());

        // Act
        Lot result = lotService.createLot(propertyId, "12");

        // Assert: only the two minimum columns reach the insert
        verify(lotRepository).save(propertyId, "12");

        assertEquals(savedRow.uuid(), result.uuid());
        assertEquals("12", result.lotNumber());

        // No land template to copy from, so nothing was seeded. The read on the
        // way out is hydration, not a write.
        assertEquals(List.of(), result.permissibleAgreementTypes());
        verify(lotPermissibleAgreementTypeRepository, never()).save(any());

        // Keys must be the record component names AuditMapper produces, matching every
        // other module's audit entries -- not the snake_case column names.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> auditCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).recordInsert(eq("lot"), eq(savedRow.uuid()), auditCaptor.capture());

        Map<String, Object> logged = auditCaptor.getValue();
        assertEquals(propertyId, logged.get("propertyId"));
        assertEquals("12", logged.get("lotNumber"));
        assertFalse(logged.containsKey("lot_number"));
        assertFalse(logged.containsKey("property_id"));
    }

    @Test
    void createLot_shouldSeedLand_atThePropertysTemplateRates() {
        // Arrange
        UUID propertyId = UUID.randomUUID();
        LotRow savedRow = stubRow(propertyId);
        when(lotRepository.save(propertyId, "12")).thenReturn(savedRow);
        when(termsTemplateService.findForProperty(propertyId, AgreementType.LAND))
                .thenReturn(Optional.of(template(new BigDecimal("650.00"), new BigDecimal("725.00"))));

        LotPermissibleAgreementTypeRow seeded = stubAgreementType(
                savedRow.uuid(), AgreementType.LAND, "650.00", "725.00");

        // Nothing there before the insert, one row after it -- which is exactly
        // what savePermissible looks at to decide insert-versus-update.
        when(lotPermissibleAgreementTypeRepository.findByLotId(savedRow.uuid()))
                .thenReturn(List.of(), List.of(seeded));
        when(lotPermissibleAgreementTypeRepository.save(any())).thenReturn(seeded);

        // Act
        Lot result = lotService.createLot(propertyId, "12");

        // Assert -- both figures come off the template
        verify(lotPermissibleAgreementTypeRepository).save(argThat(row ->
                row.agreementType() == AgreementType.LAND
                        && new BigDecimal("650.00").compareTo(row.targetRate()) == 0
                        && new BigDecimal("725.00").compareTo(row.askingRate()) == 0));

        assertTrue(result.permits(AgreementType.LAND));
        assertEquals(0, new BigDecimal("725.00").compareTo(
                result.askingRateFor(AgreementType.LAND).orElseThrow()));

        verify(auditService).recordInsert(eq("lot"), eq(savedRow.uuid()), any());
        verify(auditService).recordInsert(eq("lot_permissible_agreement_type"), eq(seeded.uuid()), any());
    }

    // ---- permit / revoke -----------------------------------------------------

    @Test
    void permitAgreementType_shouldReturnEmpty_whenTheLotDoesNotExist() {
        // Arrange
        UUID unknownUuid = UUID.randomUUID();
        when(lotRepository.findById(unknownUuid)).thenReturn(Optional.empty());

        // Act
        Optional<Lot> result = lotService.permitAgreementType(
                unknownUuid, AgreementType.STORAGE, null, null);

        // Assert
        assertTrue(result.isEmpty());
        verifyNoInteractions(termsTemplateService);
        verifyNoInteractions(auditService);
    }

    @Test
    void permitAgreementType_shouldThrow_whenThePropertyDoesNotOfferThatType() {
        // Arrange -- a space cannot offer what the park never took on
        LotRow lot = stubRow(UUID.randomUUID());
        when(lotRepository.findById(lot.uuid())).thenReturn(Optional.of(lot));
        when(termsTemplateService.findForProperty(lot.propertyId(), AgreementType.STORAGE))
                .thenReturn(Optional.empty());

        // Act / Assert
        assertThrows(IllegalStateException.class, () -> lotService.permitAgreementType(
                lot.uuid(), AgreementType.STORAGE, null, null));
        verify(lotPermissibleAgreementTypeRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void permitAgreementType_shouldUseTheSuppliedRates_overTheTemplates() {
        // Arrange
        LotRow lot = stubRow(UUID.randomUUID());
        when(lotRepository.findById(lot.uuid())).thenReturn(Optional.of(lot));
        when(termsTemplateService.findForProperty(lot.propertyId(), AgreementType.STORAGE))
                .thenReturn(Optional.of(template(new BigDecimal("80.00"), new BigDecimal("95.00"))));

        LotPermissibleAgreementTypeRow saved = stubAgreementType(
                lot.uuid(), AgreementType.STORAGE, "100.00", "120.00");
        when(lotPermissibleAgreementTypeRepository.findByLotId(lot.uuid()))
                .thenReturn(List.of(), List.of(saved));
        when(lotPermissibleAgreementTypeRepository.save(any())).thenReturn(saved);

        // Act
        lotService.permitAgreementType(lot.uuid(), AgreementType.STORAGE,
                new BigDecimal("100.00"), new BigDecimal("120.00"));

        // Assert
        verify(lotPermissibleAgreementTypeRepository).save(argThat(row ->
                new BigDecimal("100.00").compareTo(row.targetRate()) == 0
                        && new BigDecimal("120.00").compareTo(row.askingRate()) == 0));
    }

    @Test
    void permitAgreementType_shouldFallBackToTheTemplateRates_whenNoneSupplied() {
        // Arrange
        LotRow lot = stubRow(UUID.randomUUID());
        when(lotRepository.findById(lot.uuid())).thenReturn(Optional.of(lot));
        when(termsTemplateService.findForProperty(lot.propertyId(), AgreementType.STORAGE))
                .thenReturn(Optional.of(template(new BigDecimal("80.00"), new BigDecimal("95.00"))));

        LotPermissibleAgreementTypeRow saved = stubAgreementType(
                lot.uuid(), AgreementType.STORAGE, "80.00", "95.00");
        when(lotPermissibleAgreementTypeRepository.findByLotId(lot.uuid()))
                .thenReturn(List.of(), List.of(saved));
        when(lotPermissibleAgreementTypeRepository.save(any())).thenReturn(saved);

        // Act
        lotService.permitAgreementType(lot.uuid(), AgreementType.STORAGE, null, null);

        // Assert
        verify(lotPermissibleAgreementTypeRepository).save(argThat(row ->
                new BigDecimal("80.00").compareTo(row.targetRate()) == 0
                        && new BigDecimal("95.00").compareTo(row.askingRate()) == 0));
    }

    @Test
    void permitAgreementType_shouldThrow_onANegativeRate() {
        // Arrange
        LotRow lot = stubRow(UUID.randomUUID());
        when(lotRepository.findById(lot.uuid())).thenReturn(Optional.of(lot));
        when(termsTemplateService.findForProperty(lot.propertyId(), AgreementType.LAND))
                .thenReturn(Optional.of(template(new BigDecimal("650.00"), new BigDecimal("725.00"))));

        // Act / Assert
        assertThrows(IllegalArgumentException.class, () -> lotService.permitAgreementType(
                lot.uuid(), AgreementType.LAND, new BigDecimal("-1.00"), null));
        verify(lotPermissibleAgreementTypeRepository, never()).save(any());
    }

    @Test
    void revokeAgreementType_shouldRecordADelete_whenARowWent() {
        // Arrange
        LotRow lot = stubRow(UUID.randomUUID());
        LotPermissibleAgreementTypeRow existing = stubAgreementType(
                lot.uuid(), AgreementType.STORAGE, "80.00", "95.00");
        when(lotRepository.findById(lot.uuid())).thenReturn(Optional.of(lot));
        when(lotPermissibleAgreementTypeRepository.findByLotId(lot.uuid()))
                .thenReturn(List.of(existing), List.of());
        when(lotPermissibleAgreementTypeRepository.delete(lot.uuid(), AgreementType.STORAGE)).thenReturn(true);

        // Act
        Optional<Lot> result = lotService.revokeAgreementType(lot.uuid(), AgreementType.STORAGE);

        // Assert
        assertTrue(result.isPresent());
        assertFalse(result.get().permits(AgreementType.STORAGE));
        verify(auditService).recordDelete(
                eq("lot_permissible_agreement_type"), eq(existing.uuid()), any());
    }

    @Test
    void revokeAgreementType_shouldNotRecordAudit_whenThereWasNothingToRevoke() {
        // Arrange -- the log is a record of state changes, not of attempts
        LotRow lot = stubRow(UUID.randomUUID());
        when(lotRepository.findById(lot.uuid())).thenReturn(Optional.of(lot));
        when(lotPermissibleAgreementTypeRepository.findByLotId(lot.uuid())).thenReturn(List.of());

        // Act
        Optional<Lot> result = lotService.revokeAgreementType(lot.uuid(), AgreementType.STORAGE);

        // Assert
        assertTrue(result.isPresent());
        verify(lotPermissibleAgreementTypeRepository, never()).delete(any(), any());
        verifyNoInteractions(auditService);
    }

    // ---- the owner's pricing pass --------------------------------------------

    @Test
    void setRates_shouldThrow_whenNeitherFigureIsSupplied() {
        // Act / Assert
        assertThrows(IllegalArgumentException.class, () -> lotService.setRates(
                List.of(UUID.randomUUID()), AgreementType.LAND, null, null));
        verifyNoInteractions(lotPermissibleAgreementTypeRepository);
        verifyNoInteractions(auditService);
    }

    @Test
    void setRates_shouldOnlyCountAndLogTheLotsThatActuallyMoved() {
        // Arrange -- one lot already sits at the new figure
        UUID lotA = UUID.randomUUID();
        UUID lotB = UUID.randomUUID();
        List<UUID> selection = List.of(lotA, lotB);

        when(lotPermissibleAgreementTypeRepository.findByLotIds(selection)).thenReturn(List.of(
                stubAgreementType(lotA, AgreementType.LAND, "600.00", "725.00"),
                stubAgreementType(lotB, AgreementType.LAND, "650.00", "725.00")));

        // Act
        int updated = lotService.setRates(selection, AgreementType.LAND, new BigDecimal("650.00"), null);

        // Assert
        assertEquals(1, updated, "lot B was already at 650.00");
        verify(auditService, times(1)).recordUpdate(
                eq("lot_permissible_agreement_type"), any(), any(), any());
    }

    @Test
    void setRates_shouldLeaveTheOtherFigureAlone() {
        // Arrange -- setting only the asking rate must not disturb the target
        UUID lotA = UUID.randomUUID();
        when(lotPermissibleAgreementTypeRepository.findByLotIds(List.of(lotA))).thenReturn(List.of(
                stubAgreementType(lotA, AgreementType.LAND, "650.00", "700.00")));

        // Act
        lotService.setRates(List.of(lotA), AgreementType.LAND, null, new BigDecimal("725.00"));

        // Assert
        verify(lotPermissibleAgreementTypeRepository)
                .updateRates(List.of(lotA), AgreementType.LAND, null, new BigDecimal("725.00"));

        AuditMapper.Diff logged = capturedPermissibleUpdate();
        assertEquals(Set.of("askingRate"), logged.before().keySet());
        assertEquals(0, new BigDecimal("725.00").compareTo((BigDecimal) logged.after().get("askingRate")));
    }

    @Test
    void setRates_shouldIgnoreLotsThatDoNotPermitTheType() {
        // Arrange -- a stale selection, not a lot to enrol
        UUID lotA = UUID.randomUUID();
        when(lotPermissibleAgreementTypeRepository.findByLotIds(List.of(lotA))).thenReturn(List.of(
                stubAgreementType(lotA, AgreementType.STORAGE, "80.00", "95.00")));

        // Act
        int updated = lotService.setRates(List.of(lotA), AgreementType.LAND, new BigDecimal("650.00"), null);

        // Assert
        assertEquals(0, updated);
        verifyNoInteractions(auditService);
    }

    // ---- delete --------------------------------------------------------------

    @Test
    void deleteLot_shouldReturnTrue_andRecordAudit_whenExists() {
        // Arrange
        LotRow existing = stubRow(UUID.randomUUID());
        when(lotRepository.findById(existing.uuid())).thenReturn(Optional.of(existing));
        when(lotRepository.softDelete(existing.uuid())).thenReturn(true);

        // Act
        boolean result = lotService.deleteLot(existing.uuid());

        // Assert
        assertTrue(result);
        verify(lotRepository).softDelete(existing.uuid());
        // The permissible types stay -- what the space could host is still true.
        verifyNoInteractions(lotPermissibleAgreementTypeRepository);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).recordDelete(eq("lot"), eq(existing.uuid()), captor.capture());
        verify(auditService, never()).recordUpdate(any(), any(), any(), any());

        Map<String, Object> logged = captor.getValue();
        assertEquals(existing.propertyId(), logged.get("propertyId"));
        assertEquals(existing.lotNumber(), logged.get("lotNumber"));
        assertFalse(logged.containsKey("deleted_at"));
    }

    @Test
    void deleteLot_shouldReturnFalse_andNotRecordAudit_whenNotExists() {
        // Arrange
        UUID unknownUuid = UUID.randomUUID();
        when(lotRepository.findById(unknownUuid)).thenReturn(Optional.empty());

        // Act
        boolean result = lotService.deleteLot(unknownUuid);

        // Assert
        assertFalse(result);
        verify(lotRepository, never()).softDelete(any());
        verifyNoInteractions(auditService);
        verifyNoInteractions(lotPermissibleAgreementTypeRepository);
    }

    // ---- patch ---------------------------------------------------------------

    @Test
    void patchLot_shouldReturnUpdatedLot_andRecordAudit_whenFieldChanges() {
        // Arrange
        UUID propertyId = UUID.randomUUID();
        LotRow before = stubRow(propertyId);
        LotRow after = new LotRow(
                before.uuid(), propertyId, true, null,
                "14", before.lotAddress(), "3005051", before.description(), before.notes(), before.sortOrder(),
                before.shapeData(), before.createdAt(), null
        );
        when(lotRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(lotRepository.patch(eq(before.uuid()), any())).thenReturn(Optional.of(after));

        List<LotPermissibleAgreementTypeRow> agreementTypes =
                List.of(stubAgreementType(before.uuid(), AgreementType.RESIDENTIAL, "350.00", "400.00"));
        when(lotPermissibleAgreementTypeRepository.findByLotId(before.uuid())).thenReturn(agreementTypes);

        Map<String, Object> changes = new HashMap<>();
        changes.put("lot_number", "14");
        changes.put("lot_parcel", "3005051");

        // Act
        Optional<Lot> result = lotService.patchLot(before.uuid(), changes);

        // Assert
        assertTrue(result.isPresent());
        assertEquals("14", result.get().lotNumber());
        // Patched lots pick up their current permissible agreement types, since
        // patching the base row doesn't touch that table.
        assertEquals(1, result.get().permissibleAgreementTypes().size());
        assertEquals(AgreementType.RESIDENTIAL, result.get().permissibleAgreementTypes().get(0).agreementType());

        // Only lotNumber and lotParcel moved, so the log must not carry the untouched fields.
        AuditMapper.Diff logged = capturedUpdate(before.uuid());
        assertEquals(Set.of("lotNumber", "lotParcel"), logged.before().keySet());
        assertEquals("12", logged.before().get("lotNumber"));
        assertEquals(Set.of("lotNumber", "lotParcel"), logged.after().keySet());
        assertEquals("14", logged.after().get("lotNumber"));
        assertEquals("3005051", logged.after().get("lotParcel"));
    }

    @Test
    void patchLot_shouldNotRecordAudit_whenNoDiffProduced() {
        // Arrange
        LotRow before = stubRow(UUID.randomUUID());
        // A separate instance carrying the same values: the diff has to come out empty on
        // value equality, not because the stub handed back the very same object.
        LotRow after = new LotRow(
                before.uuid(), before.propertyId(), before.isRentable(), before.notRentableReason(),
                before.lotNumber(), before.lotAddress(), before.lotParcel(), before.description(), before.notes(),
                before.sortOrder(), before.shapeData(), before.createdAt(), before.deletedAt()
        );
        when(lotRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(lotRepository.patch(eq(before.uuid()), any())).thenReturn(Optional.of(after));
        when(lotPermissibleAgreementTypeRepository.findByLotId(before.uuid())).thenReturn(List.of());

        Map<String, Object> changes = new HashMap<>();
        changes.put("lot_number", before.lotNumber());

        // Act
        lotService.patchLot(before.uuid(), changes);

        // Assert
        verifyNoInteractions(auditService);
    }

    @Test
    void patchLot_shouldReturnEmpty_andNotRecordAudit_whenNotExists() {
        // Arrange
        UUID unknownUuid = UUID.randomUUID();
        when(lotRepository.findById(unknownUuid)).thenReturn(Optional.empty());

        Map<String, Object> changes = new HashMap<>();
        changes.put("lot_number", "14");

        // Act
        Optional<Lot> result = lotService.patchLot(unknownUuid, changes);

        // Assert
        assertTrue(result.isEmpty());
        verify(lotRepository, never()).patch(any(), any());
        verifyNoInteractions(auditService);
        verifyNoInteractions(lotPermissibleAgreementTypeRepository);
    }

    // ---- reads ---------------------------------------------------------------

    @Test
    void findById_shouldReturnLot_withMergedAgreementTypes_whenFound() {
        // Arrange
        LotRow existing = stubRow(UUID.randomUUID());
        when(lotRepository.findById(existing.uuid())).thenReturn(Optional.of(existing));
        List<LotPermissibleAgreementTypeRow> agreementTypes =
                List.of(stubAgreementType(existing.uuid(), AgreementType.STORAGE, "80.00", "95.00"));
        when(lotPermissibleAgreementTypeRepository.findByLotId(existing.uuid())).thenReturn(agreementTypes);

        // Act
        Optional<Lot> result = lotService.findById(existing.uuid());

        // Assert
        assertTrue(result.isPresent());
        assertEquals(existing.uuid(), result.get().uuid());
        assertEquals(1, result.get().permissibleAgreementTypes().size());
        assertEquals(AgreementType.STORAGE, result.get().permissibleAgreementTypes().get(0).agreementType());
        assertEquals(0, new BigDecimal("95.00").compareTo(
                result.get().askingRateFor(AgreementType.STORAGE).orElseThrow()));
    }

    @Test
    void findById_shouldReturnEmpty_whenNotFound() {
        // Arrange
        UUID unknownUuid = UUID.randomUUID();
        when(lotRepository.findById(unknownUuid)).thenReturn(Optional.empty());

        // Act
        Optional<Lot> result = lotService.findById(unknownUuid);

        // Assert
        assertTrue(result.isEmpty());
        verifyNoInteractions(lotPermissibleAgreementTypeRepository);
    }

    @Test
    void findByProperty_shouldBatchAgreementTypeLookup_andMergePerLot() {
        // Arrange: two lots, only one of which has a permissible agreement type, to
        // confirm the grouping doesn't just zip results back in list order.
        UUID propertyId = UUID.randomUUID();
        LotRow lotA = stubRow(UUID.randomUUID(), propertyId);
        LotRow lotB = stubRow(UUID.randomUUID(), propertyId);
        when(lotRepository.findByProperty("PARK1")).thenReturn(List.of(lotA, lotB));
        when(lotPermissibleAgreementTypeRepository.findByLotIds(List.of(lotA.uuid(), lotB.uuid())))
                .thenReturn(List.of(stubAgreementType(lotB.uuid(), AgreementType.COMMERCIAL, "500.00", "550.00")));

        // Act
        List<Lot> result = lotService.findByProperty("PARK1");

        // Assert
        assertEquals(2, result.size());
        Lot resultA = result.stream().filter(l -> l.uuid().equals(lotA.uuid())).findFirst().orElseThrow();
        Lot resultB = result.stream().filter(l -> l.uuid().equals(lotB.uuid())).findFirst().orElseThrow();
        assertEquals(List.of(), resultA.permissibleAgreementTypes());
        assertEquals(1, resultB.permissibleAgreementTypes().size());
        assertEquals(AgreementType.COMMERCIAL, resultB.permissibleAgreementTypes().get(0).agreementType());

        // One batched call for both lots, not one call per lot.
        verify(lotPermissibleAgreementTypeRepository, times(1)).findByLotIds(any());
    }

    @Test
    void findByProperty_shouldReturnEmptyList_withoutQueryingAgreementTypes_whenNoLotsFound() {
        // Arrange
        when(lotRepository.findByProperty("EMPTY")).thenReturn(List.of());

        // Act
        List<Lot> result = lotService.findByProperty("EMPTY");

        // Assert
        assertEquals(List.of(), result);
        verifyNoInteractions(lotPermissibleAgreementTypeRepository);
    }

    @Test
    void findByPropertyPermitting_shouldDropTheLotsThatCannotHostThatDeal() {
        // Arrange -- step one of the owner's pricing flow
        UUID propertyId = UUID.randomUUID();
        LotRow landLot = stubRow(UUID.randomUUID(), propertyId);
        LotRow storageLot = stubRow(UUID.randomUUID(), propertyId);
        when(lotRepository.findByProperty("PARK1")).thenReturn(List.of(landLot, storageLot));
        when(lotPermissibleAgreementTypeRepository.findByLotIds(List.of(landLot.uuid(), storageLot.uuid())))
                .thenReturn(List.of(
                        stubAgreementType(landLot.uuid(), AgreementType.LAND, "650.00", "725.00"),
                        stubAgreementType(storageLot.uuid(), AgreementType.STORAGE, "80.00", "95.00")));

        // Act
        List<Lot> result = lotService.findByPropertyPermitting("PARK1", AgreementType.LAND);

        // Assert
        assertEquals(1, result.size());
        assertEquals(landLot.uuid(), result.get(0).uuid());
    }

    // ---- Fixtures ------------------------------------------------------------

    // Grabs the before/after maps handed to AuditService.recordUpdate so tests can
    // assert the log holds only the fields that actually changed.
    @SuppressWarnings("unchecked")
    private AuditMapper.Diff capturedUpdate(UUID uuid) {
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditService).recordUpdate(eq("lot"), eq(uuid), before.capture(), after.capture());
        return new AuditMapper.Diff(before.getValue(), after.getValue());
    }

    @SuppressWarnings("unchecked")
    private AuditMapper.Diff capturedPermissibleUpdate() {
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditService).recordUpdate(
                eq("lot_permissible_agreement_type"), any(), before.capture(), after.capture());
        return new AuditMapper.Diff(before.getValue(), after.getValue());
    }

    private LotRow stubRow(UUID propertyId) {
        return stubRow(UUID.randomUUID(), propertyId);
    }

    private LotRow stubRow(UUID uuid, UUID propertyId) {
        return new LotRow(
                uuid, propertyId, true, null,
                "12", "123 Main St", null, "Rental lot", "Front row", 1,
                null, OffsetDateTime.now(ZoneOffset.UTC), null
        );
    }

    // A persisted permissible row, so it carries the uuid the audit trail hangs off.
    private LotPermissibleAgreementTypeRow stubAgreementType(
            UUID lotId, AgreementType type, String targetRate, String askingRate) {
        return new LotPermissibleAgreementTypeRow(
                UUID.randomUUID(), lotId, type, new BigDecimal(targetRate), new BigDecimal(askingRate));
    }

    /** A property-level template carrying both figures. */
    private static TermsTemplate template(BigDecimal targetRate, BigDecimal askingRate) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new TermsTemplate(
                UUID.randomUUID(), UUID.randomUUID(), null, "Test Terms",
                AgreementType.LAND, targetRate, askingRate,
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
}