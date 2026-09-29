package io.github.lordship.accounts;

import io.github.lordship.audit.AuditService;
import io.github.lordship.lots.Lot;
import io.github.lordship.lots.LotService;
import io.github.lordship.properties.Property;
import io.github.lordship.properties.PropertyService;
import io.github.lordship.tenancy.TenancyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class AccountServiceTest {

    @Autowired
    AccountService accountService;

    @Autowired
    PropertyService propertyService;

    @Autowired
    LotService lotService;

    @Autowired
    TenancyService tenancyService;

    @MockitoBean
    AuditService auditService;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID setupFullChain() {
        Property property = propertyService.createProperty("Test Mobile Park", "999 Test Ave", "Testville", "WA", "98000");
        Lot lot = lotService.createLot(property.uuid(), "1");
        return tenancyService.create(lot.uuid()).uuid();
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void createTenancy_autoCreatesAccountWithDefaults() {
        UUID tenancyId = setupFullChain();

        Account account = accountService.getAccountByTenancyId(tenancyId).orElseThrow();

        assertNotNull(account.uuid());
        assertEquals(tenancyId, account.tenancyId());
        assertEquals(AccountStatus.ACTIVE, account.accountStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(account.balanceCached()));
        assertFalse(account.autopayEnabled());
        assertNull(account.notes());

        verify(auditService).recordInsert(eq("account"), eq(account.uuid()), any());
    }

    @Test
    void getAccount_returnsAccountWhenExists() {
        UUID tenancyId = setupFullChain();
        Account created = accountService.getAccountByTenancyId(tenancyId).orElseThrow();

        Optional<Account> found = accountService.getAccount(created.uuid());

        assertTrue(found.isPresent());
        assertEquals(created.uuid(), found.get().uuid());
        assertEquals(AccountStatus.ACTIVE, found.get().accountStatus());
    }

    @Test
    void getAccount_returnsEmptyWhenNotExists() {
        Optional<Account> found = accountService.getAccount(UUID.randomUUID());

        assertTrue(found.isEmpty());
    }

    @Test
    void updateAccount_updatesCorrectFields() {
        UUID tenancyId = setupFullChain();
        Account created = accountService.getAccountByTenancyId(tenancyId).orElseThrow();

        Account toUpdate = new Account(
                created.uuid(),
                created.tenancyId(),
                AccountStatus.DELINQUENT,
                created.balanceCached(),
                true,
                "Late on payment",
                created.createdAt(),
                created.deletedAt()
        );

        Optional<Account> updated = accountService.updateAccount(toUpdate);

        assertTrue(updated.isPresent());
        assertEquals(AccountStatus.DELINQUENT, updated.get().accountStatus());
        assertTrue(updated.get().autopayEnabled());
        assertEquals("Late on payment", updated.get().notes());

        verify(auditService).recordUpdate(eq("account"), eq(created.uuid()), any(), any());
    }

    @Test
    void updateAccount_doesNotChangeBalance() {
        UUID tenancyId = setupFullChain();
        Account created = accountService.getAccountByTenancyId(tenancyId).orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(created.balanceCached()));

        // Pass an account with a different balance — the update SQL does not touch the balance column
        Account toUpdate = new Account(
                created.uuid(),
                created.tenancyId(),
                AccountStatus.ACTIVE,
                new BigDecimal("500.00"),
                false,
                null,
                created.createdAt(),
                created.deletedAt()
        );

        Optional<Account> updated = accountService.updateAccount(toUpdate);

        assertTrue(updated.isPresent());
        assertEquals(0, BigDecimal.ZERO.compareTo(updated.get().balanceCached()));
    }

    @Test
    void deactivateAccount_cannotBeFoundAfterDeletion() {
        UUID tenancyId = setupFullChain();
        Account created = accountService.getAccountByTenancyId(tenancyId).orElseThrow();

        Optional<Account> deleted = accountService.deactivateAccount(created.uuid());
        assertTrue(deleted.isPresent());

        Optional<Account> found = accountService.getAccount(created.uuid());
        assertTrue(found.isEmpty());

        verify(auditService).recordDelete(eq("account"), eq(created.uuid()), any());
    }

    @Test
    void getAccountByTenancyId_returnsCorrectAccount() {
        UUID tenancyId = setupFullChain();
        Account created = accountService.getAccountByTenancyId(tenancyId).orElseThrow();

        Optional<Account> found = accountService.getAccountByTenancyId(tenancyId);

        assertTrue(found.isPresent());
        assertEquals(created.uuid(), found.get().uuid());
        assertEquals(tenancyId, found.get().tenancyId());
    }

    @Test
    void patchAccount_recordsAuditWhenFieldsChange() {
        UUID tenancyId = setupFullChain();
        Account created = accountService.getAccountByTenancyId(tenancyId).orElseThrow();

        accountService.patchAccount(created.uuid(), Map.of("notes", "Updated note"));

        verify(auditService).recordUpdate(eq("account"), eq(created.uuid()), any(), any());
    }

    @Test
    void patchAccount_doesNotRecordAuditWhenNoChange() {
        UUID tenancyId = setupFullChain();
        Account created = accountService.getAccountByTenancyId(tenancyId).orElseThrow();

        // account_status is already ACTIVE — patching with the same value produces no diff
        accountService.patchAccount(created.uuid(), Map.of("account_status", "ACTIVE"));

        verify(auditService, never()).recordUpdate(eq("account"), eq(created.uuid()), any(), any());
    }
}
