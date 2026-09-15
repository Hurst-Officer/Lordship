package io.github.lordship.instruments;

import io.github.lordship.lots.Lot;
import io.github.lordship.properties.Property;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.FeeMethod;
import io.github.lordship.shared.InstrumentType;
import io.github.lordship.shared.SecurityDepositMethod;
import io.github.lordship.shared.UtilityMethod;
import io.github.lordship.tenancy.Tenancy;
import io.github.lordship.tenancyterms.TenancyChargeTerm;
import io.github.lordship.tenancyterms.TenancyTermSource;
import io.github.lordship.tenancyterms.TenancyTermStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class TokenResolverTest {

    private static final UUID TENANCY = UUID.randomUUID();
    private static final UUID LOT = UUID.randomUUID();
    private static final UUID PROPERTY = UUID.randomUUID();
    private static final LocalDate START = LocalDate.of(2026, 11, 1);

    // ---- the deal ------------------------------------------------------------

    @Test
    void resolve_shouldPrintTheRateAsDigitsAndWords() {
        // Arrange / Act
        TokenValues values = TokenResolver.resolve(facts(scheduleOfFive()));

        // Assert -- a lease states the figure twice so that altering one
        // contradicts the other
        assertEquals("$4,200.00", values.scalar("term.rate"));
        assertEquals("four thousand two hundred dollars", values.scalar("term.rate_in_words"));
    }

    @Test
    void resolve_shouldPrintTheLastDayCovered_notTheDayAfter() {
        // Arrange -- 60 months from November 1 2026
        TokenValues values = TokenResolver.resolve(facts(scheduleOfFive()));

        // Assert -- printing November 1 2031 would give the tenant a free day
        assertEquals("November 1, 2026", values.scalar("instrument.term_start"));
        assertEquals("October 31, 2031", values.scalar("instrument.term_end"));
        assertEquals("sixty", values.scalar("instrument.term_months_in_words"));
    }

    @Test
    void resolve_shouldCarryTheLotAndProperty() {
        TokenValues values = TokenResolver.resolve(facts(scheduleOfFive()));

        assertEquals("42", values.scalar("lot.lot_number"));
        assertEquals("Harbor View", values.scalar("property.community_name"));
        assertEquals("Port Orchard", values.scalar("property.city"));
        assertEquals("Ada Lovelace and Grace Hopper", values.scalar("tenancy.tenant_names"));
    }

    // ---- the traps -----------------------------------------------------------

    @Test
    void resolve_shouldNotSetTheLateFeePercent_whenTheMethodIsFlat() {
        // Arrange -- late_fee_amount and late_fee_percent are the SAME column
        TokenValues values = TokenResolver.resolve(
                facts(List.of(term(START, "4200", FeeMethod.FLAT, "65.00"))));

        // Assert
        assertEquals("$65.00", values.scalar("term.late_fee_amount"));
        assertNull(values.scalar("term.late_fee_percent"),
                "a flat fee has no percentage to print");
    }

    @Test
    void resolve_shouldNotSetTheLateFeeAmount_whenTheMethodIsPercentOfRent() {
        // Arrange -- the trap: printing the raw column here states "$1.50"
        // on a lease that means 1.5% of rent
        TokenValues values = TokenResolver.resolve(
                facts(List.of(term(START, "4200", FeeMethod.PERCENT_OF_RENT, "1.5"))));

        // Assert
        assertEquals("1.5%", values.scalar("term.late_fee_percent"));
        assertEquals("one and one half percent", values.scalar("term.late_fee_percent_in_words"));
        assertNull(values.scalar("term.late_fee_amount"),
                "a percentage deal has no dollar figure to print");
    }

    @Test
    void resolve_shouldResolveAMultipleOfRentDepositIntoDollars() {
        // Arrange -- the column holds 1.00, which as dollars would state a
        // one dollar deposit on a $4,200 lease
        TokenValues values = TokenResolver.resolve(facts(
                List.of(depositTerm(SecurityDepositMethod.MULTIPLE_OF_RENT, "1.00"))));

        // Assert
        assertEquals("$4,200.00", values.scalar("term.security_deposit"));
        assertEquals("four thousand two hundred dollars",
                values.scalar("term.security_deposit_in_words"));
    }

    @Test
    void resolve_shouldNotSetADeposit_whenNoneIsTaken() {
        // Arrange -- Hurst and Son take no deposit on land leases
        TokenValues values = TokenResolver.resolve(facts(
                List.of(depositTerm(SecurityDepositMethod.NONE, "0"))));

        // Assert -- reported as missing rather than printed as $0.00
        assertNull(values.scalar("term.security_deposit"));
    }

    @Test
    void resolve_shouldDeriveChargedUtilitiesFromTheFourMethods() {
        TokenValues values = TokenResolver.resolve(facts(scheduleOfFive()));

        // water FLAT, sewer RUBS, power and trash NONE
        assertEquals("water and sewer", values.scalar("term.charged_utilities"));
    }

    // ---- the schedule --------------------------------------------------------

    @Test
    void resolve_shouldBuildTheRentScheduleRows() {
        // Act
        TokenValues values = TokenResolver.resolve(facts(scheduleOfFive()));
        var rows = values.list("term.rent_schedule");

        // Assert -- the last row ends on the lease's last day, which is why the
        // schedule cannot be built from the charge terms alone
        assertEquals(5, rows.size());
        assertEquals("November 1, 2026 - October 31, 2027", rows.get(0).get("rent_step.period"));
        assertEquals("$4,200.00", rows.get(0).get("rent_step.rate"));
        assertEquals("November 1, 2030 - October 31, 2031", rows.get(4).get("rent_step.period"));
        assertEquals("$4,913.41", rows.get(4).get("rent_step.rate"));
    }

    @Test
    void resolve_shouldMarkAScheduledLease_soAClauseCanBranchOnIt() {
        assertEquals("SCHEDULED",
                TokenResolver.resolve(facts(scheduleOfFive())).scalar("term.rent_schedule_type"));
    }

    @Test
    void resolve_shouldMarkAnOrdinaryLeaseAsSingle() {
        assertEquals("SINGLE",
                TokenResolver.resolve(facts(List.of(term(START, "650", FeeMethod.FLAT, "65.00"))))
                        .scalar("term.rent_schedule_type"));
    }

    // ---- end to end ----------------------------------------------------------

    @Test
    void resolveAndRender_shouldProduceTheCommercialLeasesRentClause() {
        // Arrange -- the clause an admin would author for a stepped lease
        String body = """
                1. BASE MONTHLY RENT SCHEDULE. Tenant shall pay to Landlord base monthly rent \
                during the initial Lease Term according to the following schedule:
                {{#each term.rent_schedule}}{{rent_step.period}}    {{rent_step.rate}}
                {{/each}}""";

        // Act
        TokenValues values = TokenResolver.resolve(facts(scheduleOfFive()));
        BodyRenderer.Rendered out = BodyRenderer.render(body, values);

        // Assert -- the paper and the ledger say the same five numbers
        assertTrue(out.isComplete(), "unresolved: " + out.unresolved());
        assertEquals("""
                1. BASE MONTHLY RENT SCHEDULE. Tenant shall pay to Landlord base monthly rent \
                during the initial Lease Term according to the following schedule:
                November 1, 2026 - October 31, 2027    $4,200.00
                November 1, 2027 - October 31, 2028    $4,368.00
                November 1, 2028 - October 31, 2029    $4,542.72
                November 1, 2029 - October 31, 2030    $4,724.43
                November 1, 2030 - October 31, 2031    $4,913.41
                """, out.text());
    }

    // ---- Fixtures ------------------------------------------------------------

    private static TokenResolver.LeaseFacts facts(List<TenancyChargeTerm> schedule) {
        return new TokenResolver.LeaseFacts(
                instrument(), schedule.get(0), schedule, tenancy(), lot(), property(),
                List.of("Ada Lovelace", "Grace Hopper"));
    }

    private static List<TenancyChargeTerm> scheduleOfFive() {
        return List.of(
                term(LocalDate.of(2026, 11, 1), "4200.00", FeeMethod.FLAT, "65.00"),
                term(LocalDate.of(2027, 11, 1), "4368.00", FeeMethod.FLAT, "65.00"),
                term(LocalDate.of(2028, 11, 1), "4542.72", FeeMethod.FLAT, "65.00"),
                term(LocalDate.of(2029, 11, 1), "4724.43", FeeMethod.FLAT, "65.00"),
                term(LocalDate.of(2030, 11, 1), "4913.41", FeeMethod.FLAT, "65.00"));
    }

    private static Instrument instrument() {
        OffsetDateTime now = OffsetDateTime.of(2026, 10, 15, 9, 0, 0, 0, ZoneOffset.UTC);
        return new Instrument(
                UUID.randomUUID(), TENANCY, InstrumentType.LEASE, InstrumentStatus.DRAFT,
                "LSE-000123", null,
                LocalDate.of(2026, 11, 1), 60, OnExpiry.MONTH_TO_MONTH,
                UUID.randomUUID(), 3, UUID.randomUUID(),
                now, UUID.randomUUID(),
                null, null,
                null, null, null, null,
                null, null,
                null, now, UUID.randomUUID());
    }

    private static Tenancy tenancy() {
        return new Tenancy(TENANCY, LOT, LocalDate.of(2026, 11, 1), null,
                false, false, true, false,
                OffsetDateTime.now(ZoneOffset.UTC), null);
    }

    private static Lot lot() {
        return new Lot(LOT, PROPERTY, true, null, "42", "42 Dockside Way", "P-9981",
                null, null, 1, null, OffsetDateTime.now(ZoneOffset.UTC), null, List.of());
    }

    private static Property property() {
        return new Property(PROPERTY, "HV", "Harbor View", "100 Marina Drive",
                "Port Orchard", "WA", "98366", LocalDate.of(2019, 5, 1),
                "MHP-2", "P-1000", "Harbor View LLC", "PO Box 12, Port Orchard WA 98366",
                1978, OffsetDateTime.now(ZoneOffset.UTC), null);
    }

    private static TenancyChargeTerm term(LocalDate validAt, String rate,
                                          FeeMethod lateFeeMethod, String lateFeeAmount) {
        return chargeTerm(validAt, rate, lateFeeMethod, lateFeeAmount,
                SecurityDepositMethod.NONE, "0");
    }

    private static TenancyChargeTerm depositTerm(SecurityDepositMethod method, String amount) {
        return chargeTerm(START, "4200.00", FeeMethod.FLAT, "65.00", method, amount);
    }

    /** The one place the 40-component constructor is written out. */
    private static TenancyChargeTerm chargeTerm(LocalDate validAt,
                                                String rate,
                                                FeeMethod lateFeeMethod,
                                                String lateFeeAmount,
                                                SecurityDepositMethod depositMethod,
                                                String depositAmount) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new TenancyChargeTerm(
                UUID.randomUUID(), TENANCY, validAt, AgreementType.COMMERCIAL,
                new BigDecimal(rate),
                new BigDecimal("45.00"), 2, 4,
                new BigDecimal("45.00"), 2,
                1, 5,
                FeeMethod.NONE, BigDecimal.ZERO,                       // rule violation
                FeeMethod.FLAT, new BigDecimal("35.00"),               // nsf
                lateFeeMethod, new BigDecimal(lateFeeAmount),
                UtilityMethod.FLAT, new BigDecimal("30.00"),           // water
                UtilityMethod.NONE, BigDecimal.ZERO,                   // power
                UtilityMethod.RUBS, BigDecimal.ZERO,                   // sewer
                UtilityMethod.NONE, BigDecimal.ZERO,                   // trash
                depositMethod, new BigDecimal(depositAmount),
                TenancyTermStatus.PROPOSED, TenancyTermSource.LEASE,
                null, UUID.randomUUID(), UUID.randomUUID(),
                null, null, null,                                      // cancel columns
                null,                                                  // deletedAt
                null, now, UUID.randomUUID());
    }
}