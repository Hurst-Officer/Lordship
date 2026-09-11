package io.github.lordship.shared;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every {@code {{token}}} a document clause may contain, and where each one gets
 * its value. This is the seam between the deal and the paper: a clause body
 * never carries a literal amount, so a lease cannot state a late fee the charge
 * term does not.
 *
 * <p>Deliberately an enum rather than a table. A token only means something
 * because there is code that resolves it, so a row someone added to a
 * {@code document_token} table would be a token the renderer could not print --
 * a table that looks editable and is not. Adding a token is a code change by
 * definition, and this is vocabulary three modules speak: {@code documenttemplate}
 * validating clause bodies at save, {@code instruments} substituting at generate,
 * and the clause editor's token picker.
 *
 * <p>Names are namespaced -- {@code term.rate}, {@code lot.lot_number},
 * {@code landlord.address} -- so a clause author can see where a value comes
 * from while reading the body. The namespace is what the author sees;
 * {@link Source} is how the resolver actually gets it, and the two do not
 * always agree: {@code term.charged_utilities} sits in the term namespace but
 * is derived from four method columns rather than read from one.
 *
 * <p>These strings are a wire format. They live in {@code template_clause.body}
 * and in {@code instrument_clause.body_template} inside served legal documents,
 * so a constant may be renamed freely and a string may never be.
 *
 * <p>{@link Format#ENUM} is what a clause may branch on. A clause whose wording
 * depends on a method is authored once per method and selected by
 * {@code template_clause.condition_field}; conditioning on a money token is
 * refused at save.
 */
public enum DocumentToken {

    // ---- term.* : the deal (tenancy_charge_term) -----------------------------

    RATE("term.rate", Source.CHARGE_TERM, Format.MONEY,
            "Monthly rate for this agreement type"),
    RATE_IN_WORDS("term.rate_in_words", Source.CHARGE_TERM, Format.MONEY_WORDS,
            "Monthly rate spelled out"),

    CAR_FEE("term.car_fee", Source.CHARGE_TERM, Format.MONEY,
            "Monthly charge per vehicle beyond the allowance"),
    CAR_FEE_IN_WORDS("term.car_fee_in_words", Source.CHARGE_TERM, Format.MONEY_WORDS,
            "Vehicle charge spelled out"),
    ALLOWED_CARS("term.allowed_cars", Source.CHARGE_TERM, Format.INTEGER,
            "Vehicles included at the base rate"),
    ALLOWED_CARS_IN_WORDS("term.allowed_cars_in_words", Source.CHARGE_TERM, Format.INTEGER_WORDS,
            "Vehicles included at the base rate, spelled out"),
    CARS_MAX("term.cars_max", Source.CHARGE_TERM, Format.INTEGER,
            "Hard ceiling on vehicles per lot"),

    PET_FEE("term.pet_fee", Source.CHARGE_TERM, Format.MONEY,
            "Monthly charge per pet beyond the allowance"),
    ALLOWED_PETS("term.allowed_pets", Source.CHARGE_TERM, Format.INTEGER,
            "Pets included at the base rate"),
    ALLOWED_PETS_IN_WORDS("term.allowed_pets_in_words", Source.CHARGE_TERM, Format.INTEGER_WORDS,
            "Pets included at the base rate, spelled out"),

    PAYMENT_DUE_DAY("term.payment_due_day", Source.CHARGE_TERM, Format.ORDINAL,
            "Day of the month rent is due, as 1st / 5th"),
    GRACE_PERIOD_DAYS("term.grace_period_days", Source.CHARGE_TERM, Format.INTEGER,
            "Days after the due date before a late fee attaches"),
    GRACE_PERIOD_DAYS_IN_WORDS("term.grace_period_days_in_words", Source.CHARGE_TERM, Format.INTEGER_WORDS,
            "Grace period spelled out"),
    LATE_AFTER_DAY("term.late_after_day", Source.COMPUTED, Format.ORDINAL,
            "First day rent is late: the due day plus the grace period"),

    // Method tokens carry Format.ENUM: these are the fields a clause may branch on.
    LATE_FEE_METHOD("term.late_fee_method", Source.CHARGE_TERM, Format.ENUM,
            "NONE / FLAT / PERCENT_OF_RENT -- selects which late fee clause prints",
            Set.of("NONE", "FLAT", "PERCENT_OF_RENT")),
    LATE_FEE_AMOUNT("term.late_fee_amount", Source.CHARGE_TERM, Format.MONEY,
            "Late fee, when the method is FLAT",
            "term.late_fee_method", Set.of("FLAT")),
    LATE_FEE_AMOUNT_IN_WORDS("term.late_fee_amount_in_words", Source.CHARGE_TERM, Format.MONEY_WORDS,
            "Late fee spelled out",
            "term.late_fee_method", Set.of("FLAT")),
    LATE_FEE_PERCENT("term.late_fee_percent", Source.CHARGE_TERM, Format.PERCENT,
            "Late fee, when the method is PERCENT_OF_RENT -- same column, printed as a percentage",
            "term.late_fee_method", Set.of("PERCENT_OF_RENT")),
    LATE_FEE_PERCENT_IN_WORDS("term.late_fee_percent_in_words", Source.CHARGE_TERM, Format.PERCENT_WORDS,
            "Late fee percentage spelled out, e.g. one and one half percent",
            "term.late_fee_method", Set.of("PERCENT_OF_RENT")),

    NSF_FEE_METHOD("term.nsf_fee_method", Source.CHARGE_TERM, Format.ENUM,
            "NONE / FLAT / BANK_OR_FLAT -- selects which returned-payment clause prints",
            Set.of("NONE", "FLAT", "BANK_OR_FLAT")),
    NSF_FEE_AMOUNT("term.nsf_fee_amount", Source.CHARGE_TERM, Format.MONEY,
            "Returned payment fee",
            "term.nsf_fee_method", Set.of("FLAT", "BANK_OR_FLAT")),
    NSF_FEE_AMOUNT_IN_WORDS("term.nsf_fee_amount_in_words", Source.CHARGE_TERM, Format.MONEY_WORDS,
            "Returned payment fee spelled out",
            "term.nsf_fee_method", Set.of("FLAT", "BANK_OR_FLAT")),

    RULE_VIOLATION_FEE_METHOD("term.rule_violation_fee_method", Source.CHARGE_TERM, Format.ENUM,
            "NONE / FLAT",
            Set.of("NONE", "FLAT")),
    RULE_VIOLATION_FEE_AMOUNT("term.rule_violation_fee_amount", Source.CHARGE_TERM, Format.MONEY,
            "Charge per rule violation",
            "term.rule_violation_fee_method", Set.of("FLAT")),

    WATER_METHOD("term.water_method", Source.CHARGE_TERM, Format.ENUM,
            "NONE / FLAT / RUBS / SUBMETERED -- selects the water clause",
            Set.of("NONE", "FLAT", "RUBS", "SUBMETERED")),
    WATER_FLAT_AMOUNT("term.water_flat_amount", Source.CHARGE_TERM, Format.MONEY,
            "Water charge, when the method is FLAT",
            "term.water_method", Set.of("FLAT")),
    POWER_METHOD("term.power_method", Source.CHARGE_TERM, Format.ENUM,
            "NONE / FLAT / RUBS / SUBMETERED",
            Set.of("NONE", "FLAT", "RUBS", "SUBMETERED")),
    POWER_FLAT_AMOUNT("term.power_flat_amount", Source.CHARGE_TERM, Format.MONEY,
            "Power charge, when the method is FLAT",
            "term.power_method", Set.of("FLAT")),
    SEWER_METHOD("term.sewer_method", Source.CHARGE_TERM, Format.ENUM,
            "NONE / FLAT / RUBS / SUBMETERED -- also selects the septic vs city sewer addendum",
            Set.of("NONE", "FLAT", "RUBS", "SUBMETERED")),
    SEWER_FLAT_AMOUNT("term.sewer_flat_amount", Source.CHARGE_TERM, Format.MONEY,
            "Sewer charge, when the method is FLAT",
            "term.sewer_method", Set.of("FLAT")),
    TRASH_METHOD("term.trash_method", Source.CHARGE_TERM, Format.ENUM,
            "NONE / FLAT / RUBS",
            Set.of("NONE", "FLAT", "RUBS")),
    TRASH_FLAT_AMOUNT("term.trash_flat_amount", Source.CHARGE_TERM, Format.MONEY,
            "Trash charge, when the method is FLAT",
            "term.trash_method", Set.of("FLAT")),

    SECURITY_DEPOSIT_METHOD("term.security_deposit_method", Source.CHARGE_TERM, Format.ENUM,
            "NONE / FLAT / MULTIPLE_OF_RENT -- selects which deposit clause prints",
            Set.of("NONE", "FLAT", "MULTIPLE_OF_RENT")),

    // Resolved, not read: the column holds dollars under FLAT and a multiplier
    // under MULTIPLE_OF_RENT, so printing it raw states a $1.00 deposit on a
    // one-month-rent deal. This is the only deposit figure a clause may print.
    SECURITY_DEPOSIT("term.security_deposit", Source.COMPUTED, Format.MONEY,
            "The deposit in dollars -- the flat amount, or the multiple times the rate",
            "term.security_deposit_method", Set.of("FLAT", "MULTIPLE_OF_RENT")),
    SECURITY_DEPOSIT_IN_WORDS("term.security_deposit_in_words", Source.COMPUTED, Format.MONEY_WORDS,
            "The deposit spelled out",
            "term.security_deposit_method", Set.of("FLAT", "MULTIPLE_OF_RENT")),

    CHARGED_UTILITIES("term.charged_utilities", Source.COMPUTED, Format.LIST,
            "The utilities this tenant is charged for, derived from the four methods"),

    AGREEMENT_TYPE("term.agreement_type", Source.CHARGE_TERM, Format.ENUM,
            "LAND / RESIDENTIAL / STORAGE -- selects agreement-specific wording",
            Set.of("RESIDENTIAL", "LAND", "TRANSIENT", "COMMERCIAL", "STORAGE", "UTILITY_SERVICE")),
    VALID_AT("term.valid_at", Source.CHARGE_TERM, Format.DATE,
            "Date these figures take effect"),

    // ---- instrument.* : this piece of paper ----------------------------------

    SERIAL("instrument.serial", Source.INSTRUMENT, Format.TEXT,
            "Serial printed on the document, typed back in to find it"),

    // Only paper that carries a term of its own can print one. A notice or an
    // addendum renders these blank, so the picker does not offer them.
    TERM_START("instrument.term_start", Source.INSTRUMENT, Format.DATE,
            "First day of the period this document covers",
            Set.of(), On.TERM_CARRYING),
    TERM_MONTHS("instrument.term_months", Source.INSTRUMENT, Format.INTEGER,
            "Length of the term in months; 1 is month to month",
            Set.of(), On.TERM_CARRYING),
    TERM_MONTHS_IN_WORDS("instrument.term_months_in_words", Source.INSTRUMENT, Format.INTEGER_WORDS,
            "Term length spelled out",
            Set.of(), On.TERM_CARRYING),
    TERM_END("instrument.term_end", Source.COMPUTED, Format.DATE,
            "term_start plus term_months",
            Set.of(), On.TERM_CARRYING),
    ON_EXPIRY("instrument.on_expiry", Source.INSTRUMENT, Format.ENUM,
            "MONTH_TO_MONTH / AUTO_RENEW / TERMINATE -- selects the renewal clause",
            Set.of("MONTH_TO_MONTH", "AUTO_RENEW", "TERMINATE"), On.TERM_CARRYING),
    GENERATED_ON("instrument.generated_on", Source.INSTRUMENT, Format.DATE,
            "Date this document was produced"),
    EXECUTION_YEAR("instrument.execution_year", Source.INSTRUMENT, Format.INTEGER,
            "Year this document is executed, for the dated blanks in the body"),

    // ---- lot.* : the space ---------------------------------------------------

    LOT_NUMBER("lot.lot_number", Source.LOT, Format.TEXT,
            "Lot number as the community writes it, numeric or lettered"),
    LOT_ADDRESS("lot.address", Source.LOT, Format.TEXT,
            "Street address of the lot"),
    LOT_PARCEL("lot.parcel", Source.LOT, Format.TEXT,
            "Parcel number, where the lot has its own"),

    // ---- lot.rent_history_* : the RCW 59.20 disclosure -----------------------
    // Five years, always -- the rows are a legal requirement, not a function of
    // how long anyone has lived there. Each rate is the HIGHEST charged for this
    // lot that year, across whoever occupied it, read from ACTIVE charge terms.
    // A year the company owned the park too briefly to know resolves to the
    // literal "unknown" rather than a blank or a zero.

    RENT_HISTORY_YEAR_1("lot.rent_history_year_1", Source.HISTORY, Format.INTEGER, "Oldest disclosed year"),
    RENT_HISTORY_RATE_1("lot.rent_history_rate_1", Source.HISTORY, Format.MONEY, "Highest rate that year, or unknown"),
    RENT_HISTORY_YEAR_2("lot.rent_history_year_2", Source.HISTORY, Format.INTEGER, "Second disclosed year"),
    RENT_HISTORY_RATE_2("lot.rent_history_rate_2", Source.HISTORY, Format.MONEY, "Highest rate that year, or unknown"),
    RENT_HISTORY_YEAR_3("lot.rent_history_year_3", Source.HISTORY, Format.INTEGER, "Third disclosed year"),
    RENT_HISTORY_RATE_3("lot.rent_history_rate_3", Source.HISTORY, Format.MONEY, "Highest rate that year, or unknown"),
    RENT_HISTORY_YEAR_4("lot.rent_history_year_4", Source.HISTORY, Format.INTEGER, "Fourth disclosed year"),
    RENT_HISTORY_RATE_4("lot.rent_history_rate_4", Source.HISTORY, Format.MONEY, "Highest rate that year, or unknown"),
    RENT_HISTORY_YEAR_5("lot.rent_history_year_5", Source.HISTORY, Format.INTEGER, "Most recent disclosed year"),
    RENT_HISTORY_RATE_5("lot.rent_history_rate_5", Source.HISTORY, Format.MONEY, "Highest rate that year, or unknown"),

    // ---- property.* : the park -----------------------------------------------

    COMMUNITY_NAME("property.community_name", Source.PROPERTY, Format.TEXT,
            "Community name as it appears on the lease"),
    PROPERTY_CODE("property.code", Source.PROPERTY, Format.TEXT,
            "Short park code"),
    PROPERTY_ADDRESS("property.physical_address", Source.PROPERTY, Format.TEXT,
            "Park street address"),
    PROPERTY_CITY("property.city", Source.PROPERTY, Format.TEXT,
            "Park city"),
    PROPERTY_STATE("property.state", Source.PROPERTY, Format.TEXT,
            "Park state"),
    PROPERTY_ZIP("property.zip", Source.PROPERTY, Format.TEXT,
            "Park ZIP"),
    PROPERTY_ZONING("property.zoning", Source.PROPERTY, Format.TEXT,
            "Zoning designation, which the lease is required to state"),
    PROPERTY_MANAGER("property.manager", Source.PROPERTY, Format.TEXT,
            "Manager named on the agreement"),
    PAYABLE_TO("property.payable_to", Source.PROPERTY, Format.TEXT,
            "Who cheques are made out to -- the LLC that holds THIS property, not the parent company"),
    REMITTANCE_ADDRESS("property.remittance_address", Source.PROPERTY, Format.TEXT,
            "Where cheques for this property are mailed; falls back to the main office when unset"),

    // ---- tenancy.* : the people ----------------------------------------------

    TENANT_NAMES("tenancy.tenant_names", Source.COMPUTED, Format.LIST,
            "All tenants on this tenancy, as they sign"),
    INCOMING_TENANT_NAME("tenancy.incoming_tenant_name", Source.COMPUTED, Format.TEXT,
            "The tenant this document introduces",
            Set.of(), On.INTRODUCES_A_TENANT),
    OCCUPANCY_DATE("tenancy.occupancy_date", Source.TENANCY, Format.DATE,
            "Possession date -- tenancy.start_date, not the lease term"),

    // ---- landlord.* : the global_settings singleton --------------------------
    // Two address tokens, one column behind them for now: both resolve to the
    // main office. They are separate tokens because they are separate facts --
    // where legal process is served, and where checks go -- and a lockbox or a
    // payment processor pulls them apart the moment one is used. Splitting them
    // now means that day costs a column and a resolver line rather than
    // re-authoring every lease.

    LANDLORD_NAME("landlord.name", Source.ORGANIZATION, Format.TEXT,
            "Legal entity named on the lease"),
    LANDLORD_ADDRESS("landlord.address", Source.ORGANIZATION, Format.TEXT,
            "Where notices are served on the landlord and tenants write in -- the main office"),
    COMPLIANCE_EMAIL("landlord.compliance_email", Source.ORGANIZATION, Format.TEXT,
            "Address for compliance correspondence");


    /** Which row the value is read from. Not always the namespace it prints under. */
    public enum Source {
        CHARGE_TERM,
        INSTRUMENT,
        LOT,
        PROPERTY,
        TENANCY,
        ORGANIZATION,
        /** Derived from other values rather than read from a column. */
        COMPUTED,
        /** Read from charge terms on the lot, across tenancies. */
        HISTORY
    }

    /** How the value prints, and whether a clause may branch on it. */
    public enum Format {
        TEXT,
        MONEY,          // $725.00
        MONEY_WORDS,    // seven hundred twenty-five dollars
        INTEGER,        // 2
        INTEGER_WORDS,  // two
        ORDINAL,        // 1st
        PERCENT,        // 1.5%
        PERCENT_WORDS,  // one and one half percent
        DATE,
        LIST,           // water, sewer and trash
        ENUM            // the only format a clause condition may test
    }

    /**
     * Named groups of instrument types, in a nested class because an enum
     * constant cannot reference a static field of its own enum.
     */
    private static final class On {
        /** Paper with a term of its own -- mirrors the instrument_lease_has_term CHECK. */
        static final Set<InstrumentType> TERM_CARRYING = Set.of(
                InstrumentType.LEASE, InstrumentType.ASSUMPTION, InstrumentType.WAIVER);

        /** Paper that brings someone onto a tenancy. A waiver does not; it resets an anniversary. */
        static final Set<InstrumentType> INTRODUCES_A_TENANT = Set.of(
                InstrumentType.LEASE, InstrumentType.ASSUMPTION);
    }

    // allows a lookup table like "term.rate" → RATE
    private static final Map<String, DocumentToken> BY_NAME =
            Arrays.stream(values()).collect(Collectors.toMap(
                    DocumentToken::token, t -> t, (a, b) -> a, LinkedHashMap::new));

    // Fails at class load rather than at generate time, which is the only
    // moment a broken link would otherwise show up.
    static {
        for (DocumentToken t : values()) {
            if (t.governedByToken == null) {
                continue;
            }
            DocumentToken governor = BY_NAME.get(t.governedByToken);
            if (governor == null || !governor.canCondition()) {
                throw new IllegalStateException(
                        t + " is governed by " + t.governedByToken + ", which is not a conditionable token");
            }
            if (!governor.allowedValues().containsAll(t.populatedWhen)) {
                throw new IllegalStateException(
                        t + " claims to be populated when " + t.governedByToken + " is "
                                + t.populatedWhen + ", but that column only takes " + governor.allowedValues());
            }
        }
    }

    // Held by name rather than as a DocumentToken: an enum constant may not
    // reference a static field of its own type from its constructor. Resolved
    // through governedBy(), and checked once in the static block above.
    private final String token;
    private final Source source;
    private final Format format;
    private final String description;
    private final Set<String> allowedValues;
    private final Set<InstrumentType> resolvesOn;
    private final String governedByToken;
    private final Set<String> populatedWhen;

    DocumentToken(String token, Source source, Format format, String description) {
        this(token, source, format, description, Set.of(), Set.of(), null, Set.of());
    }

    DocumentToken(String token, Source source, Format format, String description,
                  Set<String> allowedValues) {
        this(token, source, format, description, allowedValues, Set.of(), null, Set.of());
    }

    DocumentToken(String token, Source source, Format format, String description,
                  Set<String> allowedValues, Set<InstrumentType> resolvesOn) {
        this(token, source, format, description, allowedValues, resolvesOn, null, Set.of());
    }

    DocumentToken(String token, Source source, Format format, String description,
                  String governedByToken, Set<String> populatedWhen) {
        this(token, source, format, description, Set.of(), Set.of(), governedByToken, populatedWhen);
    }

    DocumentToken(String token, Source source, Format format, String description,
                  Set<String> allowedValues, Set<InstrumentType> resolvesOn,
                  String governedByToken, Set<String> populatedWhen) {
        this.token = token;
        this.source = source;
        this.format = format;
        this.description = description;
        this.allowedValues = Set.copyOf(allowedValues);
        this.resolvesOn = Set.copyOf(resolvesOn);
        this.governedByToken = governedByToken;
        this.populatedWhen = Set.copyOf(populatedWhen);
    }

    /** The name as it appears between the braces, without them. */
    public String token() {
        return token;
    }

    /** The visible prefix -- what the picker groups by. */
    public String namespace() {
        return token.substring(0, token.indexOf('.'));
    }

    public Source source() {
        return source;
    }

    public Format format() {
        return format;
    }

    /** Shown in the clause editor's token picker. */
    public String description() {
        return description;
    }

    /** How it is written in a clause body. */
    public String placeholder() {
        return "{{" + token + "}}";
    }

    /**
     * Only an ENUM token can decide whether a clause prints. Conditioning on a
     * money amount is a category error and is refused when the clause is saved,
     * rather than discovered at generate time on a legal document.
     */
    // Can this token be used as a yes/no-ish decision for choosing which clause gets printed?
    public boolean canCondition() {
        return format == Format.ENUM;
    }

    /**
     * The values a clause condition on this token may name. Deliberately the
     * per-COLUMN subset rather than the whole Java enum -- late fee takes
     * NONE / FLAT / PERCENT_OF_RENT while NSF takes NONE / FLAT / BANK_OR_FLAT,
     * both of them FeeMethod. Empty for anything that is not an ENUM token.
     */
    public Set<String> allowedValues() {
        return allowedValues;
    }

    /**
     * Whether this token has a value to print on that kind of document. Most
     * tokens resolve everywhere and say so with an empty set; the ones that do
     * not would render blank, which is a trap a clause author cannot see. A
     * null instrument type means "not narrowing", so everything qualifies.
     */
    public boolean resolvesOn(InstrumentType instrumentType) {
        return instrumentType == null || resolvesOn.isEmpty() || resolvesOn.contains(instrumentType);
    }

    /** Empty when the token resolves on every kind of document. */
    public Set<InstrumentType> resolvesOn() {
        return resolvesOn;
    }

    /**
     * The method that decides whether this token has a real value. Present only
     * on amount tokens, and it mirrors the {@code term_*_amount_matches_method}
     * CHECK constraints: the database keeps the amount at zero unless the
     * method carries one, and this keeps the amount off the page under the same
     * condition.
     *
     * <p>The case it exists for: {@code term.late_fee_amount} is the same
     * column as {@code term.late_fee_percent}, so a clause printing the amount
     * with no condition renders "$1.50" for a tenant on 1.5% of rent -- a
     * signed lease stating a figure the deal does not.
     */
    public Optional<DocumentToken> governedBy() {
        return governedByToken == null ? Optional.empty() : of(governedByToken);
    }

    /** The governing method's values under which this token actually has a figure. */
    public Set<String> populatedWhen() {
        return populatedWhen;
    }

    public static Optional<DocumentToken> of(String token) {
        return Optional.ofNullable(BY_NAME.get(token));
    }

    public static Set<String> tokenNames() {
        return BY_NAME.keySet();
    }
}