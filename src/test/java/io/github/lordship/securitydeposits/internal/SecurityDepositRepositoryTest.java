package io.github.lordship.securitydeposits.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.lots.internal.LotRow;
import io.github.lordship.properties.internal.PropertyRow;
import io.github.lordship.securitydeposits.HeldDeposit;
import io.github.lordship.securitydeposits.SecurityDepositSource;
import io.github.lordship.shared.SystemPrincipal;
import io.github.lordship.tenancy.internal.TenancyRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
public class SecurityDepositRepositoryTest extends IntegrationTest {

    @Autowired
    SecurityDepositRepository securityDepositRepository;

    @Autowired
    JdbcClient jdbc;

    private UUID property;
    private UUID lot;
    private UUID tenancy;

    @BeforeEach
    void setUp() {
        PropertyRow propertyRow = testData.insertProperty("SD");
        LotRow lotRow = testData.insertLot(propertyRow.uuid(), "1");
        TenancyRow tenancyRow = testData.insertTenancy(lotRow.uuid());

        property = propertyRow.uuid();
        lot = lotRow.uuid();
        tenancy = tenancyRow.uuid();
    }

    // ── save and the row mapper ───────────────────────────────────────────────

    @Test
    void save_shouldFillInTheDatabaseGeneratedColumns() {
        // Act
        SecurityDepositRow saved = securityDepositRepository.save(collected(new BigDecimal("900.00")));

        // Assert
        assertNotNull(saved.uuid());
        assertNotNull(saved.createdAt());
        assertNull(saved.settledOn(), "a new deposit is held");
        assertNull(saved.deletedAt());
        assertEquals(SecurityDepositSource.LEASE, saved.source());
        assertEquals(LocalDate.of(2026, 9, 1), saved.collectedOn());
    }

    // Reading the WRONG column silently returns someone else's value, and two
    // dates sit next to each other here, so each gets a value nothing shares.
    @Test
    void rowMapper_shouldPopulateEveryColumn_onARoundTrip() {
        // Arrange
        SecurityDepositRow saved = securityDepositRepository.save(collected(new BigDecimal("912.34")));
        securityDepositRepository.patch(saved.uuid(), Map.of(
                "collected_on", LocalDate.of(2026, 3, 4),
                "settled_on", LocalDate.of(2026, 5, 6),
                "description", "Tony and Linda both paid half",
                "note", "refunded with check #3120"));

        // Act
        SecurityDepositRow reread = securityDepositRepository.findById(saved.uuid()).orElseThrow();

        // Assert
        assertEquals(tenancy, reread.tenancy());
        assertNull(reread.instrument());
        assertEquals(SecurityDepositSource.LEASE, reread.source());
        assertEquals(0, new BigDecimal("912.34").compareTo(reread.amount()));
        assertEquals(LocalDate.of(2026, 3, 4), reread.collectedOn());
        assertEquals(LocalDate.of(2026, 5, 6), reread.settledOn());
        assertEquals("Tony and Linda both paid half", reread.description());
        assertEquals("refunded with check #3120", reread.note());
        assertEquals(SystemPrincipal.AGENT_UUID, reread.createdBy());
        assertNotNull(reread.createdAt());
        assertNull(reread.deletedAt());
    }

    @Test
    void findById_shouldNotReturnASoftDeletedDeposit() {
        // Arrange
        SecurityDepositRow saved = securityDepositRepository.save(collected(new BigDecimal("900.00")));
        securityDepositRepository.softDelete(saved.uuid());

        // Act / Assert
        assertTrue(securityDepositRepository.findById(saved.uuid()).isEmpty());
    }

    @Test
    void findByTenancy_shouldReturnTheMostRecentlyCollectedFirst() {
        // Arrange -- a deposit paid over two months is two rows
        SecurityDepositRow first = securityDepositRepository.save(collected(new BigDecimal("400.00")));
        SecurityDepositRow second = securityDepositRepository.save(collected(new BigDecimal("500.00")));
        securityDepositRepository.patch(second.uuid(), Map.of("collected_on", LocalDate.of(2026, 10, 1)));

        // Act
        List<SecurityDepositRow> found = securityDepositRepository.findByTenancy(tenancy);

        // Assert
        assertEquals(2, found.size());
        assertEquals(second.uuid(), found.get(0).uuid());
        assertEquals(first.uuid(), found.get(1).uuid());
    }

    // ── What the park is still holding ────────────────────────────────────────

    @Test
    void findHeldByProperty_shouldCarryTheLotContext() {
        // Arrange
        securityDepositRepository.save(collected(new BigDecimal("900.00")));

        // Act
        List<HeldDeposit> held = securityDepositRepository.findHeldByProperty(property);

        // Assert -- the rent roll needs the lot, not just the tenancy uuid
        assertEquals(1, held.size());
        HeldDeposit row = held.get(0);
        assertEquals(lot, row.lot());
        assertEquals("1", row.lotNumber());
        assertEquals(0, new BigDecimal("900.00").compareTo(row.amount()));
        assertNull(row.tenancyEndedOn(), "the tenant is still there");
        assertNull(row.daysSinceTenancyEnded());
    }

    @Test
    void findHeldByProperty_shouldExcludeASettledDeposit() {
        // Arrange -- settling is what takes a deposit off the rent roll
        SecurityDepositRow saved = securityDepositRepository.save(collected(new BigDecimal("900.00")));
        securityDepositRepository.patch(saved.uuid(), Map.of("settled_on", LocalDate.of(2026, 9, 30)));

        // Act / Assert
        assertTrue(securityDepositRepository.findHeldByProperty(property).isEmpty());
    }

