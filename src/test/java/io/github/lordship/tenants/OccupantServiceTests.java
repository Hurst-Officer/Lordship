package io.github.lordship.tenants;

import io.github.lordship.audit.AuditService;
import io.github.lordship.tenancy.Tenancy;
import io.github.lordship.tenancy.TenancyService;
import io.github.lordship.tenants.internal.OccupantRepository;
import io.github.lordship.tenants.internal.OccupantRow;
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
public class OccupantServiceTests {
    private OccupantRepository occupantRepository;
    private TenancyService tenancyService;
    private AuditService auditService;
    private OccupantService occupantService;

    private UUID tenancyId;
    private UUID personId;
    private UUID uuid1;
    private UUID uuid2;

    @BeforeEach
    void setup() {
        occupantRepository = mock(OccupantRepository.class);
        tenancyService = mock(TenancyService.class);
        auditService = mock(AuditService.class);

        occupantService = new OccupantService(
                occupantRepository,
                tenancyService,
                auditService
        );

        tenancyId = UUID.randomUUID();
        personId = UUID.randomUUID();
        uuid1 = UUID.randomUUID();
        uuid2 = UUID.randomUUID();
    }

    // Assumes OccupantRow's components follow the table's column order:
    // uuid, tenancy_id, person_id, start_date, end_date, created_at, deleted_at.
    // This helper and afterEdit() are the only places that depend on it.
    private OccupantRow row(UUID id, LocalDate start, LocalDate end) {
        return new OccupantRow(
                id,
                tenancyId,
                personId,
                start,
                end,
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(10).truncatedTo(ChronoUnit.DAYS),
                null
        );
    }

