package io.github.lordship.tenancyterms.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.lots.internal.LotRow;
import io.github.lordship.properties.internal.PropertyRow;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.SystemPrincipal;
import io.github.lordship.tenancy.internal.TenancyRow;
import io.github.lordship.tenancyterms.RentHistoryYear;
import io.github.lordship.tenancyterms.TenancyTermSource;
import io.github.lordship.tenancyterms.TenancyTermStatus;
import io.github.lordship.termstemplate.TermsTemplate;
import io.github.lordship.termstemplate.internal.TermsTemplateRepository;
import io.github.lordship.termstemplate.internal.TermsTemplateRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The RCW 59.20 disclosure query: what this lot charged, year by year.
 *
 * <p>Its own file because the thing being tested is a span calculation rather
 * than a row round trip -- a rate set once and left alone was charged in every
 * year until something replaced it, and the naive GROUP BY on valid_at finds
 * only the years a new term started.
 */
@Transactional
public class TenancyChargeTermRentHistoryTest extends IntegrationTest {

    @Autowired
    TenancyChargeTermRepository tenancyChargeTermRepository;

    @Autowired
    TermsTemplateRepository termsTemplateRepository;

    private UUID lot;
    private UUID tenancy;
    private TermsTemplate template;

    @BeforeEach
    void setUp() {
        PropertyRow property = testData.insertProperty("RH");
        LotRow lotRow = testData.insertLot(property.uuid(), "1");
        lot = lotRow.uuid();
        tenancy = testData.insertTenancy(lot).uuid();
        template = termsTemplateRepository.save(new TermsTemplateRow(
                property.uuid(), "RH Land Lease", AgreementType.LAND,
                SystemPrincipal.AGENT_UUID)).toTermsTemplate();
    }

    // ---- the span, not the start date ---------------------------------------

    @Test
    void findRentHistoryByLot_shouldCarryARateThroughTheYearsNothingChanged() {
        // Arrange -- set in 2021, left alone until 2024
        activeTerm(tenancy, LocalDate.of(2021, 3, 1), "3600.00");
        activeTerm(tenancy, LocalDate.of(2024, 1, 1), "4050.00");

        // Act
        Map<Integer, BigDecimal> byYear = history(2021, 2025);

        // Assert -- 2022 and 2023 are years the ledger plainly knows, and a
        // GROUP BY on valid_at would have disclosed them as unknown
        assertEquals(0, new BigDecimal("3600.00").compareTo(byYear.get(2022)));
        assertEquals(0, new BigDecimal("3600.00").compareTo(byYear.get(2023)));
        assertEquals(0, new BigDecimal("4050.00").compareTo(byYear.get(2024)));
        assertEquals(0, new BigDecimal("4050.00").compareTo(byYear.get(2025)));
    }

    @Test
    void findRentHistoryByLot_shouldLeaveOutAYearNothingWasInForce() {
        // Arrange -- the company did not hold this lot until 2023
        activeTerm(tenancy, LocalDate.of(2023, 1, 1), "3000.00");

        // Act
        Map<Integer, BigDecimal> byYear = history(2021, 2025);

        // Assert -- absent rather than zero; the caller prints "Unknown"
        assertFalse(byYear.containsKey(2021));
        assertFalse(byYear.containsKey(2022));
        assertTrue(byYear.containsKey(2023));
    }

    // ---- across whoever occupied it -----------------------------------------

    @Test
    void findRentHistoryByLot_shouldTakeTheHighestOfTwoTenanciesInOneYear() {
        // Arrange -- the lot was re-let in July at a higher rate
        UUID second = testData.insertTenancy(lot).uuid();
        activeTerm(tenancy, LocalDate.of(2023, 1, 1), "3000.00");
        activeTerm(second, LocalDate.of(2023, 7, 1), "3300.00");

        // Act + Assert -- the disclosure is about the ground, not about who was
        // standing on it
        assertEquals(0, new BigDecimal("3300.00").compareTo(history(2023, 2023).get(2023)));
    }

