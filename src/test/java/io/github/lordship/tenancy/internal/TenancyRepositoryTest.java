package io.github.lordship.tenancy.internal;

import io.github.lordship.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
public class TenancyRepositoryTest extends IntegrationTest {

    @Autowired
    TenancyRepository tenancyRepository;

    @Autowired
    JdbcClient jdbc;

    private UUID lot(String propertyCode) {
        return testData.insertLot(testData.insertProperty(propertyCode).uuid(), "1").uuid();
    }

    private UUID lot(String propertyCode, String lotNumber) {
        return testData.insertLot(testData.insertProperty(propertyCode).uuid(), lotNumber).uuid();
    }

    // Straight to the column: LotRow's compact constructor refuses to build a
    // not-rentable lot without a reason, and the lots module is not under test here.
    private void makeNotRentable(UUID lotId, String reason) {
        jdbc.sql("""
                        UPDATE lot
                           SET is_rentable = FALSE, not_rentable_reason = :reason
                         WHERE uuid = :uuid
                        """)
                .param("reason", reason)
                .param("uuid", lotId)
                .update();
    }

    private int clearEndDate(UUID tenancyId) {
        return jdbc.sql("UPDATE tenancy SET end_date = NULL WHERE uuid = :uuid")
                .param("uuid", tenancyId)
                .update();
    }

    private Set<UUID> uuids(List<TenancyRow> rows) {
        return rows.stream().map(TenancyRow::uuid).collect(Collectors.toSet());
    }

    // ---- save ---------------------------------------------------------------

    @Test
    void save_shouldPersistMinimalRow_andApplyDefaults() {
        // Arrange
        UUID lotId = lot("T001");

        // Act
        TenancyRow saved = tenancyRepository.save(lotId);

        // Assert
        assertNotNull(saved.uuid());
        assertEquals(lotId, saved.lotId());

        // Possession and departure are both unknown at creation -- the tenancy
        // row exists before anyone has agreed a date.
        assertNull(saved.startDate());
        assertNull(saved.endDate());

        assertFalse(saved.noPersonalChecks());
        assertFalse(saved.noPartialPayments());
        assertTrue(saved.acceptPayments());
        assertFalse(saved.exemptFromLateFees());

        assertNotNull(saved.createdAt());
        assertNull(saved.deletedAt());
    }

    @Test
    void save_shouldFail_whenLotDoesNotExist() {
        // Act & Assert: the lot_id foreign key
        assertThrows(DataIntegrityViolationException.class,
                () -> tenancyRepository.save(UUID.randomUUID()));
    }

    // ---- findById -----------------------------------------------------------

    @Test
    void findById_shouldReturnRow_whenExists() {
        // Arrange
        TenancyRow saved = tenancyRepository.save(lot("T002"));

        // Act
        Optional<TenancyRow> found = tenancyRepository.findById(saved.uuid());

        // Assert
        assertTrue(found.isPresent());
        assertEquals(saved.uuid(), found.get().uuid());
    }