    // The same row as row(uuid1, now - 20 days, null), with one column replaced by
    // the patched value. Every value in editableColumns() differs from these
    // defaults, so before/after always produce a non-empty audit diff.
    private OccupantRow afterEdit(String column, Object value) {
        return new OccupantRow(
                uuid1,
                tenancyId,
                personId,
                column.equals("start_date") ? LocalDate.parse((String) value) : LocalDate.now().minusDays(20),
                column.equals("end_date") ? LocalDate.parse((String) value) : null,
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

    private void personIsNotYetAnOccupant() {
        when(occupantRepository.findActiveByTenancyAndPerson(tenancyId, personId))
                .thenReturn(Optional.empty());
    }

    // Map.of rejects null values, and clearing a date is exactly a null.
    private static Map<String, Object> change(String key, Object value) {
        Map<String, Object> m = new HashMap<>();
        m.put(key, value);
        return m;
    }

    // The two columns the PATCH endpoint can write, keyed the way the controller
    // hands them to the service. Dates are relative to today so they stay valid
    // against the start/end ordering checks (the row under test starts 20 days ago
    // and is open). Add a row here whenever patchOccupant() gains a column.
    static Stream<Arguments> editableColumns() {
        return Stream.of(
                Arguments.of("start_date", LocalDate.now().minusDays(30).toString()),
                Arguments.of("end_date", LocalDate.now().toString())
        );
    }

    // ---- create -------------------------------------------------------------

    @Test
    void create_savesWithTheGivenStartDate() {
        // Arrange
        tenancyExists();
        personIsNotYetAnOccupant();
        LocalDate start = LocalDate.now().minusDays(3);
        OccupantRow saved = row(uuid1, start, null);
        when(occupantRepository.save(tenancyId, personId, start)).thenReturn(saved);

        // Act
        Occupant result = occupantService.create(tenancyId, personId, start);

        // Assert
        assertEquals(uuid1, result.uuid());
        verify(auditService).recordInsert(eq("occupant"), eq(uuid1), any());
    }

    @Test
    void create_defaultsTheStartDate_whenNoneIsGiven() {
        // Arrange
        tenancyExists();
        personIsNotYetAnOccupant();
        OccupantRow saved = row(uuid1, LocalDate.now(), null);
        when(occupantRepository.save(eq(tenancyId), eq(personId), any())).thenReturn(saved);
        ArgumentCaptor<LocalDate> startCaptor = ArgumentCaptor.forClass(LocalDate.class);

        // Act
        occupantService.create(tenancyId, personId, null);

        // Assert
        verify(occupantRepository).save(eq(tenancyId), eq(personId), startCaptor.capture());
        assertEquals(TenantService.defaultStartDate(LocalDate.now()), startCaptor.getValue());
    }

    @Test
    void create_rejectsUnknownTenancy() {
        // Arrange
        when(tenancyService.findTenancyById(tenancyId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(EntityNotFoundException.class,
                () -> occupantService.create(tenancyId, personId, LocalDate.now()));
        verify(occupantRepository, never()).findActiveByTenancyAndPerson(any(), any());
        verify(occupantRepository, never()).save(any(), any(), any());
        verify(auditService, never()).recordInsert(any(), any(), any());
    }

    // One active stay per person per tenancy; someone who left and returned gets
    // a second row once the first has an end date.
    @Test
    void create_rejectsAPersonWhoIsAlreadyActiveOnTheTenancy() {
        // Arrange
        tenancyExists();
        OccupantRow existing = row(uuid1, LocalDate.now().minusDays(30), null);
        when(occupantRepository.findActiveByTenancyAndPerson(tenancyId, personId))
                .thenReturn(Optional.of(existing));

        // Act
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> occupantService.create(tenancyId, personId, LocalDate.now()));

        // Assert: the message names the row that is in the way
        assertTrue(e.getMessage().contains(uuid1.toString()));
        verify(occupantRepository, never()).save(any(), any(), any());
        verify(auditService, never()).recordInsert(any(), any(), any());
    }

    // ---- reads --------------------------------------------------------------

    @Test
    void findById_returnsMappedOccupant() {
        // Arrange
        when(occupantRepository.findById(uuid1))
                .thenReturn(Optional.of(row(uuid1, LocalDate.now(), null)));

        // Act
        Optional<Occupant> result = occupantService.findById(uuid1);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(uuid1, result.get().uuid());
    }

    @Test
    void findActiveByTenancy_returnsMappedOccupants() {
        // Arrange
        when(occupantRepository.findActiveByTenancy(tenancyId)).thenReturn(List.of(
                row(uuid1, LocalDate.now(), null),
                row(uuid2, LocalDate.now(), null)));

        // Act
        List<Occupant> result = occupantService.findActiveByTenancy(tenancyId);

        // Assert
        assertEquals(2, result.size());
        assertEquals(uuid1, result.get(0).uuid());
    }

    @Test
    void findByTenancy_returnsPastStaysToo() {
        // Arrange
        when(occupantRepository.findByTenancy(tenancyId)).thenReturn(List.of(
                row(uuid1, LocalDate.now().minusDays(40), LocalDate.now().minusDays(10)),
                row(uuid2, LocalDate.now(), null)));

        // Act
        List<Occupant> result = occupantService.findByTenancy(tenancyId);

        // Assert
        assertEquals(2, result.size());
    }

    @Test
    void findByPerson_returnsMappedOccupants() {
        // Arrange
        when(occupantRepository.findByPerson(personId))
                .thenReturn(List.of(row(uuid1, LocalDate.now(), null)));

        // Act
        List<Occupant> result = occupantService.findByPerson(personId);

        // Assert
        assertEquals(1, result.size());
        assertEquals(uuid1, result.get(0).uuid());
    }

    // ---- patch --------------------------------------------------------------

    // Each editable column must survive the service and reach the repository.
    @ParameterizedTest(name = "can edit {0}")
    @MethodSource("editableColumns")
    @SuppressWarnings("unchecked")
    void patchOccupant_canEditEachEditableColumn(String column, Object value) {
        // Arrange
        OccupantRow before = row(uuid1, LocalDate.now().minusDays(20), null);
        // The service audits only when before and after differ, so the row the
        // repository returns must carry the edit.
        OccupantRow after = afterEdit(column, value);

        when(occupantRepository.findById(uuid1)).thenReturn(Optional.of(before));
        when(occupantRepository.patch(eq(uuid1), any())).thenReturn(Optional.of(after));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

        // Act
        Optional<Occupant> result = occupantService.patchOccupant(uuid1, change(column, value));

        // Assert
        assertTrue(result.isPresent());
        verify(occupantRepository).patch(eq(uuid1), captor.capture());
        assertTrue(captor.getValue().containsKey(column),
                column + " was dropped before reaching the repository");
        verify(auditService).recordUpdate(eq("occupant"), eq(uuid1), any(), any());
    }

    @Test
    void patchOccupant_returnsEmptyIfNotFound() {
        // Arrange
        when(occupantRepository.findById(uuid1)).thenReturn(Optional.empty());

        // Act
        Optional<Occupant> result = occupantService.patchOccupant(uuid1, change("end_date", "2025-01-01"));

        // Assert
        assertTrue(result.isEmpty());
        verify(occupantRepository, never()).patch(any(), any());
    }

    // Moving out is setting end_date.
    @Test
    void patchOccupant_recordsAMoveOut_whenEndDateIsSet() {
        // Arrange
        LocalDate end = LocalDate.now();
        OccupantRow before = row(uuid1, LocalDate.now().minusDays(20), null);
        OccupantRow after = row(uuid1, before.startDate(), end);

        when(occupantRepository.findById(uuid1)).thenReturn(Optional.of(before));
        when(occupantRepository.patch(eq(uuid1), any())).thenReturn(Optional.of(after));

        // Act
        Optional<Occupant> result = occupantService.patchOccupant(uuid1, change("end_date", end.toString()));

        // Assert
        assertTrue(result.isPresent());
        assertEquals(end, result.get().endDate());
        verify(auditService).recordUpdate(eq("occupant"), eq(uuid1), any(), any());
    }

    // A figure someone typed wrong, not a state change.
    @Test
    void patchOccupant_correctsAnExistingEndDate() {
        // Arrange
        LocalDate corrected = LocalDate.now().minusDays(3);
        OccupantRow before = row(uuid1, LocalDate.now().minusDays(20), LocalDate.now().minusDays(1));
        OccupantRow after = row(uuid1, before.startDate(), corrected);

        when(occupantRepository.findById(uuid1)).thenReturn(Optional.of(before));
        when(occupantRepository.patch(eq(uuid1), any())).thenReturn(Optional.of(after));

        // Act
        Optional<Occupant> result = occupantService.patchOccupant(uuid1, change("end_date", corrected.toString()));

        // Assert
        assertTrue(result.isPresent());
        assertEquals(corrected, result.get().endDate());
        verify(occupantRepository, never()).findActiveByTenancyAndPerson(any(), any());
    }

    // Undoing a move-out entered by mistake.
    @Test
    void patchOccupant_undoesAMoveOut_whenThePersonIsNotActiveElsewhere() {
        // Arrange
        OccupantRow before = row(uuid1, LocalDate.now().minusDays(20), LocalDate.now().minusDays(1));
        OccupantRow after = row(uuid1, before.startDate(), null);

        when(occupantRepository.findById(uuid1)).thenReturn(Optional.of(before));
        when(occupantRepository.findActiveByTenancyAndPerson(tenancyId, personId))
                .thenReturn(Optional.empty());
        when(occupantRepository.patch(eq(uuid1), any())).thenReturn(Optional.of(after));

        // Act
        Optional<Occupant> result = occupantService.patchOccupant(uuid1, change("end_date", null));

        // Assert
        assertTrue(result.isPresent());
        assertNull(result.get().endDate());
    }

    // Clearing an end_date is a second way past uq_occupant_active_person that
    // create() never sees.
    @Test
    void patchOccupant_rejectsUndoingAMoveOut_whenThePersonWasAddedAgain() {
        // Arrange
        OccupantRow before = row(uuid1, LocalDate.now().minusDays(20), LocalDate.now().minusDays(1));
        OccupantRow readded = row(uuid2, LocalDate.now().minusDays(1), null);

        when(occupantRepository.findById(uuid1)).thenReturn(Optional.of(before));
        when(occupantRepository.findActiveByTenancyAndPerson(tenancyId, personId))
                .thenReturn(Optional.of(readded));

        // Act & Assert
        assertThrows(IllegalStateException.class,
                () -> occupantService.patchOccupant(uuid1, change("end_date", null)));

        verify(occupantRepository, never()).patch(any(), any());
        verify(auditService, never()).recordUpdate(any(), any(), any(), any());
    }

    @Test
    void patchOccupant_rejectsEndDateBeforeStartDate() {
        // Arrange
        OccupantRow before = row(uuid1, LocalDate.now().minusDays(20), null);

        when(occupantRepository.findById(uuid1)).thenReturn(Optional.of(before));

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> occupantService.patchOccupant(uuid1,
                        change("end_date", LocalDate.now().minusDays(30).toString())));

        verify(occupantRepository, never()).patch(any(), any());
    }

    @Test
    void patchOccupant_rejectsStartDateAfterExistingEndDate() {
        // Arrange
        OccupantRow before = row(uuid1, LocalDate.now().minusDays(20), LocalDate.now().minusDays(10));

        when(occupantRepository.findById(uuid1)).thenReturn(Optional.of(before));

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> occupantService.patchOccupant(uuid1,
                        change("start_date", LocalDate.now().toString())));

        verify(occupantRepository, never()).patch(any(), any());
    }

    @ParameterizedTest(name = "rejects an unparseable {0}")
    @ValueSource(strings = {"start_date", "end_date"})
    void patchOccupant_rejectsAnUnparseableDate(String column) {
        // Arrange
        OccupantRow before = row(uuid1, LocalDate.now().minusDays(20), null);

        when(occupantRepository.findById(uuid1)).thenReturn(Optional.of(before));

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> occupantService.patchOccupant(uuid1, change(column, "not-a-date")));

        verify(occupantRepository, never()).patch(any(), any());
    }


