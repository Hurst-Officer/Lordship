package io.github.lordship.securitydeposits;

import io.github.lordship.audit.AuditContext;
import io.github.lordship.audit.AuditService;
import io.github.lordship.securitydeposits.internal.SecurityDepositRepository;
import io.github.lordship.securitydeposits.internal.SecurityDepositRow;
import io.github.lordship.shared.SystemPrincipal;
import io.github.lordship.tenancy.Tenancy;
import io.github.lordship.tenancy.TenancyService;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class SecurityDepositServiceTest {

    private static final UUID TENANCY = UUID.randomUUID();
    private static final LocalDate COLLECTED = LocalDate.of(2026, 9, 1);

    @Mock
    SecurityDepositRepository securityDepositRepository;

    @Mock
    TenancyService tenancyService;

    @Mock
    AuditService auditService;

    @Mock
    AuditContext auditContext;

    @InjectMocks
    SecurityDepositService securityDepositService;

    // ── Creating ─────────────────────────────────────────────────────────────

    @Test
    void create_shouldRefuseMigration_andPointAtTheOtherEndpoint() {
        // Arrange -- MIGRATION is a different act with a different permission,
        // not a value an agent picks off a list

        // Act / Assert
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> securityDepositService.create(
                        TENANCY, new BigDecimal("900.00"), COLLECTED, SecurityDepositSource.MIGRATION));
        assertTrue(thrown.getMessage().contains("migrations endpoint"), thrown.getMessage());
        verify(securityDepositRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void create_shouldSaveAndRecordAnInsert() {
        // Arrange
        SecurityDepositRow stubRow = deposit(UUID.randomUUID(), SecurityDepositSource.LEASE, COLLECTED, null);
        when(tenancyService.findTenancyById(TENANCY)).thenReturn(Optional.of(tenancy()));
        when(securityDepositRepository.save(any())).thenReturn(stubRow);

        // Act
        Optional<SecurityDeposit> result = securityDepositService.create(
                TENANCY, new BigDecimal("900.00"), COLLECTED, SecurityDepositSource.LEASE);

        // Assert
        assertTrue(result.isPresent());
        assertTrue(result.get().isHeld(), "a deposit is held until it is settled");
        verify(auditService).recordInsert(eq("security_deposit"), eq(stubRow.uuid()), any());
    }

    @Test
    void create_shouldCarryTheCollectionDate_ratherThanWaitingForAPatch() {
        // Arrange -- the amount and the date are on the same cheque
        when(tenancyService.findTenancyById(TENANCY)).thenReturn(Optional.of(tenancy()));
        when(securityDepositRepository.save(any()))
                .thenReturn(deposit(UUID.randomUUID(), SecurityDepositSource.LEASE, COLLECTED, null));

        // Act
        securityDepositService.create(TENANCY, new BigDecimal("900.00"), COLLECTED, SecurityDepositSource.LEASE);

        // Assert
        verify(securityDepositRepository).save(argThat(row -> COLLECTED.equals(row.collectedOn())));
    }

    @Test
    void create_shouldReturnEmpty_whenTheTenancyDoesNotExist() {
        // Arrange
        when(tenancyService.findTenancyById(TENANCY)).thenReturn(Optional.empty());

        // Act
        Optional<SecurityDeposit> result = securityDepositService.create(
                TENANCY, new BigDecimal("900.00"), COLLECTED, SecurityDepositSource.LEASE);

        // Assert
        assertTrue(result.isEmpty());
        verify(securityDepositRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void create_shouldAttributeToSystem_whenThereIsNoRequest() {
        // Arrange -- a unit test has no bound request, so ActingAgent falls back
        when(tenancyService.findTenancyById(any())).thenReturn(Optional.of(tenancy()));
        when(securityDepositRepository.save(any()))
                .thenReturn(deposit(UUID.randomUUID(), SecurityDepositSource.LEASE, COLLECTED, null));

        // Act
        securityDepositService.create(TENANCY, new BigDecimal("900.00"), COLLECTED, SecurityDepositSource.LEASE);

        // Assert
        verify(securityDepositRepository).save(argThat(
                row -> SystemPrincipal.AGENT_UUID.equals(row.createdBy())));
    }

    @Test
    void createMigrated_shouldStampMigrationWithoutBeingAsked() {
        // Arrange
        when(tenancyService.findTenancyById(TENANCY)).thenReturn(Optional.of(tenancy()));
        when(securityDepositRepository.save(any()))
                .thenReturn(deposit(UUID.randomUUID(), SecurityDepositSource.MIGRATION, null, null));

        // Act -- no collection date: the previous owner's records are what they are
        securityDepositService.createMigrated(TENANCY, new BigDecimal("650.00"), null);

        // Assert
        verify(securityDepositRepository).save(argThat(
                row -> row.source() == SecurityDepositSource.MIGRATION && row.collectedOn() == null));
    }

    // ── Patching, and the coercion the untyped map needs ─────────────────────

    @Test
    void patchDeposit_shouldCoerceADateString_ratherThanLettingPostgresRefuseIt() {
        // Arrange -- Jackson hands us "2026-09-10"; the column is a date, and
        // binding the String is a BadSqlGrammarException the caller sees as 500
        SecurityDepositRow before = deposit(UUID.randomUUID(), SecurityDepositSource.LEASE, COLLECTED, null);
        when(securityDepositRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(securityDepositRepository.patch(eq(before.uuid()), any())).thenReturn(Optional.of(before));

        // Act
        Map<String, Object> changes = new HashMap<>();
        changes.put("settled_on", "2026-09-10");
        securityDepositService.patchDeposit(before.uuid(), changes);

        // Assert
        verify(securityDepositRepository).patch(eq(before.uuid()), argThat(
                map -> LocalDate.of(2026, 9, 10).equals(map.get("settled_on"))));
    }

    @Test
    void patchDeposit_shouldCoerceAUuidString() {
        // Arrange -- same trap, different column type
        SecurityDepositRow before = deposit(UUID.randomUUID(), SecurityDepositSource.LEASE, COLLECTED, null);
        UUID instrument = UUID.randomUUID();
        when(securityDepositRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(securityDepositRepository.patch(eq(before.uuid()), any())).thenReturn(Optional.of(before));

        // Act
        Map<String, Object> changes = new HashMap<>();
        changes.put("instrument", instrument.toString());
        securityDepositService.patchDeposit(before.uuid(), changes);

        // Assert
        verify(securityDepositRepository).patch(eq(before.uuid()), argThat(
                map -> instrument.equals(map.get("instrument"))));
    }

    @Test
    void patchDeposit_shouldPassNullThrough_soADepositCanBeUnsettled() {
        // Arrange -- clearing settled_on puts the deposit back on the rent roll
        SecurityDepositRow before = deposit(
                UUID.randomUUID(), SecurityDepositSource.LEASE, COLLECTED, LocalDate.of(2026, 9, 10));
        when(securityDepositRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(securityDepositRepository.patch(eq(before.uuid()), any())).thenReturn(Optional.of(before));

        // Act
        Map<String, Object> changes = new HashMap<>();
        changes.put("settled_on", null);
        securityDepositService.patchDeposit(before.uuid(), changes);

        // Assert
        verify(securityDepositRepository).patch(eq(before.uuid()), argThat(
                map -> map.containsKey("settled_on") && map.get("settled_on") == null));
    }

    @Test
    void patchDeposit_shouldThrow_whenTheDateIsNotADate() {
        // Arrange
        SecurityDepositRow before = deposit(UUID.randomUUID(), SecurityDepositSource.LEASE, COLLECTED, null);
        when(securityDepositRepository.findById(before.uuid())).thenReturn(Optional.of(before));

        // Act / Assert -- the message has to name the column, or the caller is
        // guessing which of two dates they got wrong
        Map<String, Object> changes = new HashMap<>();
        changes.put("collected_on", "last Tuesday");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> securityDepositService.patchDeposit(before.uuid(), changes));
        assertTrue(thrown.getMessage().contains("collected_on"), thrown.getMessage());
        verify(securityDepositRepository, never()).patch(any(), any());
    }

    @Test
    void patchDeposit_shouldNotRecordAudit_whenNothingActuallyChanged() {
        // Arrange -- the log is a record of state changes, not of attempts
        SecurityDepositRow before = deposit(UUID.randomUUID(), SecurityDepositSource.LEASE, COLLECTED, null);
        when(securityDepositRepository.findById(before.uuid())).thenReturn(Optional.of(before));
        when(securityDepositRepository.patch(eq(before.uuid()), any())).thenReturn(Optional.of(before));

        // Act
        Map<String, Object> changes = new HashMap<>();
        changes.put("note", before.note());
        securityDepositService.patchDeposit(before.uuid(), changes);

        // Assert
        verifyNoInteractions(auditService);
    }

    @Test
    void patchDeposit_shouldReturnEmpty_whenTheDepositDoesNotExist() {
        // Arrange
        UUID unknownUUID = UUID.randomUUID();
        when(securityDepositRepository.findById(unknownUUID)).thenReturn(Optional.empty());

        // Act
        Optional<SecurityDeposit> result =
                securityDepositService.patchDeposit(unknownUUID, Map.of("note", "nope"));

        // Assert
        assertTrue(result.isEmpty());
        verify(securityDepositRepository, never()).patch(any(), any());
        verifyNoInteractions(auditService);
    }

    // ── Deleting ─────────────────────────────────────────────────────────────

    @Test
    void deleteDeposit_shouldRecordADelete_whenARowWasAffected() {
        // Arrange
        SecurityDepositRow existing = deposit(UUID.randomUUID(), SecurityDepositSource.LEASE, COLLECTED, null);
        when(securityDepositRepository.findById(existing.uuid())).thenReturn(Optional.of(existing));
        when(securityDepositRepository.softDelete(existing.uuid())).thenReturn(true);

        // Act
        boolean result = securityDepositService.deleteDeposit(existing.uuid());

        // Assert
        assertTrue(result);
        verify(auditService).recordDelete(eq("security_deposit"), eq(existing.uuid()), any());
    }

    @Test
    void deleteDeposit_shouldReturnFalse_andNotRecordAudit_whenNotFound() {
        // Arrange
        UUID unknownUUID = UUID.randomUUID();
        when(securityDepositRepository.findById(unknownUUID)).thenReturn(Optional.empty());

        // Act
        boolean result = securityDepositService.deleteDeposit(unknownUUID);

        // Assert
        assertFalse(result);
        verify(securityDepositRepository, never()).softDelete(any());
        verifyNoInteractions(auditService);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static Tenancy tenancy() {
        return new Tenancy(TENANCY, UUID.randomUUID(), LocalDate.of(2026, 1, 1), null,
                false, false, true, false, "",
                OffsetDateTime.now(ZoneOffset.UTC), null);
    }

    private static SecurityDepositRow deposit(UUID uuid,
                                              SecurityDepositSource source,
                                              LocalDate collectedOn,
                                              LocalDate settledOn) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new SecurityDepositRow(
                uuid, TENANCY, null, source, new BigDecimal("900.00"),
                collectedOn, settledOn,
                "test description", "test note",
                SystemPrincipal.AGENT_UUID, now, null);
    }
}