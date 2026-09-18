package io.github.lordship.instruments;

import io.github.lordship.globalsettings.GlobalSettings;
import io.github.lordship.lots.Lot;
import io.github.lordship.properties.Property;
import io.github.lordship.shared.DocumentToken;
import io.github.lordship.shared.UtilityMethod;
import io.github.lordship.tenancy.Tenancy;
import io.github.lordship.tenancyterms.RentHistoryYear;
import io.github.lordship.tenancyterms.TenancyChargeTerm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gathers everything one document has to say, formatted and keyed by token name.
 *
 * <p>Pure on purpose: it takes the rows as arguments rather than services, so
 * the question "does a lease for this deal say the right things" can be asked
 * without a database. Fetching those rows is a service's job; deciding what
 * they mean on paper is this one's, and the two fail for different reasons.
 *
 * <p>An amount whose method does not carry one is deliberately NOT set. The
 * rule is {@code DocumentToken.populatedWhen}, the same rule the clause editor
 * warns about and the same rule the CHECK constraints enforce -- so a tenant on
 * 1.5% of rent never sees "$1.50" because the value simply is not there to
 * print. A clause that asks for it anyway lands in
 * {@link BodyRenderer.Rendered#unresolved()} and generation refuses.
 */
public final class TokenResolver {

    private TokenResolver() {}

    /**
     * Everything the resolver reads. Assembled by the service that owns the
     * fetching; every field is required except the schedule, which is one entry
     * long for an ordinary lease.
     *
     * @param term     the step in force when the document's term begins -- what
     *                 {@code term.rate} means on a lease the tenant is signing
     * @param schedule every step this instrument produced, earliest first
     * @param settings the company-wide row -- one field of it prints, and the
     *                 rest of the landlord's identity comes off the property
     */
    public record LeaseFacts(
            Instrument instrument,
            TenancyChargeTerm term,
            List<TenancyChargeTerm> schedule,
            Tenancy tenancy,
            Lot lot,
            Property property,
            GlobalSettings settings,
            List<String> tenantNames,
            List<RentHistoryYear> rentHistory
    ) {
        public LeaseFacts {
            schedule = List.copyOf(schedule);
            tenantNames = List.copyOf(tenantNames);
            rentHistory = List.copyOf(rentHistory);
        }
    }

    public static TokenValues resolve(LeaseFacts facts) {
        TokenValues.Builder out = TokenValues.builder();

        putTerm(out, facts);
        putInstrument(out, facts.instrument());
        putLot(out, facts.lot());
        putProperty(out, facts.property());
        putRentHistory(out, facts);
        putLandlord(out, facts);
        putTenancy(out, facts);
        putRentSchedule(out, facts);

        return out.build();
    }

    // ---- term.* --------------------------------------------------------------

    private static void putTerm(TokenValues.Builder out, LeaseFacts facts) {
        TenancyChargeTerm term = facts.term();

        put(out, DocumentToken.RATE, term.rate());
        put(out, DocumentToken.RATE_IN_WORDS, term.rate());

        put(out, DocumentToken.CAR_FEE, term.carFee());
        put(out, DocumentToken.CAR_FEE_IN_WORDS, term.carFee());
        put(out, DocumentToken.ALLOWED_CARS, term.allowedCars());
        put(out, DocumentToken.ALLOWED_CARS_IN_WORDS, term.allowedCars());
        put(out, DocumentToken.CARS_MAX, term.carsMax());

        put(out, DocumentToken.PET_FEE, term.petFee());
        put(out, DocumentToken.ALLOWED_PETS, term.allowedPets());
        put(out, DocumentToken.ALLOWED_PETS_IN_WORDS, term.allowedPets());

        put(out, DocumentToken.PAYMENT_DUE_DAY, term.paymentDueDay());
        put(out, DocumentToken.GRACE_PERIOD_DAYS, term.gracePeriodDays());
        put(out, DocumentToken.GRACE_PERIOD_DAYS_IN_WORDS, term.gracePeriodDays());
        put(out, DocumentToken.LATE_AFTER_DAY, term.paymentDueDay() + term.gracePeriodDays());

        put(out, DocumentToken.AGREEMENT_TYPE, term.agreementType());
        put(out, DocumentToken.VALID_AT, term.validAt());

        // Methods are what a clause branches on, so they always print.
        put(out, DocumentToken.LATE_FEE_METHOD, term.lateFeeMethod());
        put(out, DocumentToken.NSF_FEE_METHOD, term.nsfFeeMethod());
        put(out, DocumentToken.RULE_VIOLATION_FEE_METHOD, term.ruleViolationFeeMethod());
        put(out, DocumentToken.WATER_METHOD, term.waterMethod());
        put(out, DocumentToken.POWER_METHOD, term.powerMethod());
        put(out, DocumentToken.SEWER_METHOD, term.sewerMethod());
        put(out, DocumentToken.TRASH_METHOD, term.trashMethod());
        put(out, DocumentToken.SECURITY_DEPOSIT_METHOD, term.securityDepositMethod());

        // Amounts print only under the methods that carry one. late_fee_amount
        // and late_fee_percent are the same column; which of them is real is
        // decided here rather than by the clause author remembering.
        putGoverned(out, DocumentToken.LATE_FEE_AMOUNT, term.lateFeeMethod(), term.lateFeeAmount());
        putGoverned(out, DocumentToken.LATE_FEE_AMOUNT_IN_WORDS, term.lateFeeMethod(), term.lateFeeAmount());
        putGoverned(out, DocumentToken.LATE_FEE_PERCENT, term.lateFeeMethod(), term.lateFeeAmount());
        putGoverned(out, DocumentToken.LATE_FEE_PERCENT_IN_WORDS, term.lateFeeMethod(), term.lateFeeAmount());

        putGoverned(out, DocumentToken.NSF_FEE_AMOUNT, term.nsfFeeMethod(), term.nsfFeeAmount());
        putGoverned(out, DocumentToken.NSF_FEE_AMOUNT_IN_WORDS, term.nsfFeeMethod(), term.nsfFeeAmount());
        putGoverned(out, DocumentToken.RULE_VIOLATION_FEE_AMOUNT,
                term.ruleViolationFeeMethod(), term.ruleViolationFeeAmount());

        putGoverned(out, DocumentToken.WATER_FLAT_AMOUNT, term.waterMethod(), term.waterFlatAmount());
        putGoverned(out, DocumentToken.POWER_FLAT_AMOUNT, term.powerMethod(), term.powerFlatAmount());
        putGoverned(out, DocumentToken.SEWER_FLAT_AMOUNT, term.sewerMethod(), term.sewerFlatAmount());
        putGoverned(out, DocumentToken.TRASH_FLAT_AMOUNT, term.trashMethod(), term.trashFlatAmount());

        // The deposit in dollars, whichever way it was agreed. The raw column is
        // never printed: under MULTIPLE_OF_RENT it holds 1.00, which as dollars
        // would state a one dollar deposit.
        BigDecimal deposit = depositInDollars(term);
        if (deposit != null) {
            put(out, DocumentToken.SECURITY_DEPOSIT, deposit);
            put(out, DocumentToken.SECURITY_DEPOSIT_IN_WORDS, deposit);
        }

        put(out, DocumentToken.CHARGED_UTILITIES, chargedUtilities(term));
    }

    /** Null when no deposit is taken, so a clause asking for one reports it rather than printing $0.00. */
    private static BigDecimal depositInDollars(TenancyChargeTerm term) {
        return switch (term.securityDepositMethod()) {
            case FLAT -> term.securityDepositAmount();
            case MULTIPLE_OF_RENT ->
                    term.rate().multiply(term.securityDepositAmount()).setScale(2, RoundingMode.HALF_UP);
            case NONE -> null;
        };
    }

    /** Derived from the four method columns rather than read from one -- what this tenant actually pays for. */
    private static List<String> chargedUtilities(TenancyChargeTerm term) {
        List<String> charged = new ArrayList<>();
        if (isCharged(term.waterMethod())) charged.add("water");
        if (isCharged(term.powerMethod())) charged.add("power");
        if (isCharged(term.sewerMethod())) charged.add("sewer");
        if (isCharged(term.trashMethod())) charged.add("trash");
        return charged;
    }

    private static boolean isCharged(UtilityMethod method) {
        return method != null && method != UtilityMethod.NONE && method != UtilityMethod.INCLUDED;
    }

    // ---- instrument.* --------------------------------------------------------

    private static void putInstrument(TokenValues.Builder out, Instrument instrument) {
        put(out, DocumentToken.SERIAL, instrument.serial());
        put(out, DocumentToken.TERM_START, instrument.termStart());
        put(out, DocumentToken.TERM_MONTHS, instrument.termMonths());
        put(out, DocumentToken.TERM_MONTHS_IN_WORDS, instrument.termMonths());
        put(out, DocumentToken.ON_EXPIRY, instrument.onExpiry());

        // The last day covered, not the day after it: a lease that runs 60
        // months from November 1 2026 ends October 31 2031, and printing
        // November 1 would give the tenant a free day.
        instrument.lastCoveredDay().ifPresent(day -> put(out, DocumentToken.TERM_END, day));
        anniversary(instrument).ifPresent(day -> put(out, DocumentToken.ANNIVERSARY, TokenFormatter.monthAndDay(day)));

        if (instrument.generatedAt() != null) {
            LocalDate generatedOn = instrument.generatedAt().toLocalDate();
            put(out, DocumentToken.GENERATED_ON, generatedOn);
            put(out, DocumentToken.EXECUTION_YEAR, generatedOn.getYear());
        }
    }

    /**
     * The WA lease anniversary. A 12-month term's is the day it starts; any
     * other length's is the first of the month after its last covered day.
     */
    static java.util.Optional<LocalDate> anniversary(Instrument instrument) {
        if (instrument.termStart() == null || instrument.termMonths() == null) {
            return java.util.Optional.empty();
        }
        if (instrument.termMonths() == 12) {
            return java.util.Optional.of(instrument.termStart());
        }
        return instrument.lastCoveredDay().map(last -> last.plusMonths(1).withDayOfMonth(1));
    }

    // ---- lot.* and property.* ------------------------------------------------

    private static void putLot(TokenValues.Builder out, Lot lot) {
        put(out, DocumentToken.LOT_NUMBER, lot.lotNumber());
        put(out, DocumentToken.LOT_ADDRESS, lot.lotAddress());
        put(out, DocumentToken.LOT_PARCEL, lot.lotParcel());
    }

    private static void putProperty(TokenValues.Builder out, Property property) {
        put(out, DocumentToken.COMMUNITY_NAME, property.propertyName());
        put(out, DocumentToken.PROPERTY_CODE, property.propertyCode());
        put(out, DocumentToken.PROPERTY_ADDRESS, property.propertyAddress());
        put(out, DocumentToken.PROPERTY_CITY, property.propertyCity());
        put(out, DocumentToken.PROPERTY_STATE, property.propertyState());
        put(out, DocumentToken.PROPERTY_ZIP, property.propertyZip());
        put(out, DocumentToken.PROPERTY_ZONING, property.propertyZoning());
        put(out, DocumentToken.PAYABLE_TO, property.payableTo());
        put(out, DocumentToken.REMITTANCE_ADDRESS, property.remittanceAddress());
    }

    // ---- lot.rent_history_* --------------------------------------------------

    private static final DocumentToken[] HISTORY_YEARS = {
            DocumentToken.RENT_HISTORY_YEAR_1, DocumentToken.RENT_HISTORY_YEAR_2,
            DocumentToken.RENT_HISTORY_YEAR_3, DocumentToken.RENT_HISTORY_YEAR_4,
            DocumentToken.RENT_HISTORY_YEAR_5
    };

    private static final DocumentToken[] HISTORY_RATES = {
            DocumentToken.RENT_HISTORY_RATE_1, DocumentToken.RENT_HISTORY_RATE_2,
            DocumentToken.RENT_HISTORY_RATE_3, DocumentToken.RENT_HISTORY_RATE_4,
            DocumentToken.RENT_HISTORY_RATE_5
    };

    /**
     * The RCW 59.20 disclosure: five years of what this lot charged.
     *
     * <p>Both halves of every row always print. The year is a legal
     * requirement rather than a function of how long anyone has lived here, so
     * a year nobody can answer prints the year and the literal "Unknown" beside
     * it. A blank would read as nothing charged and a zero would read as free.
     *
     * <p>Unresolved is the wrong tool here. Everywhere else an unanswerable
     * token stops the document, because a lease that omits its rent is worse
     * than no lease. This is the exception: a park bought two years ago cannot
     * know what its lots charged five years ago, and saying so on the page is
     * the honest disclosure rather than a reason to refuse.
     */
    private static void putRentHistory(TokenValues.Builder out, LeaseFacts facts) {
        List<Integer> years = RentHistory.disclosedYears(facts.instrument().termStart());
        if (years.isEmpty()) {
            return; // paper with no term of its own discloses nothing
        }

        Map<Integer, BigDecimal> charged = new LinkedHashMap<>();
        for (RentHistoryYear row : facts.rentHistory()) {
            charged.put(row.year(), row.highestRate());
        }

        for (int i = 0; i < years.size(); i++) {
            int year = years.get(i);
            put(out, HISTORY_YEARS[i], year);

            BigDecimal rate = charged.get(year);
            if (rate == null) {
                out.put(HISTORY_RATES[i].token(), RentHistory.UNKNOWN);
            } else {
                put(out, HISTORY_RATES[i], rate);
            }
        }
    }

    // ---- landlord.* ----------------------------------------------------------

    /**
     * Who the lease says the landlord is.
     * <p>The compliance address is the one genuinely company-wide fact on the
     * page, which is the whole reason global_settings has a package.
     */
    private static void putLandlord(TokenValues.Builder out, LeaseFacts facts) {
        put(out, DocumentToken.LANDLORD_NAME, facts.property().payableTo());
        put(out, DocumentToken.LANDLORD_ADDRESS, facts.property().remittanceAddress());
        put(out, DocumentToken.COMPLIANCE_EMAIL, facts.settings().complianceEmail());
    }

    // ---- tenancy.* -----------------------------------------------------------

    private static void putTenancy(TokenValues.Builder out, LeaseFacts facts) {
        put(out, DocumentToken.TENANT_NAMES, facts.tenantNames());
        put(out, DocumentToken.OCCUPANCY_DATE, facts.tenancy().startDate());

        List<Map<String, String>> signers = new ArrayList<>();
        for (String name : facts.tenantNames()) {
            signers.add(Map.of(DocumentToken.SIGNER_NAME.token(), name));
        }
        out.putList(DocumentToken.SIGNERS.token(), signers);
    }

    // ---- the rent schedule ---------------------------------------------------

    /**
     * The schedule as rows a clause repeats over, plus the ENUM a clause branches
     * on so that an ordinary lease prints its one-rate sentence and a stepped
     * lease prints the table.
     *
     * <p>Each row's period runs to the day before the next step begins; the last
     * row runs to the last day the document covers, which is why the schedule
     * cannot be built from the charge terms alone.
     */
    private static void putRentSchedule(TokenValues.Builder out, LeaseFacts facts) {
        List<TenancyChargeTerm> steps = facts.schedule();

        out.put(RENT_SCHEDULE_TYPE, steps.size() > 1 ? "SCHEDULED" : "SINGLE");

        LocalDate lastDay = facts.instrument().lastCoveredDay().orElse(null);
        List<Map<String, String>> rows = new ArrayList<>();

        for (int i = 0; i < steps.size(); i++) {
            TenancyChargeTerm step = steps.get(i);
            LocalDate starts = step.validAt();
            LocalDate ends = (i + 1 < steps.size())
                    ? steps.get(i + 1).validAt().minusDays(1)
                    : lastDay;

            Map<String, String> row = new LinkedHashMap<>();
            row.put(STEP_STARTS_ON, TokenFormatter.date(starts));
            row.put(STEP_RATE, TokenFormatter.money(step.rate()));
            row.put(STEP_RATE_IN_WORDS, TokenFormatter.moneyInWords(step.rate()));
            if (ends != null) {
                row.put(STEP_ENDS_ON, TokenFormatter.date(ends));
                row.put(STEP_PERIOD, TokenFormatter.date(starts) + " - " + TokenFormatter.date(ends));
            }
            rows.add(row);
        }

        out.putList(RENT_SCHEDULE, rows);
    }

    // Written as strings rather than DocumentToken constants because those
    // constants do not exist yet. Adding them is what lets a clause author use
    // these at all -- until then the renderer will substitute them, but the
    // clause editor will reject the body as containing unknown tokens.
    public static final String RENT_SCHEDULE = "term.rent_schedule";
    public static final String RENT_SCHEDULE_TYPE = "term.rent_schedule_type";
    public static final String STEP_PERIOD = "rent_step.period";
    public static final String STEP_STARTS_ON = "rent_step.starts_on";
    public static final String STEP_ENDS_ON = "rent_step.ends_on";
    public static final String STEP_RATE = "rent_step.rate";
    public static final String STEP_RATE_IN_WORDS = "rent_step.rate_in_words";

    // ---- internals -----------------------------------------------------------

    /** Formats per the token's own declared Format. A null value is simply not set. */
    private static void put(TokenValues.Builder out, DocumentToken token, Object value) {
        String formatted = TokenFormatter.format(token, value);
        if (formatted != null) {
            out.put(token.token(), formatted);
        }
    }

    /**
     * Sets an amount only under the methods that actually carry one.
     *
     * <p>{@code populatedWhen} is the single statement of that rule; reading it
     * here rather than restating it is what keeps this from becoming the
     * eleventh place the same fact is written down.
     */
    private static void putGoverned(TokenValues.Builder out,
                                    DocumentToken token,
                                    Enum<?> method,
                                    Object value) {
        if (method != null && token.populatedWhen().contains(method.name())) {
            put(out, token, value);
        }
    }
}