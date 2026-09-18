package io.github.lordship.instruments;

import io.github.lordship.globalsettings.GlobalSettings;
import io.github.lordship.lots.Lot;
import io.github.lordship.properties.Property;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.FeeMethod;
import io.github.lordship.shared.InstrumentType;
import io.github.lordship.shared.SecurityDepositMethod;
import io.github.lordship.shared.UtilityMethod;
import io.github.lordship.tenancy.Tenancy;
import io.github.lordship.tenancyterms.RentHistoryYear;
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
    private static final OffsetDateTime SETTINGS_UPDATED =
            OffsetDateTime.of(2026, 1, 2, 8, 0, 0, 0, ZoneOffset.UTC);

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
        assertEquals("Vine Villa", values.scalar("property.city"));
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
                instrument(), schedule.get(0), schedule, tenancy(), lot(), property(), settings(),
                List.of("Ada Lovelace", "Grace Hopper"), history());
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
                "Vine Villa", "WA", "94370", LocalDate.of(2019, 5, 1),
                "MHP-2", "P-1000", "Harbor View LLC", "PO Box 12, Vine Villa WA 94370",
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

    // ---- landlord.* ----------------------------------------------------------

    @Test
    void resolve_shouldNameTheParksOwnEntityAsTheLandlord() {
        // Arrange -- each park is held by its own LLC
        TokenResolver.LeaseFacts facts = facts(singleStep());

        // Act
        TokenValues values = TokenResolver.resolve(facts);

        // Assert -- the lease names the entity that owns the ground, not the
        // parent company
        assertEquals("Harbor View LLC", values.scalar("landlord.name"));
        assertEquals("PO Box 12, Vine Villa WA 94370", values.scalar("landlord.address"));
    }

    @Test
    void resolve_shouldGiveLandlordAndPayableToTheSameValue() {
        // Arrange -- two names for one fact today, so that the day a park takes
        // cheques at a lockbox only one of them has to move
        TokenValues values = TokenResolver.resolve(facts(singleStep()));

        // Assert
        assertEquals(values.scalar("property.payable_to"), values.scalar("landlord.name"));
        assertEquals(values.scalar("property.remittance_address"), values.scalar("landlord.address"));
    }

    @Test
    void resolve_shouldTakeTheComplianceAddressFromTheCompany() {
        // Arrange -- the one genuinely company-wide fact on the page
        TokenValues values = TokenResolver.resolve(facts(singleStep()));

        // Assert
        assertEquals("compliance@hurstandson.com", values.scalar("landlord.compliance_email"));
    }

    @Test
    void resolve_shouldLeaveTheComplianceAddressUnset_whenNobodyHasEnteredOne() {
        // Arrange -- the settings row exists but the field is blank, which is
        // how a fresh install arrives
        TokenResolver.LeaseFacts facts = withSettings(
                new GlobalSettings(UUID.randomUUID(), null, SETTINGS_UPDATED));

        // Act
        TokenValues values = TokenResolver.resolve(facts);

        // Assert -- not set rather than blank, so a clause asking for it lands
        // in unresolved and generation refuses instead of printing "emailing ."
        assertNull(values.scalar("landlord.compliance_email"));
    }

    @Test
    void resolve_shouldLeaveTheLandlordUnset_whenThePropertyNeverNamedOne() {
        // Arrange -- a park set up in a hurry
        TokenResolver.LeaseFacts facts = withProperty(propertyWithNoEntity());

        // Act
        TokenValues values = TokenResolver.resolve(facts);

        // Assert -- a lease that cannot say who the landlord is must not print
        assertNull(values.scalar("landlord.name"));
        assertNull(values.scalar("landlord.address"));
    }

    /** An ordinary lease: one rate, no escalation. */
    private static List<TenancyChargeTerm> singleStep() {
        return List.of(term(START, "4200.00", FeeMethod.FLAT, "65.00"));
    }

    private static GlobalSettings settings() {
        return new GlobalSettings(UUID.randomUUID(), "compliance@hurstandson.com", SETTINGS_UPDATED);
    }

    private static TokenResolver.LeaseFacts withSettings(GlobalSettings settings) {
        TokenResolver.LeaseFacts base = facts(singleStep());
        return new TokenResolver.LeaseFacts(base.instrument(), base.term(), base.schedule(),
                base.tenancy(), base.lot(), base.property(), settings, base.tenantNames(),
                base.rentHistory());
    }

    private static TokenResolver.LeaseFacts withProperty(Property property) {
        TokenResolver.LeaseFacts base = facts(singleStep());
        return new TokenResolver.LeaseFacts(base.instrument(), base.term(), base.schedule(),
                base.tenancy(), base.lot(), property, base.settings(), base.tenantNames(),
                base.rentHistory());
    }

    private static Property propertyWithNoEntity() {
        Property p = property();
        return new Property(p.uuid(), p.propertyCode(), p.propertyName(), p.propertyAddress(),
                p.propertyCity(), p.propertyState(), p.propertyZip(), p.purchaseDate(),
                p.propertyZoning(), p.propertyParcel(), null, null, p.yearBuilt(),
                p.createdAt(), p.deletedAt());
    }

    // ---- lot.rent_history_* --------------------------------------------------

    @Test
    void resolve_shouldDiscloseTheFiveYearsBeforeTheLeaseBegins() {
        // Arrange -- the term starts 1 November 2026
        TokenValues values = TokenResolver.resolve(facts(singleStep()));

        // Assert -- 2026 is the rate the lease states above, not history
        assertEquals("2021", values.scalar("lot.rent_history_year_1"));
        assertEquals("2022", values.scalar("lot.rent_history_year_2"));
        assertEquals("2023", values.scalar("lot.rent_history_year_3"));
        assertEquals("2024", values.scalar("lot.rent_history_year_4"));
        assertEquals("2025", values.scalar("lot.rent_history_year_5"));
    }

    @Test
    void resolve_shouldPrintTheHighestRateChargedThatYear() {
        // Arrange
        TokenValues values = TokenResolver.resolve(facts(singleStep()));

        // Assert
        assertEquals("$3,600.00", values.scalar("lot.rent_history_rate_1"));
        assertEquals("$3,900.00", values.scalar("lot.rent_history_rate_3"));
    }

    @Test
    void resolve_shouldPrintUnknown_forAYearNobodyCanAnswer() {
        // Arrange -- a park bought two years ago cannot know what its lots
        // charged five years ago
        TokenResolver.LeaseFacts facts = withHistory(List.of(
                new RentHistoryYear(2024, new BigDecimal("4000.00")),
                new RentHistoryYear(2025, new BigDecimal("4100.00"))));

        // Act
        TokenValues values = TokenResolver.resolve(facts);

        // Assert -- the year still prints; a blank would read as nothing
        // charged and a zero would read as free
        assertEquals("2021", values.scalar("lot.rent_history_year_1"));
        assertEquals("Unknown", values.scalar("lot.rent_history_rate_1"));
        assertEquals("$4,100.00", values.scalar("lot.rent_history_rate_5"));
    }

    @Test
    void resolve_shouldStillDiscloseEveryYear_whenNothingIsKnownAtAll() {
        // Arrange -- a park bought last month
        TokenValues values = TokenResolver.resolve(withHistory(List.of()));

        // Assert -- five rows, every rate Unknown, and generation is not
        // blocked: saying so on the page IS the disclosure
        assertEquals("2021", values.scalar("lot.rent_history_year_1"));
        assertEquals("2025", values.scalar("lot.rent_history_year_5"));
        for (int i = 1; i <= 5; i++) {
            assertEquals("Unknown", values.scalar("lot.rent_history_rate_" + i));
        }
    }

    @Test
    void resolve_shouldDiscloseNothing_onPaperWithNoTermOfItsOwn() {
        // Arrange -- a notice does not expire and has nothing to disclose from
        TokenResolver.LeaseFacts facts = withInstrument(noticeWithNoTerm());

        // Act
        TokenValues values = TokenResolver.resolve(facts);

        // Assert
        assertNull(values.scalar("lot.rent_history_year_1"));
        assertNull(values.scalar("lot.rent_history_rate_1"));
    }

    private static List<RentHistoryYear> history() {
        return List.of(
                new RentHistoryYear(2021, new BigDecimal("3600.00")),
                new RentHistoryYear(2022, new BigDecimal("3750.00")),
                new RentHistoryYear(2023, new BigDecimal("3900.00")),
                new RentHistoryYear(2024, new BigDecimal("4050.00")),
                new RentHistoryYear(2025, new BigDecimal("4200.00")));
    }

    private static TokenResolver.LeaseFacts withHistory(List<RentHistoryYear> rentHistory) {
        TokenResolver.LeaseFacts base = facts(singleStep());
        return new TokenResolver.LeaseFacts(base.instrument(), base.term(), base.schedule(),
                base.tenancy(), base.lot(), base.property(), base.settings(),
                base.tenantNames(), rentHistory);
    }

    private static TokenResolver.LeaseFacts withInstrument(Instrument instrument) {
        TokenResolver.LeaseFacts base = facts(singleStep());
        return new TokenResolver.LeaseFacts(instrument, base.term(), base.schedule(),
                base.tenancy(), base.lot(), base.property(), base.settings(),
                base.tenantNames(), base.rentHistory());
    }

    private static Instrument noticeWithNoTerm() {
        Instrument i = instrument();
        return new Instrument(i.uuid(), i.tenancy(), InstrumentType.INCREASE_NOTICE, i.status(),
                i.serial(), i.amends(), null, null, null,
                i.template(), i.templateVersion(), i.documentAssignment(),
                i.generatedAt(), i.generatedFile(), i.sentAt(), i.sentBy(),
                i.servedOn(), i.serviceMethod(), i.servedBy(), i.proofFile(),
                i.returnedOn(), i.returnedFile(), i.note(), i.createdAt(), i.createdBy());
    }
}