    @Test
    void findById_shouldReturnEmpty_whenUuidIsUnknown() {
        // Act & Assert
        assertTrue(tenancyRepository.findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    void findById_shouldStillReturnRow_afterItIsClosed() {
        // Arrange: closing is not deleting -- a finished tenancy stays readable
        TenancyRow saved = tenancyRepository.save(lot("T003"));
        tenancyRepository.close(saved.uuid(), LocalDate.now());

        // Act
        Optional<TenancyRow> found = tenancyRepository.findById(saved.uuid());

        // Assert
        assertTrue(found.isPresent());
        assertEquals(LocalDate.now(), found.get().endDate());
    }

    @Test
    void findById_shouldReturnEmpty_afterSoftDelete() {
        // Arrange
        TenancyRow saved = tenancyRepository.save(lot("T004"));

        // Act
        tenancyRepository.softDelete(saved.uuid());

        // Assert
        assertTrue(tenancyRepository.findById(saved.uuid()).isEmpty());
    }

    // ---- findActiveByLot ----------------------------------------------------

    @Test
    void findActiveByLot_shouldReturnBoth_whenTwoTenanciesOverlap() {
        // Arrange: the handover window -- outgoing and incoming at once
        UUID lotId = lot("T005");
        TenancyRow outgoing = tenancyRepository.save(lotId);
        TenancyRow incoming = tenancyRepository.save(lotId);

        // Act
        List<TenancyRow> active = tenancyRepository.findActiveByLot(lotId);

        // Assert
        assertEquals(2, active.size());
        assertEquals(Set.of(outgoing.uuid(), incoming.uuid()), uuids(active));
    }

    @Test
    void findActiveByLot_shouldExcludeClosedTenancies() {
        // Arrange
        UUID lotId = lot("T006");
        TenancyRow closed = tenancyRepository.save(lotId);
        tenancyRepository.close(closed.uuid(), LocalDate.now());
        TenancyRow open = tenancyRepository.save(lotId);

        // Act
        List<TenancyRow> active = tenancyRepository.findActiveByLot(lotId);

        // Assert
        assertEquals(1, active.size());
        assertEquals(open.uuid(), active.get(0).uuid());
    }

    @Test
    void findActiveByLot_shouldExcludeSoftDeletedTenancies() {
        // Arrange
        UUID lotId = lot("T007");
        TenancyRow deleted = tenancyRepository.save(lotId);
        tenancyRepository.softDelete(deleted.uuid());
        TenancyRow open = tenancyRepository.save(lotId);

        // Act
        List<TenancyRow> active = tenancyRepository.findActiveByLot(lotId);

        // Assert
        assertEquals(1, active.size());
        assertEquals(open.uuid(), active.get(0).uuid());
    }

    @Test
    void findActiveByLot_shouldNotLeakAcrossLots() {
        // Arrange
        UUID lotA = lot("T008", "1");
        UUID lotB = lot("T009", "2");
        TenancyRow onA = tenancyRepository.save(lotA);
        tenancyRepository.save(lotB);

        // Act
        List<TenancyRow> active = tenancyRepository.findActiveByLot(lotA);

        // Assert
        assertEquals(1, active.size());
        assertEquals(onA.uuid(), active.get(0).uuid());
    }

    @Test
    void findActiveByLot_shouldReturnEmpty_whenLotIsUnknown() {
        // Act & Assert
        assertTrue(tenancyRepository.findActiveByLot(UUID.randomUUID()).isEmpty());
    }

    // ---- close --------------------------------------------------------------

    @Test
    void close_shouldSetEndDate_andReturnTheUpdatedRow() {
        // Arrange
        TenancyRow saved = tenancyRepository.save(lot("T010"));
        LocalDate end = LocalDate.now();

        // Act
        TenancyRow closed = tenancyRepository.close(saved.uuid(), end);

        // Assert
        assertEquals(saved.uuid(), closed.uuid());
        assertEquals(end, closed.endDate());
    }

    // No guard at this layer: refusing to re-close is TenancyService's job, and
    // enforceSecondTenancyLimit calls straight through here.
    @Test
    void close_shouldOverwriteAnExistingEndDate() {
        // Arrange
        TenancyRow saved = tenancyRepository.save(lot("T011"));
        tenancyRepository.close(saved.uuid(), LocalDate.now().minusDays(5));

        // Act
        TenancyRow reclosed = tenancyRepository.close(saved.uuid(), LocalDate.now());

        // Assert
        assertEquals(LocalDate.now(), reclosed.endDate());
    }

    @Test
    void close_shouldThrow_whenTenancyIsSoftDeleted() {
        // Arrange
        TenancyRow saved = tenancyRepository.save(lot("T012"));
        tenancyRepository.softDelete(saved.uuid());

        // Act & Assert: the query filters deleted rows and asks for .single()
        assertThrows(EmptyResultDataAccessException.class,
                () -> tenancyRepository.close(saved.uuid(), LocalDate.now()));
    }

    // ---- softDelete ---------------------------------------------------------

    @Test
    void softDelete_shouldReturnTrue_thenFalseOnASecondCall() {
        // Arrange
        TenancyRow saved = tenancyRepository.save(lot("T013"));

        // Act & Assert: the boolean is rows-affected, which is what lets the
        // service audit only a delete that actually changed something.
        assertTrue(tenancyRepository.softDelete(saved.uuid()));
        assertFalse(tenancyRepository.softDelete(saved.uuid()));
    }

    @Test
    void softDelete_shouldReturnFalse_whenUuidIsUnknown() {
        // Act & Assert
        assertFalse(tenancyRepository.softDelete(UUID.randomUUID()));
    }

    // ---- patch --------------------------------------------------------------

    // Every column the service can hand to patch() from the PATCH endpoint, in one
    // call: start_date, end_date, notes and the four payment flags. Each value
    // differs from the column's default, so an ignored column cannot pass.
    @Test
    void patch_shouldUpdateEveryEditableColumn_andPersistThem() {
        // Arrange
        TenancyRow saved = tenancyRepository.save(lot("T014"));
        LocalDate start = LocalDate.now().minusDays(30);
        LocalDate end = LocalDate.now();
        Map<String, Object> changes = Map.of(
                "start_date", start,
                "end_date", end,
                "notes", "Called re: late rent",
                "no_personal_checks", true,
                "no_partial_payments", true,
                "accept_payments", false,
                "exempt_from_late_fees", true
        );

        // Act
        Optional<TenancyRow> patched = tenancyRepository.patch(saved.uuid(), changes);

        // Assert
        assertTrue(patched.isPresent());
        TenancyRow row = patched.get();
        assertEquals(start, row.startDate());
        assertEquals(end, row.endDate());
        assertEquals("Called re: late rent", row.notes());
        assertTrue(row.noPersonalChecks());
        assertTrue(row.noPartialPayments());
        assertFalse(row.acceptPayments());
        assertTrue(row.exemptFromLateFees());

        // What came back is what is stored, not just what the UPDATE echoed
        assertEquals(row, tenancyRepository.findById(saved.uuid()).orElseThrow());
    }

    @Test
    void patch_shouldLeaveOtherColumnsAlone_whenPatchingOne() {
        // Arrange
        TenancyRow saved = tenancyRepository.save(lot("T031"));

        // Act
        Optional<TenancyRow> patched = tenancyRepository.patch(
                saved.uuid(), Map.of("notes", "Called re: late rent"));

        // Assert
        assertTrue(patched.isPresent());
        TenancyRow row = patched.get();
        assertEquals("Called re: late rent", row.notes());
        assertEquals(saved.lotId(), row.lotId());
        assertEquals(saved.startDate(), row.startDate());
        assertEquals(saved.endDate(), row.endDate());
        assertEquals(saved.noPersonalChecks(), row.noPersonalChecks());
        assertEquals(saved.noPartialPayments(), row.noPartialPayments());
        assertEquals(saved.acceptPayments(), row.acceptPayments());
        assertEquals(saved.exemptFromLateFees(), row.exemptFromLateFees());
        assertEquals(saved.createdAt(), row.createdAt());
    }

    @Test
    void patch_shouldClearEndDate_whenGivenNull() {
        // Arrange
        TenancyRow saved = tenancyRepository.save(lot("T015"));
        tenancyRepository.close(saved.uuid(), LocalDate.now());

        Map<String, Object> changes = new HashMap<>();
        changes.put("end_date", null);

        // Act
        Optional<TenancyRow> patched = tenancyRepository.patch(saved.uuid(), changes);

        // Assert
        assertTrue(patched.isPresent());
        assertNull(patched.get().endDate());
    }

    @Test
    void patch_shouldMoveATenancy_toAnotherLot() {
        // Arrange
        UUID origin = lot("T016", "1");
        UUID destination = lot("T017", "2");
        TenancyRow saved = tenancyRepository.save(origin);

        // Act
        Optional<TenancyRow> patched = tenancyRepository.patch(
                saved.uuid(), Map.of("lot_id", destination));

        // Assert
        assertTrue(patched.isPresent());
        assertEquals(destination, patched.get().lotId());
        assertTrue(tenancyRepository.findActiveByLot(origin).isEmpty());
    }

    @Test
    void patch_shouldReturnUnchangedRow_withEmptyChanges() {
        // Arrange
        TenancyRow saved = tenancyRepository.save(lot("T018"));

        // Act
        Optional<TenancyRow> patched = tenancyRepository.patch(saved.uuid(), Map.of());

        // Assert
        assertTrue(patched.isPresent());
        assertEquals(saved.uuid(), patched.get().uuid());
        assertNull(patched.get().endDate());
    }

    @Test
    void patch_shouldThrow_whenColumnIsNotAllowed() {
        // Arrange
        TenancyRow saved = tenancyRepository.save(lot("T019"));

        // Act & Assert: @Repository exception translation wraps the
        // IllegalArgumentException.
        assertThrows(InvalidDataAccessApiUsageException.class,
                () -> tenancyRepository.patch(saved.uuid(), Map.of("created_at", "2025-01-01")));
    }

    // anniversary_on is sticky -- established once by the first lease or the
    // first payment, never edited through the generic patch bag.
    @Test
    void patch_shouldThrow_whenAnniversaryColumnRequested() {
        // Arrange
        TenancyRow saved = tenancyRepository.save(lot("T020"));

        // Act & Assert
        assertThrows(InvalidDataAccessApiUsageException.class,
                () -> tenancyRepository.patch(saved.uuid(), Map.of("anniversary_on", LocalDate.now())));
    }

    @Test
    void patch_shouldReturnEmpty_whenRowIsSoftDeleted() {
        // Arrange
        TenancyRow saved = tenancyRepository.save(lot("T021"));
        tenancyRepository.softDelete(saved.uuid());

        // Act
        Optional<TenancyRow> patched = tenancyRepository.patch(
                saved.uuid(), Map.of("start_date", LocalDate.now()));

        // Assert
        assertTrue(patched.isEmpty());
    }

    // ---- schema backstops ---------------------------------------------------
    // The service is not the only way rows reach this table. Each refusal below
    // aborts its transaction, so nothing may follow the assertion in its test.

    @Test
    void save_shouldBeRefusedByTrigger_whenLotIsNotRentable() {
        // Arrange
        UUID lotId = lot("T022");
        makeNotRentable(lotId, "condemned after the flood");

        // Act & Assert
        assertThrows(DataIntegrityViolationException.class,
                () -> tenancyRepository.save(lotId));
    }

    @Test
    void save_shouldBeRefusedByTrigger_onAThirdActiveTenancy() {
        // Arrange
        UUID lotId = lot("T023");
        tenancyRepository.save(lotId);
        tenancyRepository.save(lotId);

        // Act & Assert
        assertThrows(DataIntegrityViolationException.class,
                () -> tenancyRepository.save(lotId));
    }

    @Test
    void save_shouldBeAllowed_whenTheOtherTenanciesAreClosed() {
        // Arrange: two finished tenancies do not consume the lot's two slots
        UUID lotId = lot("T024");
        tenancyRepository.close(tenancyRepository.save(lotId).uuid(), LocalDate.now().minusYears(2));
        tenancyRepository.close(tenancyRepository.save(lotId).uuid(), LocalDate.now().minusYears(1));

        // Act
        TenancyRow saved = tenancyRepository.save(lotId);

        // Assert
        assertNotNull(saved.uuid());
        assertEquals(1, tenancyRepository.findActiveByLot(lotId).size());
    }

    @Test
    void clearingEndDate_shouldBeRefusedByTrigger_whenTheLotIsFull() {
        // Arrange: trg_tenancy_active_limit watches end_date, not just lot_id,
        // so reopening cannot smuggle a third tenancy onto the lot.
        UUID lotId = lot("T025");
        UUID first = tenancyRepository.save(lotId).uuid();
        tenancyRepository.close(first, LocalDate.now());
        tenancyRepository.save(lotId);
        tenancyRepository.save(lotId);

        // Act & Assert
        assertThrows(DataIntegrityViolationException.class, () -> clearEndDate(first));
    }

    @Test
    void clearingEndDate_shouldBeAllowed_onANotRentableLot() {
        // Arrange: a lot that went out of service keeps its tenants, and their
        // dates stay correctable -- trg_tenancy_lot_must_be_rentable governs
        // arrivals only.
        UUID lotId = lot("T026");
        UUID first = tenancyRepository.save(lotId).uuid();
        tenancyRepository.close(first, LocalDate.now());
        makeNotRentable(lotId, "held for the road widening");

        // Act & Assert
        assertEquals(1, clearEndDate(first));
    }

    @Test
    void patch_shouldBeRefusedByTrigger_whenMovingOntoANotRentableLot() {
        // Arrange
        UUID origin = lot("T027", "1");
        UUID destination = lot("T028", "2");
        makeNotRentable(destination, "condemned after the flood");
        TenancyRow saved = tenancyRepository.save(origin);

        // Act & Assert
        assertThrows(DataIntegrityViolationException.class,
                () -> tenancyRepository.patch(saved.uuid(), Map.of("lot_id", destination)));
    }

    @Test
    void patch_shouldBeRefusedByTrigger_whenMovingOntoAFullLot() {
        // Arrange
        UUID origin = lot("T029", "1");
        UUID destination = lot("T030", "2");
        tenancyRepository.save(destination);
        tenancyRepository.save(destination);
        TenancyRow saved = tenancyRepository.save(origin);

        // Act & Assert
        assertThrows(DataIntegrityViolationException.class,
                () -> tenancyRepository.patch(saved.uuid(), Map.of("lot_id", destination)));
    }
}