    @Test
    void findRentHistoryByLot_shouldNotSeeAnotherLotsTerms() {
        // Arrange
        PropertyRow otherProperty = testData.insertProperty("RH2");
        UUID otherLot = testData.insertLot(otherProperty.uuid(), "9").uuid();
        UUID otherTenancy = testData.insertTenancy(otherLot).uuid();
        activeTerm(otherTenancy, LocalDate.of(2023, 1, 1), "9999.00");
        activeTerm(tenancy, LocalDate.of(2023, 1, 1), "3000.00");

        // Act + Assert
        assertEquals(0, new BigDecimal("3000.00").compareTo(history(2023, 2023).get(2023)));
    }

    // ---- only what was actually charged -------------------------------------

    @Test
    void findRentHistoryByLot_shouldIgnoreATermNobodySigned() {
        // Arrange -- a proposal and a submitted-but-unsigned term were never charged
        activeTerm(tenancy, LocalDate.of(2023, 1, 1), "3000.00");
        proposedTerm(LocalDate.of(2023, 6, 1), "9999.00");
        pendingTerm(LocalDate.of(2023, 7, 1), "8888.00");

        // Act + Assert
        assertEquals(0, new BigDecimal("3000.00").compareTo(history(2023, 2023).get(2023)));
    }

    @Test
    void findRentHistoryByLot_shouldIgnoreARetractedTerm() {
        // Arrange -- cancelled means it was excluded from resolution entirely
        activeTerm(tenancy, LocalDate.of(2023, 1, 1), "3000.00");
        UUID retracted = activeTerm(tenancy, LocalDate.of(2023, 6, 1), "7777.00");
        tenancyChargeTermRepository.cancel(retracted, SystemPrincipal.AGENT_UUID, "entered in error").orElseThrow();

        // Act + Assert
        assertEquals(0, new BigDecimal("3000.00").compareTo(history(2023, 2023).get(2023)));
    }

    // ---- fixtures ------------------------------------------------------------

    private Map<Integer, BigDecimal> history(int fromYear, int toYear) {
        List<RentHistoryYear> rows =
                tenancyChargeTermRepository.findRentHistoryByLot(lot, fromYear, toYear);
        return rows.stream().collect(Collectors.toMap(
                RentHistoryYear::year, RentHistoryYear::highestRate));
    }

    // Sourced MIGRATION because that is the one value term_in_force_needs_paper
    // exempts, which keeps the fixture from needing a whole instrument behind it.
    private UUID activeTerm(UUID forTenancy, LocalDate validAt, String rate) {
        UUID uuid = saveTerm(forTenancy, validAt, rate);
        tenancyChargeTermRepository.updateStatus(
                uuid, TenancyTermStatus.PROPOSED, TenancyTermStatus.PENDING).orElseThrow();
        tenancyChargeTermRepository.updateStatus(
                uuid, TenancyTermStatus.PENDING, TenancyTermStatus.ACTIVE).orElseThrow();
        return uuid;
    }

    private void proposedTerm(LocalDate validAt, String rate) {
        saveTerm(tenancy, validAt, rate);
    }

    private void pendingTerm(LocalDate validAt, String rate) {
        UUID uuid = saveTerm(tenancy, validAt, rate);
        tenancyChargeTermRepository.updateStatus(
                uuid, TenancyTermStatus.PROPOSED, TenancyTermStatus.PENDING).orElseThrow();
    }

    private UUID saveTerm(UUID forTenancy, LocalDate validAt, String rate) {
        return tenancyChargeTermRepository.save(TenancyChargeTermRow.fromTemplate(
                forTenancy, template, new BigDecimal(rate), validAt,
                TenancyTermSource.MIGRATION, null, SystemPrincipal.AGENT_UUID)).uuid();
    }
}
