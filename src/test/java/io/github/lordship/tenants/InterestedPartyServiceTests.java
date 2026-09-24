package io.github.lordship.tenants;

import io.github.lordship.audit.AuditService;
import io.github.lordship.tenancy.Tenancy;
import io.github.lordship.tenancy.TenancyService;
import io.github.lordship.tenants.internal.InterestedPartyRepository;
import io.github.lordship.tenants.internal.InterestedPartyRow;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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
public class InterestedPartyServiceTests {
    private InterestedPartyRepository interestedPartyRepository;
    private TenancyService tenancyService;
    private AuditService auditService;
    private InterestedPartyService interestedPartyService;

    private UUID tenancyId;
    private UUID personId;
    private UUID uuid1;
    private UUID uuid2;

    @BeforeEach
    void setup() {
        interestedPartyRepository = mock(InterestedPartyRepository.class);
        tenancyService = mock(TenancyService.class);
        auditService = mock(AuditService.class);

        interestedPartyService = new InterestedPartyService(
                interestedPartyRepository,
                tenancyService,
                auditService
        );

        tenancyId = UUID.randomUUID();
        personId = UUID.randomUUID();
        uuid1 = UUID.randomUUID();
        uuid2 = UUID.randomUUID();
    }

    // Assumes InterestedPartyRow's components follow the table's column order:
    // uuid, tenancy_id, person_id, notification_reason, start_date, end_date,
    // accept_payments, notes, created_at, deleted_at. This helper and afterEdit()
    // are the only places that depend on it.
    private InterestedPartyRow row(UUID id, LocalDate start, LocalDate end) {
        return new InterestedPartyRow(
                id,
                tenancyId,
                personId,
                null,
                start,
                end,
                false,
                null,
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(10).truncatedTo(ChronoUnit.DAYS),
                null
        );
    }

    // The same row as row(uuid1, now - 20 days, null), with one column replaced by
    // the patched value. Every value in editableColumns() differs from these
    // defaults, so before/after always produce a non-empty audit diff.
    private InterestedPartyRow afterEdit(String column, Object value) {
        return new InterestedPartyRow(
                uuid1,
                tenancyId,
                personId,
                column.equals("notification_reason") ? (String) value : null,
                column.equals("start_date") ? LocalDate.parse((String) value) : LocalDate.now().minusDays(20),
                column.equals("end_date") ? LocalDate.parse((String) value) : null,
                column.equals("accept_payments") ? (Boolean) value : false,
                column.equals("notes") ? (String) value : null,
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(10).truncatedTo(ChronoUnit.DAYS),
                null
        );
    }

    // Stubbed per test rather than in setup: MockitoExtension is strict, and the
    // tests that never reach the tenancy lookup would fail on an unused stub.
    private void tenancyExists() {
        Tenancy tenancy = mock(Tenancy.class);
        when(tenancy.uuid()).thenReturn(tenancyId);
        when(tenancyService.findTenancyById(tenancyId)).thenReturn(Optional.of(tenancy));
    }

    // Map.of rejects null values, and clearing a date is exactly a null.
    private static Map<String, Object> change(String key, Object value) {
        Map<String, Object> m = new HashMap<>();
        m.put(key, value);
        return m;
    }

    // One row per column the patch can write, keyed the way the controller hands
    // them to the service. Dates are relative to today so they stay valid against
    // the start/end ordering checks (the row under test starts 20 days ago and is
    // open). accept_payments defaults to FALSE, so its edit is true. Add a row
    // here whenever patchInterestedParty() gains a column.
    static Stream<Arguments> editableColumns() {
        return Stream.of(
                Arguments.of("start_date", LocalDate.now().minusDays(30).toString()),
                Arguments.of("end_date", LocalDate.now().toString()),
                Arguments.of("notification_reason", "Lender on record"),
                Arguments.of("accept_payments", true),
                Arguments.of("notes", "Called re: notice")
        );
    }

    // ---- create -------------------------------------------------------------

    @Test
    void create_savesWithTheGivenStartDate() {
        // Arrange
        tenancyExists();
        LocalDate start = LocalDate.now().minusDays(3);
        InterestedPartyRow saved = row(uuid1, start, null);
        when(interestedPartyRepository.save(tenancyId, personId, start)).thenReturn(saved);

        // Act
        InterestedParty result = interestedPartyService.create(tenancyId, personId, start);

        // Assert
        assertEquals(uuid1, result.uuid());
        verify(auditService).recordInsert(eq("tenancy_interested_party"), eq(uuid1), any());
    }

