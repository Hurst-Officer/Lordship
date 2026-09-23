package io.github.lordship.tenancy;

import io.github.lordship.accounts.AccountService;
import io.github.lordship.audit.AuditService;
import io.github.lordship.lots.Lot;
import io.github.lordship.lots.LotService;
import io.github.lordship.tenancy.internal.TenancyRepository;
import io.github.lordship.tenancy.internal.TenancyRow;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TenancyServiceTests {
    private TenancyRepository tenancyRepository;
    private AuditService auditService;
    private AccountService accountService;
    private LotService lotService;
    private TenancyService tenancyService;

    private UUID lotId;
    private UUID uuid1;
    private UUID uuid2;
    private UUID uuid3;

    @BeforeEach
    void setup() {
        tenancyRepository = mock(TenancyRepository.class);
        auditService = mock(AuditService.class);
        accountService = mock(AccountService.class);
        lotService = mock(LotService.class);

        tenancyService = new TenancyService(
                tenancyRepository,
                auditService,
                accountService,
                lotService
        );

        lotId = UUID.randomUUID();
        uuid1 = UUID.randomUUID();
        uuid2 = UUID.randomUUID();
        uuid3 = UUID.randomUUID();
    }

    private TenancyRow row(UUID id, LocalDate start, LocalDate end) {
        return new TenancyRow(
                id,
                lotId,
                start,
                end,
                false,
                false,
                true,
                false,
                "",
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(10).truncatedTo(ChronoUnit.DAYS),
                null
        );
    }

    private Lot lot(boolean isRentable, String notRentableReason) {
        return new Lot(
                lotId,
                UUID.randomUUID(),
                isRentable,
                notRentableReason,
                "14",
                null,
                null,
                null,
                null,
                1,
                null,
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                List.of()
        );
    }

    // Stubbed per test rather than in setup: MockitoExtension is strict, and the
    // tests that never reach create() would fail on an unused stub.
    private void lotIsRentable() {
        when(lotService.findById(lotId)).thenReturn(Optional.of(lot(true, null)));
    }

    // Map.of rejects null values, and clearing an end date is exactly a null.
    private static Map<String, Object> change(String key, Object value) {
        Map<String, Object> m = new HashMap<>();
        m.put(key, value);
        return m;
    }

    // The same row as row(uuid1, now - 20 days, null), with one column replaced by
    // the patched value. Every value in editableColumns() differs from these
    // defaults, so before/after always produce a non-empty audit diff.
    private TenancyRow afterEdit(String column, Object value) {
        return new TenancyRow(
                uuid1,
                lotId,
                column.equals("start_date") ? LocalDate.parse((String) value) : LocalDate.now().minusDays(20),
                column.equals("end_date") ? LocalDate.parse((String) value) : null,
                column.equals("no_personal_checks") ? (Boolean) value : false,
                column.equals("no_partial_payments") ? (Boolean) value : false,
                column.equals("accept_payments") ? (Boolean) value : true,
                column.equals("exempt_from_late_fees") ? (Boolean) value : false,
                column.equals("notes") ? (String) value : "",
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(10).truncatedTo(ChronoUnit.DAYS),
                null
        );
    }

    // One row per column the PATCH endpoint can write, keyed the way the controller
    // hands them to the service. Dates are relative to today so they stay valid
    // against the start/end ordering checks (the row under test starts 20 days ago
    // and is open). Add a row here whenever patchTenancy() gains a column.
    static Stream<Arguments> editableColumns() {
        return Stream.of(
                Arguments.of("start_date", LocalDate.now().minusDays(30).toString()),
                Arguments.of("end_date", LocalDate.now().toString()),
                Arguments.of("notes", "Called re: late rent"),
                Arguments.of("no_partial_payments", true),
                Arguments.of("no_personal_checks", true),
                Arguments.of("accept_payments", false),
                Arguments.of("exempt_from_late_fees", true)
        );
    }


    @Test
    void create_allowsFirstTenancy() {
        // Arrange
        lotIsRentable();
        when(tenancyRepository.findActiveByLot(lotId)).thenReturn(List.of());

        TenancyRow saved = row(uuid1, LocalDate.now(), null);
        when(tenancyRepository.save(any())).thenReturn(saved);

        // Act
        Tenancy result = tenancyService.create(lotId);

        // Assert
        assertEquals(uuid1, result.uuid());
        verify(auditService).recordInsert(eq("tenancy"), eq(uuid1), any());
    }

    // The overlap the office actually works in: the outgoing tenancy is still
    // open while the incoming one is set up.
    @Test
    void create_allowsSecondTenancy() {
        // Arrange
        lotIsRentable();
        TenancyRow existing = row(uuid1, LocalDate.now().minusDays(10), null);
        when(tenancyRepository.findActiveByLot(lotId)).thenReturn(List.of(existing));

        TenancyRow saved = row(uuid2, LocalDate.now(), null);
        when(tenancyRepository.save(any())).thenReturn(saved);

        // Act
        Tenancy result = tenancyService.create(lotId);

        // Assert
        assertEquals(uuid2, result.uuid());
        verify(auditService).recordInsert(eq("tenancy"), eq(uuid2), any());
    }

    @Test
    void create_rejectsThirdTenancy() {
        // Arrange
        lotIsRentable();
        TenancyRow t1 = row(uuid1, LocalDate.now().minusDays(10), null);
        TenancyRow t2 = row(uuid2, LocalDate.now().minusDays(5), null);

        when(tenancyRepository.findActiveByLot(lotId)).thenReturn(List.of(t1, t2));

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> tenancyService.create(lotId));
        verify(tenancyRepository, never()).save(any());
    }

    @Test
    void create_rejectsNotRentableLot() {
        // Arrange
        when(lotService.findById(lotId))
                .thenReturn(Optional.of(lot(false, "condemned after the flood")));

        // Act
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> tenancyService.create(lotId));

        // Assert
        assertTrue(e.getMessage().contains("condemned after the flood"));
        verify(tenancyRepository, never()).findActiveByLot(any());
        verify(tenancyRepository, never()).save(any());
    }

    @Test
    void create_rejectsUnknownLot() {
        // Arrange
        when(lotService.findById(lotId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(EntityNotFoundException.class, () -> tenancyService.create(lotId));
        verify(tenancyRepository, never()).save(any());
    }

    @Test
    void findActiveTenancyByLot_returnsMappedTenancies() {
        // Arrange
        TenancyRow r1 = row(uuid1, LocalDate.now(), null);
        TenancyRow r2 = row(uuid2, LocalDate.now(), null);

        when(tenancyRepository.findActiveByLot(lotId)).thenReturn(List.of(r1, r2));

        // Act
        List<Tenancy> result = tenancyService.findActiveTenancyByLot(lotId);

        // Assert
        assertEquals(2, result.size());
        assertEquals(uuid1, result.get(0).uuid());
    }

    @Test
    void findTenancyById_returnsMappedTenancy() {
        // Arrange
        TenancyRow r = row(uuid1, LocalDate.now(), null);
        when(tenancyRepository.findById(uuid1)).thenReturn(Optional.of(r));

        // Act
        Optional<Tenancy> result = tenancyService.findTenancyById(uuid1);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(uuid1, result.get().uuid());
    }

    @Test
    void enforceSecondTenancyLimit_closesSecondTenancyAfterOneMonth() {
        // Arrange
        TenancyRow first = row(uuid1, LocalDate.now().minusMonths(2), null);
        TenancyRow second = row(uuid2, LocalDate.now().minusMonths(1).minusDays(1), null);

        when(tenancyRepository.findActiveByLot(lotId)).thenReturn(List.of(first, second));

        TenancyRow closed = row(uuid2, second.startDate(), LocalDate.now());
        when(tenancyRepository.close(eq(uuid2), any())).thenReturn(closed);

        // Act
        tenancyService.enforceSecondTenancyLimit(lotId);

        // Assert
        verify(tenancyRepository).close(eq(uuid2), any());
        verify(auditService).recordUpdate(eq("tenancy"), eq(uuid2), any(), any());
    }

    @Test
    void enforceSecondTenancyLimit_doesNothingIfSecondTenancyIsNew() {
        // Arrange
        TenancyRow first = row(uuid1, LocalDate.now().minusMonths(2), null);
        TenancyRow second = row(uuid2, LocalDate.now().minusDays(10), null);

        when(tenancyRepository.findActiveByLot(lotId)).thenReturn(List.of(first, second));

        // Act
        tenancyService.enforceSecondTenancyLimit(lotId);

        // Assert
        verify(tenancyRepository, never()).close(any(), any());
        verify(auditService, never()).recordUpdate(any(), any(), any(), any());
    }

    // A tenancy created but not yet given its possession date has a null
    // start_date, which is the normal state during the overlap window.
    @Test
    void enforceSecondTenancyLimit_doesNothingWhenAStartDateIsMissing() {
        // Arrange
        TenancyRow first = row(uuid1, LocalDate.now().minusMonths(2), null);
        TenancyRow second = row(uuid2, null, null);

        when(tenancyRepository.findActiveByLot(lotId)).thenReturn(List.of(first, second));

        // Act & Assert
        assertDoesNotThrow(() -> tenancyService.enforceSecondTenancyLimit(lotId));
        verify(tenancyRepository, never()).close(any(), any());
    }

    @Test
    void enforceSecondTenancyLimit_doesNothingWithFewerThanTwoTenancies() {
        // Arrange
        when(tenancyRepository.findActiveByLot(lotId)).thenReturn(List.of());

        // Act & Assert
        assertDoesNotThrow(() -> tenancyService.enforceSecondTenancyLimit(lotId));
        verify(tenancyRepository, never()).close(any(), any());
    }

    // ---- end_date as a state transition -------------------------------------

    @Test
    void patchTenancy_closesTenancy_whenEndDateIsSet() {
        // Arrange
        LocalDate end = LocalDate.now();
        TenancyRow before = row(uuid1, LocalDate.now().minusDays(20), null);
        TenancyRow after = row(uuid1, before.startDate(), end);

        when(tenancyRepository.findById(uuid1)).thenReturn(Optional.of(before));
        when(tenancyRepository.patch(eq(uuid1), any())).thenReturn(Optional.of(after));

        // Act
        Optional<Tenancy> result = tenancyService.patchTenancy(uuid1, change("end_date", end.toString()));

        // Assert
        assertTrue(result.isPresent());
        assertEquals(end, result.get().endDate());
        verify(auditService).recordUpdate(eq("tenancy"), eq(uuid1), any(), any());
    }

    // A figure someone typed wrong, not a state change.
    @Test
    void patchTenancy_correctsAnExistingEndDate() {
        // Arrange
        LocalDate corrected = LocalDate.now().minusDays(3);
        TenancyRow before = row(uuid1, LocalDate.now().minusDays(20), LocalDate.now().minusDays(1));
        TenancyRow after = row(uuid1, before.startDate(), corrected);

        when(tenancyRepository.findById(uuid1)).thenReturn(Optional.of(before));
        when(tenancyRepository.patch(eq(uuid1), any())).thenReturn(Optional.of(after));

        // Act
        Optional<Tenancy> result = tenancyService.patchTenancy(uuid1, change("end_date", corrected.toString()));

        // Assert
        assertTrue(result.isPresent());
        assertEquals(corrected, result.get().endDate());
        verify(tenancyRepository, never()).findActiveByLot(any());
    }

    @Test
    void patchTenancy_reopensTenancy_whenLotHasRoom() {
        // Arrange
        TenancyRow before = row(uuid1, LocalDate.now().minusDays(20), LocalDate.now().minusDays(1));
        TenancyRow after = row(uuid1, before.startDate(), null);
        TenancyRow other = row(uuid2, LocalDate.now().minusDays(5), null);

        when(tenancyRepository.findById(uuid1)).thenReturn(Optional.of(before));
        when(tenancyRepository.findActiveByLot(lotId)).thenReturn(List.of(other));
        when(tenancyRepository.patch(eq(uuid1), any())).thenReturn(Optional.of(after));

        // Act
        Optional<Tenancy> result = tenancyService.patchTenancy(uuid1, change("end_date", null));

        // Assert
        assertTrue(result.isPresent());
        assertNull(result.get().endDate());
    }

    // Reopening is a third way onto a full lot, since create() never sees it.
    @Test
    void patchTenancy_rejectsReopen_whenLotAlreadyHasTwoActive() {
        // Arrange
        TenancyRow before = row(uuid1, LocalDate.now().minusDays(20), LocalDate.now().minusDays(1));
        TenancyRow other1 = row(uuid2, LocalDate.now().minusDays(10), null);
        TenancyRow other2 = row(uuid3, LocalDate.now().minusDays(5), null);

        when(tenancyRepository.findById(uuid1)).thenReturn(Optional.of(before));
        when(tenancyRepository.findActiveByLot(lotId)).thenReturn(List.of(other1, other2));

        // Act & Assert
        assertThrows(IllegalStateException.class,
                () -> tenancyService.patchTenancy(uuid1, change("end_date", null)));

        verify(tenancyRepository, never()).patch(any(), any());
    }

    @Test
    void patchTenancy_rejectsEndDateBeforeStartDate() {
        // Arrange
        TenancyRow before = row(uuid1, LocalDate.now().minusDays(20), null);

        when(tenancyRepository.findById(uuid1)).thenReturn(Optional.of(before));

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> tenancyService.patchTenancy(uuid1,
                        change("end_date", LocalDate.now().minusDays(30).toString())));

        verify(tenancyRepository, never()).patch(any(), any());
    }

    @Test
    void patchTenancy_rejectsStartDateAfterExistingEndDate() {
        // Arrange
        TenancyRow before = row(uuid1, LocalDate.now().minusDays(20), LocalDate.now().minusDays(10));

        when(tenancyRepository.findById(uuid1)).thenReturn(Optional.of(before));

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> tenancyService.patchTenancy(uuid1,
                        change("start_date", LocalDate.now().toString())));

        verify(tenancyRepository, never()).patch(any(), any());
    }

    // Replaces the old patchTenancy_updatesFields, which duplicated
    // patchTenancy_closesTenancy_whenEndDateIsSet and only ever touched end_date.
    // Each editable column must survive the service and reach the repository.
    @ParameterizedTest(name = "can edit {0}")
    @MethodSource("editableColumns")
    @SuppressWarnings("unchecked")
    void patchTenancy_canEditEachEditableColumn(String column, Object value) {
        // Arrange
        TenancyRow before = row(uuid1, LocalDate.now().minusDays(20), null);

        // The service audits only when before and after differ, so the row the
        // repository returns must carry the edit.
        TenancyRow after = afterEdit(column, value);

        when(tenancyRepository.findById(uuid1)).thenReturn(Optional.of(before));
        when(tenancyRepository.patch(eq(uuid1), any())).thenReturn(Optional.of(after));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

        // Act
        Optional<Tenancy> result = tenancyService.patchTenancy(uuid1, change(column, value));

        // Assert
        assertTrue(result.isPresent());
        verify(tenancyRepository).patch(eq(uuid1), captor.capture());
        assertTrue(captor.getValue().containsKey(column),
                column + " was dropped before reaching the repository");
        verify(auditService).recordUpdate(eq("tenancy"), eq(uuid1), any(), any());
    }

    @Test
    void patchTenancy_returnsEmptyIfNotFound() {
        // Arrange
        when(tenancyRepository.findById(uuid1)).thenReturn(Optional.empty());

        // Act
        Optional<Tenancy> result = tenancyService.patchTenancy(uuid1, Map.of("end_date", "2025-01-01"));

        // Assert
        assertTrue(result.isEmpty());
    }


    @Test
    void softDelete_deletesAndAudits() {
        // Arrange
        TenancyRow before = row(uuid1, LocalDate.now().minusDays(20), null);

        when(tenancyRepository.findById(uuid1)).thenReturn(Optional.of(before));
        when(tenancyRepository.softDelete(uuid1)).thenReturn(true);

        // Act
        boolean result = tenancyService.softDelete(uuid1);

        // Assert
        assertTrue(result);
        verify(tenancyRepository).softDelete(uuid1);
        verify(auditService).recordDelete(eq("tenancy"), eq(uuid1), any());
    }

    @Test
    void softDelete_returnsFalseIfNotFound() {
        // Arrange
        when(tenancyRepository.findById(uuid1)).thenReturn(Optional.empty());

        // Act
        boolean result = tenancyService.softDelete(uuid1);

        // Assert
        assertFalse(result);
        verify(tenancyRepository, never()).softDelete(any());
    }
}