    // ── The clock ─────────────────────────────────────────────────────────────

    @Test
    void findAgingByProperty_shouldIgnoreATenancyThatHasNotEnded() {
        // Arrange
        securityDepositRepository.save(collected(new BigDecimal("900.00")));

        // Act / Assert -- nothing is overdue while the tenant lives there
        assertTrue(securityDepositRepository.findAgingByProperty(property).isEmpty());
    }

    @Test
    void findAgingByProperty_shouldCountTheDaysSinceTheTenancyEnded() {
        // Arrange -- the query reads CURRENT_DATE, so the fixture is relative
        securityDepositRepository.save(collected(new BigDecimal("900.00")));
        endTenancy(tenancy, LocalDate.now().minusDays(45));

        // Act
        List<HeldDeposit> aging = securityDepositRepository.findAgingByProperty(property);

        // Assert -- no deadline is applied; the elapsed days are the answer
        assertEquals(1, aging.size());
        assertEquals(45, aging.get(0).daysSinceTenancyEnded());
        assertEquals(LocalDate.now().minusDays(45), aging.get(0).tenancyEndedOn());
    }

    @Test
    void findAgingByProperty_shouldPutTheOldestFirst() {
        // Arrange -- two ended tenancies at one park
        securityDepositRepository.save(collected(new BigDecimal("900.00")));
        endTenancy(tenancy, LocalDate.now().minusDays(30));

        LotRow secondLot = testData.insertLot(property, "2");
        UUID secondTenancy = testData.insertTenancy(secondLot.uuid()).uuid();
        securityDepositRepository.save(SecurityDepositRow.forInsert(
                secondTenancy, new BigDecimal("650.00"), SecurityDepositSource.MIGRATION,
                null, SystemPrincipal.AGENT_UUID));
        endTenancy(secondTenancy, LocalDate.now().minusDays(400));

        // Act
        List<HeldDeposit> aging = securityDepositRepository.findAgingByProperty(property);

        // Assert -- the one closest to being a problem is at the top
        assertEquals(2, aging.size());
        assertEquals(secondTenancy, aging.get(0).tenancy());
        assertEquals(400, aging.get(0).daysSinceTenancyEnded());
    }

    // ── Sums and guards ───────────────────────────────────────────────────────

    @Test
    void sumHeldByTenancy_shouldAddTheUnsettledRowsOnly() {
        // Arrange
        securityDepositRepository.save(collected(new BigDecimal("400.00")));
        securityDepositRepository.save(collected(new BigDecimal("500.00")));
        SecurityDepositRow settled = securityDepositRepository.save(collected(new BigDecimal("100.00")));
        securityDepositRepository.patch(settled.uuid(), Map.of("settled_on", LocalDate.of(2026, 9, 30)));

        // Act / Assert -- money already given back is not money held
        assertEquals(0, new BigDecimal("900.00")
                .compareTo(securityDepositRepository.sumHeldByTenancy(tenancy)));
    }

    @Test
    void sumHeldByTenancy_shouldBeZero_whenNothingHasBeenCollected() {
        // Act / Assert -- the absence of a row is what "deposit outstanding" means
        assertEquals(0, BigDecimal.ZERO.compareTo(securityDepositRepository.sumHeldByTenancy(tenancy)));
    }

    @Test
    void patch_shouldRejectAColumnOutsideTheWhitelist() {
        // Arrange -- source and tenancy are set once at creation
        SecurityDepositRow saved = securityDepositRepository.save(collected(new BigDecimal("900.00")));

        // Act / Assert -- persistence-exception translation rewraps the type
        assertThrows(InvalidDataAccessApiUsageException.class,
                () -> securityDepositRepository.patch(saved.uuid(), Map.of("source", "MIGRATION")));
    }

    @Test
    void settlingBeforeCollecting_shouldRaiseTheDatesConstraint() {
        // Arrange
        SecurityDepositRow saved = securityDepositRepository.save(collected(new BigDecimal("900.00")));

        // Act / Assert -- security_deposit_dates_ordered
        assertThrows(DataIntegrityViolationException.class,
                () -> securityDepositRepository.patch(
                        saved.uuid(), Map.of("settled_on", LocalDate.of(2026, 8, 1))));
    }

    @Test
    void save_shouldRefuseAZeroAmount() {
        // Act / Assert -- security_deposit_amount_must_be_positive
        assertThrows(DataIntegrityViolationException.class,
                () -> securityDepositRepository.save(collected(BigDecimal.ZERO)));
    }

    @Test
    void softDelete_shouldReturnFalseTheSecondTime() {
        // Arrange -- the service only writes an audit row when one was affected
        SecurityDepositRow saved = securityDepositRepository.save(collected(new BigDecimal("900.00")));

        // Act / Assert
        assertTrue(securityDepositRepository.softDelete(saved.uuid()));
        assertFalse(securityDepositRepository.softDelete(saved.uuid()));
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private SecurityDepositRow collected(BigDecimal amount) {
        return SecurityDepositRow.forInsert(
                tenancy, amount, SecurityDepositSource.LEASE,
                LocalDate.of(2026, 9, 1), SystemPrincipal.AGENT_UUID);
    }

    // Straight through jdbc: ending a tenancy is TenancyService's business, and
    // a repository test has no principal for the audit write that would pull in.
    private void endTenancy(UUID uuid, LocalDate endDate) {
        jdbc.sql("UPDATE tenancy SET end_date = :endDate WHERE uuid = :uuid")
                .param("endDate", endDate)
                .param("uuid", uuid)
                .update();
    }
}