    @Test
    void create_defaultsTheStartDate_whenNoneIsGiven() {
        // Arrange
        tenancyExists();
        InterestedPartyRow saved = row(uuid1, LocalDate.now(), null);
        when(interestedPartyRepository.save(eq(tenancyId), eq(personId), any())).thenReturn(saved);
        ArgumentCaptor<LocalDate> startCaptor = ArgumentCaptor.forClass(LocalDate.class);

        // Act
        interestedPartyService.create(tenancyId, personId, null);

        // Assert
        verify(interestedPartyRepository).save(eq(tenancyId), eq(personId), startCaptor.capture());
        assertEquals(TenantService.defaultStartDate(LocalDate.now()), startCaptor.getValue());
    }

    @Test
    void create_rejectsUnknownTenancy() {
        // Arrange
        when(tenancyService.findTenancyById(tenancyId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(EntityNotFoundException.class,
                () -> interestedPartyService.create(tenancyId, personId, LocalDate.now()));
        verify(interestedPartyRepository, never()).save(any(), any(), any());
        verify(auditService, never()).recordInsert(any(), any(), any());
    }

    // Mirrors TenantService.create(): a person has one active interest in a
    // tenancy at a time, refused here for the message ahead of the DB guarantee.
    @Test
    void create_rejectsDuplicateActivePerson() {
        // Arrange
        tenancyExists();
        InterestedPartyRow existing = row(uuid2, LocalDate.now().minusDays(5), null);
        when(interestedPartyRepository.findActiveByTenancyAndPerson(tenancyId, personId))
                .thenReturn(Optional.of(existing));

        // Act & Assert
        assertThrows(IllegalStateException.class,
                () -> interestedPartyService.create(tenancyId, personId, LocalDate.now()));
        verify(interestedPartyRepository, never()).save(any(), any(), any());
        verify(auditService, never()).recordInsert(any(), any(), any());
    }

    // ---- reads --------------------------------------------------------------

    @Test
    void findById_returnsMappedInterestedParty() {
        // Arrange
        when(interestedPartyRepository.findById(uuid1))
                .thenReturn(Optional.of(row(uuid1, LocalDate.now(), null)));

        // Act
        Optional<InterestedParty> result = interestedPartyService.findById(uuid1);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(uuid1, result.get().uuid());
    }

    @Test
    void findActiveByTenancy_returnsMappedInterestedParties() {
        // Arrange
        when(interestedPartyRepository.findActiveByTenancy(tenancyId)).thenReturn(List.of(
                row(uuid1, LocalDate.now(), null),
                row(uuid2, LocalDate.now(), null)));

        // Act
        List<InterestedParty> result = interestedPartyService.findActiveByTenancy(tenancyId);

        // Assert
        assertEquals(2, result.size());
        assertEquals(uuid1, result.get(0).uuid());
    }

    @Test
    void findByTenancy_returnsPastPartiesToo() {
        // Arrange
        when(interestedPartyRepository.findByTenancy(tenancyId)).thenReturn(List.of(
                row(uuid1, LocalDate.now().minusDays(40), LocalDate.now().minusDays(10)),
                row(uuid2, LocalDate.now(), null)));

        // Act
        List<InterestedParty> result = interestedPartyService.findByTenancy(tenancyId);

        // Assert
        assertEquals(2, result.size());
    }

    @Test
    void findByPerson_returnsMappedInterestedParties() {
        // Arrange
        when(interestedPartyRepository.findByPerson(personId))
                .thenReturn(List.of(row(uuid1, LocalDate.now(), null)));

        // Act
        List<InterestedParty> result = interestedPartyService.findByPerson(personId);

        // Assert
        assertEquals(1, result.size());
        assertEquals(uuid1, result.get(0).uuid());
    }

    // ---- patch --------------------------------------------------------------

    // Each editable column must survive the service and reach the repository.
    @ParameterizedTest(name = "can edit {0}")
    @MethodSource("editableColumns")
    @SuppressWarnings("unchecked")
    void patchInterestedParty_canEditEachEditableColumn(String column, Object value) {
        // Arrange
        InterestedPartyRow before = row(uuid1, LocalDate.now().minusDays(20), null);
        // The service audits only when before and after differ, so the row the
        // repository returns must carry the edit.
        InterestedPartyRow after = afterEdit(column, value);

        when(interestedPartyRepository.findById(uuid1)).thenReturn(Optional.of(before));
        when(interestedPartyRepository.patch(eq(uuid1), any())).thenReturn(Optional.of(after));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

        // Act
        Optional<InterestedParty> result =
                interestedPartyService.patchInterestedParty(uuid1, change(column, value));

        // Assert
        assertTrue(result.isPresent());
        verify(interestedPartyRepository).patch(eq(uuid1), captor.capture());
        assertTrue(captor.getValue().containsKey(column),
                column + " was dropped before reaching the repository");
        verify(auditService).recordUpdate(eq("tenancy_interested_party"), eq(uuid1), any(), any());
    }

    @Test
    void patchInterestedParty_returnsEmptyIfNotFound() {
        // Arrange
        when(interestedPartyRepository.findById(uuid1)).thenReturn(Optional.empty());

        // Act
        Optional<InterestedParty> result =
                interestedPartyService.patchInterestedParty(uuid1, Map.of("notes", "x"));

        // Assert
        assertTrue(result.isEmpty());
        verify(interestedPartyRepository, never()).patch(any(), any());
    }

    @Test
    void patchInterestedParty_closesIt_whenEndDateIsSet() {
        // Arrange
        LocalDate end = LocalDate.now();
        InterestedPartyRow before = row(uuid1, LocalDate.now().minusDays(20), null);
        InterestedPartyRow after = row(uuid1, before.startDate(), end);

        when(interestedPartyRepository.findById(uuid1)).thenReturn(Optional.of(before));
        when(interestedPartyRepository.patch(eq(uuid1), any())).thenReturn(Optional.of(after));

        // Act
        Optional<InterestedParty> result =
                interestedPartyService.patchInterestedParty(uuid1, change("end_date", end.toString()));

        // Assert
        assertTrue(result.isPresent());
        assertEquals(end, result.get().endDate());
        verify(auditService).recordUpdate(eq("tenancy_interested_party"), eq(uuid1), any(), any());
    }

    // A figure someone typed wrong, not a state change.
    @Test
    void patchInterestedParty_correctsAnExistingEndDate() {
        // Arrange
        LocalDate corrected = LocalDate.now().minusDays(3);
        InterestedPartyRow before = row(uuid1, LocalDate.now().minusDays(20), LocalDate.now().minusDays(1));
        InterestedPartyRow after = row(uuid1, before.startDate(), corrected);

        when(interestedPartyRepository.findById(uuid1)).thenReturn(Optional.of(before));
        when(interestedPartyRepository.patch(eq(uuid1), any())).thenReturn(Optional.of(after));

        // Act
        Optional<InterestedParty> result =
                interestedPartyService.patchInterestedParty(uuid1, change("end_date", corrected.toString()));

        // Assert
        assertTrue(result.isPresent());
        assertEquals(corrected, result.get().endDate());
        verify(interestedPartyRepository, never()).findActiveByTenancyAndPerson(any(), any());
    }

    @Test
    void patchInterestedParty_reopens_whenThePersonIsNotActiveElsewhere() {
        // Arrange
        InterestedPartyRow before = row(uuid1, LocalDate.now().minusDays(20), LocalDate.now().minusDays(1));
        InterestedPartyRow after = row(uuid1, before.startDate(), null);

        when(interestedPartyRepository.findById(uuid1)).thenReturn(Optional.of(before));
        when(interestedPartyRepository.findActiveByTenancyAndPerson(tenancyId, personId))
                .thenReturn(Optional.empty());
        when(interestedPartyRepository.patch(eq(uuid1), any())).thenReturn(Optional.of(after));

        // Act
        Optional<InterestedParty> result =
                interestedPartyService.patchInterestedParty(uuid1, change("end_date", null));

        // Assert
        assertTrue(result.isPresent());
        assertNull(result.get().endDate());
    }

    // Reopening would otherwise leave two active rows for the same person on one
    // tenancy.
    @Test
    void patchInterestedParty_rejectsReopen_whenThePersonIsAlreadyActive() {
        // Arrange
        InterestedPartyRow before = row(uuid1, LocalDate.now().minusDays(20), LocalDate.now().minusDays(1));
        InterestedPartyRow other = row(uuid2, LocalDate.now().minusDays(5), null);

        when(interestedPartyRepository.findById(uuid1)).thenReturn(Optional.of(before));
        when(interestedPartyRepository.findActiveByTenancyAndPerson(tenancyId, personId))
                .thenReturn(Optional.of(other));

        // Act & Assert
        assertThrows(IllegalStateException.class,
                () -> interestedPartyService.patchInterestedParty(uuid1, change("end_date", null)));

        verify(interestedPartyRepository, never()).patch(any(), any());
        verify(auditService, never()).recordUpdate(any(), any(), any(), any());
    }

    @Test
    void patchInterestedParty_rejectsEndDateBeforeStartDate() {
        // Arrange
        InterestedPartyRow before = row(uuid1, LocalDate.now().minusDays(20), null);

        when(interestedPartyRepository.findById(uuid1)).thenReturn(Optional.of(before));

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> interestedPartyService.patchInterestedParty(uuid1,
                        change("end_date", LocalDate.now().minusDays(30).toString())));

        verify(interestedPartyRepository, never()).patch(any(), any());
    }

    @Test
    void patchInterestedParty_rejectsStartDateAfterExistingEndDate() {
        // Arrange
        InterestedPartyRow before = row(uuid1, LocalDate.now().minusDays(20), LocalDate.now().minusDays(10));

        when(interestedPartyRepository.findById(uuid1)).thenReturn(Optional.of(before));

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> interestedPartyService.patchInterestedParty(uuid1,
                        change("start_date", LocalDate.now().toString())));

        verify(interestedPartyRepository, never()).patch(any(), any());
    }

