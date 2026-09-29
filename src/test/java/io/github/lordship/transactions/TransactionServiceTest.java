package io.github.lordship.transactions;

import io.github.lordship.accounts.Account;
import io.github.lordship.accounts.AccountService;
import io.github.lordship.audit.AuditService;
import io.github.lordship.lots.Lot;
import io.github.lordship.lots.LotService;
import io.github.lordship.properties.Property;
import io.github.lordship.properties.PropertyService;
import io.github.lordship.tenancy.Tenancy;
import io.github.lordship.tenancy.TenancyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class TransactionServiceTest {

    @Autowired
    TransactionService transactionService;

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

    private Account createTestAccount() {
        Property property = propertyService.createProperty("Test Mobile Park", "999 Test Ave", "Testville", "WA", "98000");
        Lot lot = lotService.createLot(property.uuid(), "1");
        UUID tenancyId = tenancyService.create(lot.uuid()).uuid();
        return accountService.getAccountByTenancyId(tenancyId).orElseThrow();
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void postTransaction_returnsTransactionWithCorrectFields() {
        Account account = createTestAccount();
        LocalDate billingPeriod = LocalDate.now();

        Transaction tx = transactionService.postTransaction(
                account.uuid(),
                TransactionType.RENT_CHARGE,
                new BigDecimal("150.00"),
                "Rent charge",
                billingPeriod
        );

        assertNotNull(tx.uuid());
        assertEquals(account.uuid(), tx.accountId());
        assertEquals(TransactionType.RENT_CHARGE, tx.type());
        assertEquals(0, new BigDecimal("150.00").compareTo(tx.amount()));
        assertEquals("Rent charge", tx.description());
        assertEquals(billingPeriod, tx.billingPeriod());
        assertNull(tx.deletedAt());

        verify(auditService).recordInsert(eq("transaction"), eq(tx.uuid()), any());
    }

    @Test
    void findById_returnsPostedTransaction() {
        Account account = createTestAccount();

        Transaction posted = transactionService.postTransaction(
                account.uuid(), TransactionType.RENT_CHARGE, new BigDecimal("200.00"), null, LocalDate.now()
        );

        Transaction found = transactionService.findById(posted.uuid());

        assertEquals(posted.uuid(), found.uuid());
        assertEquals(TransactionType.RENT_CHARGE, found.type());
        assertEquals(0, new BigDecimal("200.00").compareTo(found.amount()));
    }

    @Test
    void findById_throwsWhenNotFound() {
        assertThrows(NoSuchElementException.class, () ->
                transactionService.findById(UUID.randomUUID())
        );
    }

    @Test
    void findByAccountId_returnsAllTransactionsForAccount() {
        Account account = createTestAccount();

        transactionService.postTransaction(account.uuid(), TransactionType.RENT_CHARGE, new BigDecimal("100.00"), "Charge 1", LocalDate.now());
        transactionService.postTransaction(account.uuid(), TransactionType.PAYMENT, new BigDecimal("50.00"), "Payment 1", LocalDate.now());

        List<Transaction> transactions = transactionService.findByAccountId(account.uuid());

        assertEquals(2, transactions.size());
    }

    @Test
    void findByAccountId_returnsEmptyListForAccountWithNoTransactions() {
        Account account = createTestAccount();

        List<Transaction> transactions = transactionService.findByAccountId(account.uuid());

        assertTrue(transactions.isEmpty());
    }

    @Test
    void deleteTransaction_softDeletesMakesTransactionUnfindable() {
        Account account = createTestAccount();

        Transaction tx = transactionService.postTransaction(
                account.uuid(), TransactionType.RENT_CHARGE, new BigDecimal("75.00"), null, LocalDate.now()
        );

        transactionService.deleteTransaction(tx.uuid());

        assertThrows(NoSuchElementException.class, () ->
                transactionService.findById(tx.uuid())
        );

        verify(auditService).recordDelete(eq("transaction"), eq(tx.uuid()), any());
    }

    @Test
    void deleteTransaction_throwsWhenBillingPeriodClosed() {
        Account account = createTestAccount();

        // Post a transaction with a billing period in a past month (closed period)
        Transaction tx = transactionService.postTransaction(
                account.uuid(), TransactionType.RENT_CHARGE, new BigDecimal("75.00"), null,
                LocalDate.now().minusMonths(1)
        );

        assertThrows(IllegalStateException.class, () ->
                transactionService.deleteTransaction(tx.uuid())
        );
    }

    @Test
    void postTransaction_throwsWhenAccountDoesNotAcceptPayments() {
        Account account = createTestAccount();

        // Disable payments on the tenancy
        Tenancy tenancy = tenancyService.findTenancyById(account.tenancyId()).orElseThrow();
        tenancyService.patchTenancy(tenancy.uuid(), java.util.Map.of("accept_payments", false));

        assertThrows(IllegalStateException.class, () ->
                transactionService.postTransaction(account.uuid(), TransactionType.PAYMENT, new BigDecimal("100.00"), null, LocalDate.now())
        );
    }

    @Test
    void postTransaction_throwsWhenDescriptionMissingForManualType() {
        Account account = createTestAccount();

        assertThrows(IllegalArgumentException.class, () ->
                transactionService.postTransaction(account.uuid(), TransactionType.CREDIT, new BigDecimal("50.00"), null, LocalDate.now())
        );
        assertThrows(IllegalArgumentException.class, () ->
                transactionService.postTransaction(account.uuid(), TransactionType.MISC_CHARGE, new BigDecimal("20.00"), "", LocalDate.now())
        );
        assertThrows(IllegalArgumentException.class, () ->
                transactionService.postTransaction(account.uuid(), TransactionType.BALANCE_ADJUSTMENT, new BigDecimal("-25.00"), null, LocalDate.now())
        );
    }

    @Test
    void computeBalance_correctlySumsChargesMinusCredits() {
        Account account = createTestAccount();

        transactionService.postTransaction(account.uuid(), TransactionType.RENT_CHARGE, new BigDecimal("300.00"), null, LocalDate.now());
        transactionService.postTransaction(account.uuid(), TransactionType.PAYMENT, new BigDecimal("100.00"), null, LocalDate.now());

        BigDecimal balance = transactionService.computeBalance(account.uuid());

        // 300 (charge) - 100 (payment) = 200
        assertEquals(0, new BigDecimal("200.00").compareTo(balance));
    }

    @Test
    void computeBalance_returnsZeroForAccountWithNoTransactions() {
        Account account = createTestAccount();

        BigDecimal balance = transactionService.computeBalance(account.uuid());

        assertEquals(0, BigDecimal.ZERO.compareTo(balance));
    }

    @Test
    void postTransaction_syncsBalanceCachedAfterPost() {
        Account account = createTestAccount();

        transactionService.postTransaction(account.uuid(), TransactionType.RENT_CHARGE, new BigDecimal("300.00"), null, LocalDate.now());
        transactionService.postTransaction(account.uuid(), TransactionType.PAYMENT, new BigDecimal("100.00"), null, LocalDate.now());

        BigDecimal cached = accountService.getAccount(account.uuid()).orElseThrow().balanceCached();
        BigDecimal computed = transactionService.computeBalance(account.uuid());

        assertEquals(0, computed.compareTo(cached));
    }

    @Test
    void deleteTransaction_syncesBalanceCachedAfterDelete() {
        Account account = createTestAccount();

        Transaction tx = transactionService.postTransaction(
                account.uuid(), TransactionType.RENT_CHARGE, new BigDecimal("200.00"), null, LocalDate.now()
        );

        transactionService.deleteTransaction(tx.uuid());

        BigDecimal cached = accountService.getAccount(account.uuid()).orElseThrow().balanceCached();

        assertEquals(0, BigDecimal.ZERO.compareTo(cached));
    }

    @Test
    void postTransaction_throwsWhenNonAdjustmentAmountIsNegative() {
        Account account = createTestAccount();

        assertThrows(IllegalArgumentException.class, () ->
                transactionService.postTransaction(account.uuid(), TransactionType.RENT_CHARGE, new BigDecimal("-100.00"), null, LocalDate.now())
        );
        assertThrows(IllegalArgumentException.class, () ->
                transactionService.postTransaction(account.uuid(), TransactionType.PAYMENT, new BigDecimal("-50.00"), null, LocalDate.now())
        );
    }

    @Test
    void postTransaction_throwsWhenNonAdjustmentAmountIsZero() {
        Account account = createTestAccount();

        assertThrows(IllegalArgumentException.class, () ->
                transactionService.postTransaction(account.uuid(), TransactionType.RENT_CHARGE, BigDecimal.ZERO, null, LocalDate.now())
        );
    }

    @Test
    void postTransaction_throwsWhenBalanceAdjustmentAmountIsZero() {
        Account account = createTestAccount();

        assertThrows(IllegalArgumentException.class, () ->
                transactionService.postTransaction(account.uuid(), TransactionType.BALANCE_ADJUSTMENT, BigDecimal.ZERO, "Zero adjustment", LocalDate.now())
        );
    }

    @Test
    void computeBalance_handlesSignedBalanceAdjustment() {
        Account account = createTestAccount();

        transactionService.postTransaction(account.uuid(), TransactionType.RENT_CHARGE, new BigDecimal("500.00"), null, LocalDate.now());
        transactionService.postTransaction(account.uuid(), TransactionType.BALANCE_ADJUSTMENT, new BigDecimal("-100.00"), "Correction credit", LocalDate.now());

        BigDecimal balance = transactionService.computeBalance(account.uuid());

        // 500 charge + (-100 adjustment) = 400
        assertEquals(0, new BigDecimal("400.00").compareTo(balance));
    }
}
