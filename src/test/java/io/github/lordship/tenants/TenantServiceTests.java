package io.github.lordship.tenants;

import io.github.lordship.audit.AuditService;
import io.github.lordship.tenancy.Tenancy;
import io.github.lordship.tenancy.TenancyService;
import io.github.lordship.tenants.internal.TenantRepository;
import io.github.lordship.tenants.internal.TenantRow;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TenantServiceTests {

    @InjectMocks
    TenantService tenantService;

    @Mock
    TenantRepository tenantRepository;

    @Mock
    TenancyService tenancyService;

    @Mock
    AuditService auditService;

    private TenantRow row(UUID tenancyId, UUID personId, LocalDate start, LocalDate end) {
        return new TenantRow(
                UUID.randomUUID(),
                tenancyId,
                personId,
                start,
                end,
                OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS),
                null
        );
    }

    private Tenancy tenancy(UUID uuid) {
        return new Tenancy(
                uuid,
                UUID.randomUUID(),
                LocalDate.now().minusMonths(6),
                null,
                false,
                false,
                true,
                false,
                "",
                OffsetDateTime.now(ZoneOffset.UTC),
                null
        );
    }

    // ---- defaultStartDate ---------------------------------------------------

    @Test
    void defaultStartDate_beforeTheTenth_isTheFirstOfThisMonth() {
        assertEquals(LocalDate.of(2026, 9, 1), TenantService.defaultStartDate(LocalDate.of(2026, 9, 1)));
        assertEquals(LocalDate.of(2026, 9, 1), TenantService.defaultStartDate(LocalDate.of(2026, 9, 9)));
    }

    @Test
    void defaultStartDate_onOrAfterTheTenth_isTheFirstOfNextMonth() {
        assertEquals(LocalDate.of(2026, 10, 1), TenantService.defaultStartDate(LocalDate.of(2026, 9, 10)));
        assertEquals(LocalDate.of(2026, 10, 1), TenantService.defaultStartDate(LocalDate.of(2026, 9, 30)));
    }

    @Test
    void defaultStartDate_rollsTheYear_inDecember() {
        assertEquals(LocalDate.of(2027, 1, 1), TenantService.defaultStartDate(LocalDate.of(2026, 12, 20)));
    }

    // ---- create -------------------------------------------------------------

    // The regression this rewrite exists for: a spouse joining a household must
    // not close anyone else's row. The repository is asked for the duplicate
    // check and the insert, and for nothing else.
    @Test
    void create_leavesTheOtherTenantsAlone() {
        UUID tenancyId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        TenantRow saved = row(tenancyId, personId, LocalDate.now(), null);

        when(tenancyService.findTenancyById(tenancyId)).thenReturn(Optional.of(tenancy(tenancyId)));
        when(tenantRepository.findActiveByTenancyAndPerson(tenancyId, personId)).thenReturn(Optional.empty());
        when(tenantRepository.save(eq(tenancyId), eq(personId), any())).thenReturn(saved);

        tenantService.create(tenancyId, personId, null);

        verify(tenantRepository).findActiveByTenancyAndPerson(tenancyId, personId);
        verify(tenantRepository).findByTenancy(tenancyId); // read only: is this the first tenant?
        verify(tenantRepository).save(eq(tenancyId), eq(personId), any());
        verifyNoMoreInteractions(tenantRepository);
    }

    @Test
    void create_persistsTenantAndReturnsDomainObject() {
        UUID tenancyId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        LocalDate start = LocalDate.of(2026, 10, 1);
        TenantRow saved = row(tenancyId, personId, start, null);

        when(tenancyService.findTenancyById(tenancyId)).thenReturn(Optional.of(tenancy(tenancyId)));
        when(tenantRepository.findActiveByTenancyAndPerson(tenancyId, personId)).thenReturn(Optional.empty());
        when(tenantRepository.save(tenancyId, personId, start)).thenReturn(saved);

        Tenant result = tenantService.create(tenancyId, personId, start);

        assertEquals(saved.uuid(), result.uuid());
        assertEquals(tenancyId, result.tenancyId());
        assertEquals(personId, result.personId());
        assertEquals(start, result.startDate());
        assertNull(result.endDate());
        verify(auditService).recordInsert(eq("tenant"), eq(saved.uuid()), any());
    }

    @Test
    void create_startsTheFirstTenantOnTheTenancysStartDate() {
        // Arrange -- nobody has ever been on this tenancy
        UUID tenancyId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        Tenancy tenancy = tenancy(tenancyId);
        TenantRow saved = row(tenancyId, personId, tenancy.startDate(), null);

        when(tenancyService.findTenancyById(tenancyId)).thenReturn(Optional.of(tenancy));
        when(tenantRepository.findActiveByTenancyAndPerson(tenancyId, personId)).thenReturn(Optional.empty());
        when(tenantRepository.findByTenancy(tenancyId)).thenReturn(List.of());
        when(tenantRepository.save(tenancyId, personId, tenancy.startDate())).thenReturn(saved);

        // Act
        tenantService.create(tenancyId, personId, null);

        // Assert
        verify(tenantRepository).save(tenancyId, personId, tenancy.startDate());
    }

    @Test
    void create_givesALaterTenantTheBillingPeriodGuess() {
        // Arrange -- a spouse joining after the household moved in
        UUID tenancyId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        LocalDate expected = TenantService.defaultStartDate(LocalDate.now());
        TenantRow first = row(tenancyId, UUID.randomUUID(), LocalDate.now().minusMonths(6), null);
        TenantRow saved = row(tenancyId, personId, expected, null);

        when(tenancyService.findTenancyById(tenancyId)).thenReturn(Optional.of(tenancy(tenancyId)));
        when(tenantRepository.findActiveByTenancyAndPerson(tenancyId, personId)).thenReturn(Optional.empty());
        when(tenantRepository.findByTenancy(tenancyId)).thenReturn(List.of(first));
        when(tenantRepository.save(tenancyId, personId, expected)).thenReturn(saved);

        // Act
        tenantService.create(tenancyId, personId, null);

        // Assert
        verify(tenantRepository).save(tenancyId, personId, expected);
    }

    @Test
    void create_refusesTheSamePersonTwiceOnOneTenancy() {
        UUID tenancyId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        TenantRow existing = row(tenancyId, personId, LocalDate.now().minusMonths(3), null);

        when(tenancyService.findTenancyById(tenancyId)).thenReturn(Optional.of(tenancy(tenancyId)));
        when(tenantRepository.findActiveByTenancyAndPerson(tenancyId, personId)).thenReturn(Optional.of(existing));

        assertThrows(IllegalStateException.class,
                () -> tenantService.create(tenancyId, personId, null));

        verify(tenantRepository, never()).save(any(), any(), any());
        verify(auditService, never()).recordInsert(any(), any(), any());
    }

    // A person who moved out and is moving back is not a duplicate: the earlier
    // row carries an end_date, so findActiveByTenancyAndPerson does not see it.
    @Test
    void create_allowsAPersonToReturnAfterMovingOut() {
        UUID tenancyId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        TenantRow saved = row(tenancyId, personId, LocalDate.now(), null);

        when(tenancyService.findTenancyById(tenancyId)).thenReturn(Optional.of(tenancy(tenancyId)));
        when(tenantRepository.findActiveByTenancyAndPerson(tenancyId, personId)).thenReturn(Optional.empty());
        when(tenantRepository.save(eq(tenancyId), eq(personId), any())).thenReturn(saved);

        Tenant result = tenantService.create(tenancyId, personId, null);

        assertEquals(saved.uuid(), result.uuid());
    }

    @Test
    void create_throwsWhenTheTenancyIsUnknownOrDeleted() {
        UUID tenancyId = UUID.randomUUID();

        when(tenancyService.findTenancyById(tenancyId)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> tenantService.create(tenancyId, UUID.randomUUID(), null));

        verifyNoInteractions(tenantRepository);
    }

    // ---- reads --------------------------------------------------------------

    @Test
    void findById_returnsTenant_whenPresent() {
        UUID id = UUID.randomUUID();
        TenantRow saved = row(UUID.randomUUID(), UUID.randomUUID(), LocalDate.now(), null);

        when(tenantRepository.findById(id)).thenReturn(Optional.of(saved));

        Optional<Tenant> result = tenantService.findById(id);

        assertTrue(result.isPresent());
        assertEquals(saved.uuid(), result.get().uuid());
    }

    @Test
    void findById_returnsEmpty_whenNotFound() {
        when(tenantRepository.findById(any())).thenReturn(Optional.empty());

        assertTrue(tenantService.findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    void findActiveByTenancy_returnsTheWholeHousehold() {
        UUID tenancyId = UUID.randomUUID();
        TenantRow one = row(tenancyId, UUID.randomUUID(), LocalDate.now().minusYears(1), null);
        TenantRow two = row(tenancyId, UUID.randomUUID(), LocalDate.now().minusMonths(2), null);

        when(tenantRepository.findActiveByTenancy(tenancyId)).thenReturn(List.of(one, two));

        List<Tenant> result = tenantService.findActiveByTenancy(tenancyId);

        assertEquals(2, result.size());
        assertEquals(one.uuid(), result.get(0).uuid());
        assertEquals(two.uuid(), result.get(1).uuid());
    }

    // ---- patch --------------------------------------------------------------

    @Test
    void patchTenant_setsEndDate_whichIsTheMoveOut() {
        UUID id = UUID.randomUUID();
        TenantRow before = row(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2025, 1, 1), null);
        TenantRow after = row(before.tenancyId(), before.personId(), before.startDate(), LocalDate.of(2026, 1, 1));

        when(tenantRepository.findById(id)).thenReturn(Optional.of(before));
        when(tenantRepository.patch(eq(id), any())).thenReturn(Optional.of(after));

        Map<String, Object> changes = new HashMap<>();
        changes.put("end_date", "2026-01-01");

        Optional<Tenant> result = tenantService.patchTenant(id, changes);

        assertTrue(result.isPresent());
        assertEquals(LocalDate.of(2026, 1, 1), result.get().endDate());
        verify(auditService).recordUpdate(eq("tenant"), eq(id), any(), any());
    }

    @Test
    void patchTenant_returnsEmpty_whenNotFound() {
        when(tenantRepository.findById(any())).thenReturn(Optional.empty());

        Optional<Tenant> result = tenantService.patchTenant(UUID.randomUUID(), Map.of("end_date", "2026-01-01"));

        assertTrue(result.isEmpty());
    }

    @Test
    void patchTenant_clearsEndDate_whenNullProvided() {
        UUID id = UUID.randomUUID();
        TenantRow before = row(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1));
        TenantRow after = row(before.tenancyId(), before.personId(), before.startDate(), null);

        when(tenantRepository.findById(id)).thenReturn(Optional.of(before));
        when(tenantRepository.findActiveByTenancyAndPerson(before.tenancyId(), before.personId()))
                .thenReturn(Optional.empty());
        when(tenantRepository.patch(eq(id), any())).thenReturn(Optional.of(after));

        Map<String, Object> changes = new HashMap<>();
        changes.put("end_date", null);

        Optional<Tenant> result = tenantService.patchTenant(id, changes);

        assertTrue(result.isPresent());
        assertNull(result.get().endDate());
    }

    // Clearing an end_date is the second way past uq_tenant_active_person, so it
    // is refused when the person has since been added back under a newer row.
    @Test
    void patchTenant_refusesToClearEndDate_whenThePersonIsAlreadyBack() {
        UUID id = UUID.randomUUID();
        TenantRow before = row(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1));
        TenantRow current = row(before.tenancyId(), before.personId(), LocalDate.of(2026, 1, 1), null);

        when(tenantRepository.findById(id)).thenReturn(Optional.of(before));
        when(tenantRepository.findActiveByTenancyAndPerson(before.tenancyId(), before.personId()))
                .thenReturn(Optional.of(current));

        Map<String, Object> changes = new HashMap<>();
        changes.put("end_date", null);

        assertThrows(IllegalStateException.class, () -> tenantService.patchTenant(id, changes));

        verify(tenantRepository, never()).patch(any(), any());
    }

    @Test
    void patchTenant_refusesAnEndDateBeforeTheStartDate() {
        UUID id = UUID.randomUUID();
        TenantRow before = row(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 6, 1), null);

        when(tenantRepository.findById(id)).thenReturn(Optional.of(before));

        Map<String, Object> changes = new HashMap<>();
        changes.put("end_date", "2026-01-01");

        assertThrows(IllegalArgumentException.class, () -> tenantService.patchTenant(id, changes));

        verify(tenantRepository, never()).patch(any(), any());
    }

    @Test
    void patchTenant_rejectsAnUnparseableDate() {
        UUID id = UUID.randomUUID();
        TenantRow before = row(UUID.randomUUID(), UUID.randomUUID(), LocalDate.now(), null);

        when(tenantRepository.findById(id)).thenReturn(Optional.of(before));

        Map<String, Object> changes = new HashMap<>();
        changes.put("start_date", "the first of never");

        assertThrows(IllegalArgumentException.class, () -> tenantService.patchTenant(id, changes));
    }

    @Test
    void patchTenant_writesNothing_whenTheValueAlreadyMatches() {
        UUID id = UUID.randomUUID();
        TenantRow before = row(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 1, 1), null);

        when(tenantRepository.findById(id)).thenReturn(Optional.of(before));

        Map<String, Object> changes = new HashMap<>();
        changes.put("start_date", "2026-01-01");

        Optional<Tenant> result = tenantService.patchTenant(id, changes);

        assertTrue(result.isPresent());
        verify(tenantRepository, never()).patch(any(), any());
        verify(auditService, never()).recordUpdate(any(), any(), any(), any());
    }

    @Test
    void patchTenant_doesNotModifyOmittedFields() {
        UUID id = UUID.randomUUID();
        TenantRow before = row(UUID.randomUUID(), UUID.randomUUID(), LocalDate.now(), LocalDate.of(2027, 1, 1));
        TenantRow after = row(before.tenancyId(), before.personId(), LocalDate.of(2026, 1, 1), before.endDate());

        when(tenantRepository.findById(id)).thenReturn(Optional.of(before));
        when(tenantRepository.patch(eq(id), any())).thenReturn(Optional.of(after));

        Map<String, Object> changes = new HashMap<>();
        changes.put("start_date", "2026-01-01");

        Optional<Tenant> result = tenantService.patchTenant(id, changes);

        assertTrue(result.isPresent());
        assertEquals(LocalDate.of(2026, 1, 1), result.get().startDate());
        assertEquals(LocalDate.of(2027, 1, 1), result.get().endDate());
    }

    // ---- softDelete ---------------------------------------------------------

    // Regression: this used to record the delete under "tenancy", so removing a
    // tenant wrote an audit row pointing at a table the uuid is not in.
    @Test
    void softDelete_recordsTheAuditAgainstTenant() {
        UUID id = UUID.randomUUID();
        TenantRow saved = row(UUID.randomUUID(), UUID.randomUUID(), LocalDate.now(), null);

        when(tenantRepository.findById(id)).thenReturn(Optional.of(saved));
        when(tenantRepository.softDelete(id)).thenReturn(true);

        assertTrue(tenantService.softDelete(id));

        verify(auditService).recordDelete(eq("tenant"), eq(id), any());
    }

    @Test
    void softDelete_returnsFalse_whenNotFound() {
        when(tenantRepository.findById(any())).thenReturn(Optional.empty());

        assertFalse(tenantService.softDelete(UUID.randomUUID()));

        verify(tenantRepository, never()).softDelete(any());
        verify(auditService, never()).recordDelete(any(), any(), any());
    }

    @Test
    void softDelete_returnsFalse_whenTheRowWasAlreadyDeleted() {
        UUID id = UUID.randomUUID();
        TenantRow saved = row(UUID.randomUUID(), UUID.randomUUID(), LocalDate.now(), null);

        when(tenantRepository.findById(id)).thenReturn(Optional.of(saved));
        when(tenantRepository.softDelete(id)).thenReturn(false);

        assertFalse(tenantService.softDelete(id));

        verify(auditService, never()).recordDelete(any(), any(), any());
    }
}