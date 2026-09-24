package io.github.lordship.homes;

import io.github.lordship.audit.AuditService;
import io.github.lordship.homes.internal.SecuredPartyRepository;
import io.github.lordship.homes.internal.SecuredPartyRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class SecuredPartyServiceTest {

    @Mock
    SecuredPartyRepository securedPartyRepository;

    @Mock
    AuditService auditService;

    @InjectMocks
    SecuredPartyService securedPartyService;

    private final UUID mobileHomeId = UUID.randomUUID();
    private final UUID personId = UUID.randomUUID();

    private SecuredPartyRow row(UUID uuid, LocalDate startDate, LocalDate endDate) {
        return new SecuredPartyRow(
                uuid, mobileHomeId, personId, startDate, endDate, false,
                OffsetDateTime.now(ZoneOffset.UTC), null
        );
    }

    // ── createSecuredParty ──────────────────────────────────────────────────────

    @Test
    void createSecuredParty_recordsInsert_andReturnsTheRow() {
        when(securedPartyRepository.findActiveByHomeAndPerson(mobileHomeId, personId))
                .thenReturn(Optional.empty());
        SecuredPartyRow saved = row(UUID.randomUUID(), LocalDate.of(2026, 1, 1), null);
        when(securedPartyRepository.save(mobileHomeId, personId, LocalDate.of(2026, 1, 1)))
                .thenReturn(Optional.of(saved));

        SecuredParty created = securedPartyService.createSecuredParty(
                mobileHomeId, personId, LocalDate.of(2026, 1, 1));

        assertEquals(saved.uuid(), created.uuid());
        verify(auditService).recordInsert(eq("mobile_home_secured_party"), eq(saved.uuid()), any());
    }

    // Unlike Tenant/InterestedParty, this defaults to today rather than the next
    // billing period: a secured party is a legal claim, not a rent-paying stay.
    @Test
    void createSecuredParty_defaultsTheStartDate_toToday_whenOmitted() {
        when(securedPartyRepository.findActiveByHomeAndPerson(mobileHomeId, personId))
                .thenReturn(Optional.empty());
        when(securedPartyRepository.save(eq(mobileHomeId), eq(personId), any()))
                .thenReturn(Optional.of(row(UUID.randomUUID(), LocalDate.now(), null)));

        securedPartyService.createSecuredParty(mobileHomeId, personId, null);

        verify(securedPartyRepository).save(mobileHomeId, personId, LocalDate.now());
    }

    @Test
    void createSecuredParty_throwsNamingTheHome_whenTheHomeDoesNotExist() {
        when(securedPartyRepository.findActiveByHomeAndPerson(mobileHomeId, personId))
                .thenReturn(Optional.empty());
        when(securedPartyRepository.save(eq(mobileHomeId), eq(personId), any()))
                .thenReturn(Optional.empty());

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> securedPartyService.createSecuredParty(mobileHomeId, personId, null));

        // the id has to be in the message: this is what an office worker forwards
        assertTrue(thrown.getMessage().contains(mobileHomeId.toString()));
        verifyNoInteractions(auditService);
    }

    @Test
    void createSecuredParty_refusesASecondActiveClaim_forTheSamePersonAndHome() {
        SecuredPartyRow existing = row(UUID.randomUUID(), LocalDate.of(2026, 1, 1), null);
        when(securedPartyRepository.findActiveByHomeAndPerson(mobileHomeId, personId))
                .thenReturn(Optional.of(existing));

        assertThrows(IllegalStateException.class,
                () -> securedPartyService.createSecuredParty(mobileHomeId, personId, LocalDate.now()));

        verify(securedPartyRepository, never()).save(any(), any(), any());
        verifyNoInteractions(auditService);
    }

    // ── patchSecuredParty: date normalization ─────────────────────────────────────

    @Test
    void patchSecuredParty_setsAndClearsDates() {
        UUID uuid = UUID.randomUUID();
        SecuredPartyRow before = row(uuid, LocalDate.of(2026, 1, 1), null);
        SecuredPartyRow after = row(uuid, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30));

        when(securedPartyRepository.findById(uuid)).thenReturn(Optional.of(before));
        when(securedPartyRepository.patch(eq(uuid), any())).thenReturn(Optional.of(after));

        Optional<SecuredParty> patched = securedPartyService.patchSecuredParty(
                uuid, new HashMap<>(Map.of("end_date", "2026-06-30")));

        assertEquals(LocalDate.of(2026, 6, 30), patched.orElseThrow().endDate());
    }

    @Test
    void patchSecuredParty_rejectsANonTextDate() {
        UUID uuid = UUID.randomUUID();
        SecuredPartyRow before = row(uuid, LocalDate.of(2026, 1, 1), null);
        when(securedPartyRepository.findById(uuid)).thenReturn(Optional.of(before));

        Map<String, Object> changes = new HashMap<>();
        changes.put("end_date", 20260630);

        assertThrows(IllegalArgumentException.class,
                () -> securedPartyService.patchSecuredParty(uuid, changes));

        verify(securedPartyRepository, never()).patch(any(), any());
    }

    @Test
    void patchSecuredParty_dropsANoOpChange_beforeReachingTheRepository() {
        UUID uuid = UUID.randomUUID();
        SecuredPartyRow before = row(uuid, LocalDate.of(2026, 1, 1), null);
        when(securedPartyRepository.findById(uuid)).thenReturn(Optional.of(before));

        Optional<SecuredParty> patched = securedPartyService.patchSecuredParty(
                uuid, new HashMap<>(Map.of("start_date", "2026-01-01")));

        assertEquals(before.startDate(), patched.orElseThrow().startDate());
        verify(securedPartyRepository, never()).patch(any(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    void patchSecuredParty_rejectsAnEndDateBeforeTheStartDate() {
        UUID uuid = UUID.randomUUID();
        SecuredPartyRow before = row(uuid, LocalDate.of(2026, 6, 1), null);
        when(securedPartyRepository.findById(uuid)).thenReturn(Optional.of(before));

        Map<String, Object> changes = new HashMap<>();
        changes.put("end_date", "2026-01-01");

        assertThrows(IllegalArgumentException.class,
                () -> securedPartyService.patchSecuredParty(uuid, changes));

        verify(securedPartyRepository, never()).patch(any(), any());
    }

    // ── patchSecuredParty: reopening guard ────────────────────────────────────────

    @Test
    void patchSecuredParty_refusesToReopen_whenThatPersonIsActiveOnTheHomeElsewhere() {
        UUID uuid = UUID.randomUUID();
        SecuredPartyRow before = row(uuid, LocalDate.of(2024, 1, 1), LocalDate.of(2025, 6, 30));
        SecuredPartyRow other = row(UUID.randomUUID(), LocalDate.of(2026, 1, 1), null);

        when(securedPartyRepository.findById(uuid)).thenReturn(Optional.of(before));
        when(securedPartyRepository.findActiveByHomeAndPerson(mobileHomeId, personId))
                .thenReturn(Optional.of(other));

        Map<String, Object> changes = new HashMap<>();
        changes.put("end_date", null);

        assertThrows(IllegalStateException.class,
                () -> securedPartyService.patchSecuredParty(uuid, changes));

        verify(securedPartyRepository, never()).patch(any(), any());
    }

    @Test
    void patchSecuredParty_allowsReopening_whenNoOtherActiveRowExists() {
        UUID uuid = UUID.randomUUID();
        SecuredPartyRow before = row(uuid, LocalDate.of(2024, 1, 1), LocalDate.of(2025, 6, 30));
        SecuredPartyRow after = row(uuid, LocalDate.of(2024, 1, 1), null);

        when(securedPartyRepository.findById(uuid)).thenReturn(Optional.of(before));
        when(securedPartyRepository.findActiveByHomeAndPerson(mobileHomeId, personId))
                .thenReturn(Optional.empty());
        when(securedPartyRepository.patch(eq(uuid), any())).thenReturn(Optional.of(after));

        Map<String, Object> changes = new HashMap<>();
        changes.put("end_date", null);

        Optional<SecuredParty> patched = securedPartyService.patchSecuredParty(uuid, changes);

        assertTrue(patched.isPresent());
        assertNull(patched.get().endDate());
    }

    // ── patchSecuredParty: audit + not-found ──────────────────────────────────────

    @Test
    void patchSecuredParty_recordsUpdate_whenAFieldActuallyChanges() {
        UUID uuid = UUID.randomUUID();
        SecuredPartyRow before = row(uuid, LocalDate.of(2026, 1, 1), null);
        SecuredPartyRow after = row(uuid, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30));

        when(securedPartyRepository.findById(uuid)).thenReturn(Optional.of(before));
        when(securedPartyRepository.patch(eq(uuid), any())).thenReturn(Optional.of(after));

        securedPartyService.patchSecuredParty(uuid, new HashMap<>(Map.of("end_date", "2026-06-30")));

        verify(auditService).recordUpdate(eq("mobile_home_secured_party"), eq(uuid), any(), any());
    }

    @Test
    void patchSecuredParty_returnsEmpty_whenTheSecuredPartyIsNotThere() {
        UUID uuid = UUID.randomUUID();
        when(securedPartyRepository.findById(uuid)).thenReturn(Optional.empty());

        assertTrue(securedPartyService.patchSecuredParty(
                uuid, new HashMap<>(Map.of("end_date", "2026-06-30"))).isEmpty());

        verify(securedPartyRepository, never()).patch(any(), any());
        verifyNoInteractions(auditService);
    }

    // ── deleteSecuredParty ─────────────────────────────────────────────────────────

    @Test
    void deleteSecuredParty_recordsDelete_whenARowActuallyChanged() {
        SecuredPartyRow existing = row(UUID.randomUUID(), LocalDate.of(2026, 1, 1), null);
        when(securedPartyRepository.findById(existing.uuid())).thenReturn(Optional.of(existing));
        when(securedPartyRepository.softDelete(existing.uuid())).thenReturn(true);

        assertTrue(securedPartyService.deleteSecuredParty(existing.uuid()));

        verify(auditService).recordDelete(eq("mobile_home_secured_party"), eq(existing.uuid()), any());
    }

    @Test
    void deleteSecuredParty_recordsNothing_whenNoRowChanged() {
        SecuredPartyRow existing = row(UUID.randomUUID(), LocalDate.of(2026, 1, 1), null);
        when(securedPartyRepository.findById(existing.uuid())).thenReturn(Optional.of(existing));
        when(securedPartyRepository.softDelete(existing.uuid())).thenReturn(false);

        assertFalse(securedPartyService.deleteSecuredParty(existing.uuid()));

        verifyNoInteractions(auditService);
    }

    @Test
    void deleteSecuredParty_returnsFalse_whenTheSecuredPartyIsNotThere() {
        UUID unknown = UUID.randomUUID();
        when(securedPartyRepository.findById(unknown)).thenReturn(Optional.empty());

        assertFalse(securedPartyService.deleteSecuredParty(unknown));

        verify(securedPartyRepository, never()).softDelete(any());
        verifyNoInteractions(auditService);
    }
}