    @ParameterizedTest(name = "rejects an unparseable {0}")
    @ValueSource(strings = {"start_date", "end_date"})
    void patchInterestedParty_rejectsAnUnparseableDate(String column) {
        // Arrange
        InterestedPartyRow before = row(uuid1, LocalDate.now().minusDays(20), null);

        when(interestedPartyRepository.findById(uuid1)).thenReturn(Optional.of(before));

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> interestedPartyService.patchInterestedParty(uuid1, change(column, "not-a-date")));

        verify(interestedPartyRepository, never()).patch(any(), any());
    }

    // non-blank String falls into the "clear the date" branch, so a numeric JSON
    // value such as {"startDate": 20240101} silently wipes the date instead of
    // being refused. Only null and blank strings should clear a date.
    @ParameterizedTest(name = "a non-text {0} is refused, not treated as a clear")
    @ValueSource(strings = {"start_date", "end_date"})
    void patchInterestedParty_rejectsANonTextDate(String column) {
        // Arrange: both dates set, so a wrongful clear would be a real change
        InterestedPartyRow before = row(uuid1, LocalDate.now().minusDays(20), LocalDate.now().minusDays(1));

        when(interestedPartyRepository.findById(uuid1)).thenReturn(Optional.of(before));

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> interestedPartyService.patchInterestedParty(uuid1, change(column, 20240101)));

        verify(interestedPartyRepository, never()).patch(any(), any());
    }

    // The supplied value already matches, so nothing is written or audited.
    @Test
    void patchInterestedParty_doesNothing_whenTheDateAlreadyMatches() {
        // Arrange
        InterestedPartyRow before = row(uuid1, LocalDate.now().minusDays(20), null);

        when(interestedPartyRepository.findById(uuid1)).thenReturn(Optional.of(before));

        // Act
        Optional<InterestedParty> result = interestedPartyService.patchInterestedParty(
                uuid1, change("start_date", before.startDate().toString()));

        // Assert
        assertTrue(result.isPresent());
        assertEquals(uuid1, result.get().uuid());
        verify(interestedPartyRepository, never()).patch(any(), any());
        verify(auditService, never()).recordUpdate(any(), any(), any(), any());
    }

    // ---- softDelete ---------------------------------------------------------

    @Test
    void softDelete_deletesAndAudits() {
        // Arrange
        InterestedPartyRow before = row(uuid1, LocalDate.now().minusDays(20), null);

        when(interestedPartyRepository.findById(uuid1)).thenReturn(Optional.of(before));
        when(interestedPartyRepository.softDelete(uuid1)).thenReturn(true);

        // Act
        boolean result = interestedPartyService.softDelete(uuid1);

        // Assert
        assertTrue(result);
        verify(interestedPartyRepository).softDelete(uuid1);
        verify(auditService).recordDelete(eq("tenancy_interested_party"), eq(uuid1), any());
    }

    @Test
    void softDelete_returnsFalseIfNotFound() {
        // Arrange
        when(interestedPartyRepository.findById(uuid1)).thenReturn(Optional.empty());

        // Act
        boolean result = interestedPartyService.softDelete(uuid1);

        // Assert
        assertFalse(result);
        verify(interestedPartyRepository, never()).softDelete(any());
    }

    // The row was found but the delete changed nothing (already deleted in the
    // meantime), so there is nothing to audit.
    @Test
    void softDelete_doesNotAudit_whenNothingWasDeleted() {
        // Arrange
        InterestedPartyRow before = row(uuid1, LocalDate.now().minusDays(20), null);

        when(interestedPartyRepository.findById(uuid1)).thenReturn(Optional.of(before));
        when(interestedPartyRepository.softDelete(uuid1)).thenReturn(false);

        // Act
        boolean result = interestedPartyService.softDelete(uuid1);

        // Assert
        assertFalse(result);
        verify(auditService, never()).recordDelete(any(), any(), any());
    }
}