    @ParameterizedTest(name = "a non-text {0} is refused, not treated as a clear")
    @ValueSource(strings = {"start_date", "end_date"})
    void patchOccupant_rejectsANonTextDate(String column) {
        // Arrange: both dates set, so a wrongful clear would be a real change
        OccupantRow before = row(uuid1, LocalDate.now().minusDays(20), LocalDate.now().minusDays(1));

        when(occupantRepository.findById(uuid1)).thenReturn(Optional.of(before));

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> occupantService.patchOccupant(uuid1, change(column, 20240101)));

        verify(occupantRepository, never()).patch(any(), any());
    }

    // The supplied value already matches, so nothing is written or audited.
    @Test
    void patchOccupant_doesNothing_whenTheDateAlreadyMatches() {
        // Arrange
        OccupantRow before = row(uuid1, LocalDate.now().minusDays(20), null);

        when(occupantRepository.findById(uuid1)).thenReturn(Optional.of(before));

        // Act
        Optional<Occupant> result = occupantService.patchOccupant(
                uuid1, change("start_date", before.startDate().toString()));

        // Assert
        assertTrue(result.isPresent());
        assertEquals(uuid1, result.get().uuid());
        verify(occupantRepository, never()).patch(any(), any());
        verify(auditService, never()).recordUpdate(any(), any(), any(), any());
    }

    // ---- softDelete ---------------------------------------------------------

    @Test
    void softDelete_deletesAndAudits() {
        // Arrange
        OccupantRow before = row(uuid1, LocalDate.now().minusDays(20), null);

        when(occupantRepository.findById(uuid1)).thenReturn(Optional.of(before));
        when(occupantRepository.softDelete(uuid1)).thenReturn(true);

        // Act
        boolean result = occupantService.softDelete(uuid1);

        // Assert
        assertTrue(result);
        verify(occupantRepository).softDelete(uuid1);
        verify(auditService).recordDelete(eq("occupant"), eq(uuid1), any());
    }

    @Test
    void softDelete_returnsFalseIfNotFound() {
        // Arrange
        when(occupantRepository.findById(uuid1)).thenReturn(Optional.empty());

        // Act
        boolean result = occupantService.softDelete(uuid1);

        // Assert
        assertFalse(result);
        verify(occupantRepository, never()).softDelete(any());
    }

    // The row was found but the delete changed nothing (already deleted in the
    // meantime), so there is nothing to audit.
    @Test
    void softDelete_doesNotAudit_whenNothingWasDeleted() {
        // Arrange
        OccupantRow before = row(uuid1, LocalDate.now().minusDays(20), null);

        when(occupantRepository.findById(uuid1)).thenReturn(Optional.of(before));
        when(occupantRepository.softDelete(uuid1)).thenReturn(false);

        // Act
        boolean result = occupantService.softDelete(uuid1);

        // Assert
        assertFalse(result);
        verify(auditService, never()).recordDelete(any(), any(), any());
    }
}