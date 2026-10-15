package io.github.lordship.shared;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One row of a tiered late fee chart, like "9th to 12th: $20.00".
 *
 * <p>A tier starts on fromDay and runs until the next tier starts. The last
 * tier runs to the end of the month, and also covers "no payment received".
 *
 * <p>The late fee for a month is the one tier the payment date lands in.
 * Tiers do not add up: paying on the 14th costs $28, not $20 + $28.
 *
 * <p>The database stores a list of tiers as JSONB with snake_case keys:
 * {@code [{"from_day": 9, "amount": 20.00}, {"from_day": 13, "amount": 28.00}]}.
 * The API uses fromDay and amount, like every other camelCase field.
 */
public record LateFeeTier(int fromDay, BigDecimal amount) {

    /** The field name problems are reported on, so the form can highlight the tier table. */
    public static final String FIELD = "late_fee_tiers";

    // The tiers are read and written here, not by Spring's ObjectMapper bean,
    // so the row mappers work in every test slice without extra wiring.
    private static final JsonMapper JSON = JsonMapper.builder().build();

    // ---- database JSON ------------------------------------------------------

    /** The JSON text to store in a late_fee_tiers column. An empty list is "[]". */
    public static String toJson(List<LateFeeTier> tiers) {
        ArrayNode array = JSON.createArrayNode();
        for (LateFeeTier tier : tiers) {
            array.addObject()
                    .put("from_day", tier.fromDay())
                    .put("amount", tier.amount());
        }
        return JSON.writeValueAsString(array);
    }

    /** Reads a late_fee_tiers column. Null or "[]" gives an empty list. */
    public static List<LateFeeTier> fromJson(String json) {
        List<LateFeeTier> tiers = new ArrayList<>();
        if (json == null) {
            return tiers;
        }
        JsonNode array = JSON.readTree(json);
        for (int i = 0; i < array.size(); i++) {
            JsonNode tier = array.get(i);
            tiers.add(new LateFeeTier(
                    tier.get("from_day").intValue(),
                    toCents(tier.get("amount").decimalValue())));
        }
        return tiers;
    }

    // ---- PATCH bodies -------------------------------------------------------

    /**
     * Reads the tiers from a PATCH body. Jackson hands the body over as a list
     * of maps, like [{"fromDay": 9, "amount": 20}].
     *
     * <p>Only checks that each value has the right type. The rules for a
     * finished chart (order, grace period, amounts above zero) are in
     * {@link #problems}.
     *
     * <p>Null means "no tiers" and gives an empty list.
     */
    public static List<LateFeeTier> fromRequest(Object raw) {
        List<LateFeeTier> tiers = new ArrayList<>();
        if (raw == null) {
            return tiers;
        }
        if (!(raw instanceof List<?> list)) {
            throw InvalidRequest.onField(FIELD, "late_fee_tier.not_a_list");
        }

        for (int i = 0; i < list.size(); i++) {
            int tierNumber = i + 1;
            if (!(list.get(i) instanceof Map<?, ?> tier)) {
                throw InvalidRequest.onField(FIELD, "late_fee_tier.incomplete", tierNumber);
            }

            BigDecimal fromDay = toNumber(tier.get("fromDay"), tierNumber);
            BigDecimal amount = toNumber(tier.get("amount"), tierNumber);

            // A day must be a whole number. 9.0 is fine, 9.5 is not.
            if (fromDay.stripTrailingZeros().scale() > 0) {
                throw InvalidRequest.onField(FIELD, "late_fee_tier.day_not_whole", tierNumber, fromDay);
            }
            // A day too big to fit in an int is out of range, not a crash.
            if (fromDay.abs().compareTo(BigDecimal.valueOf(1000)) > 0) {
                throw InvalidRequest.onField(FIELD, "late_fee_tier.day_out_of_range", tierNumber, fromDay);
            }
            tiers.add(new LateFeeTier(fromDay.intValue(), toCents(amount)));
        }
        return tiers;
    }

    // ---- rules for a finished chart -----------------------------------------

    /**
     * Everything wrong with the tiers, given the late fee method and the
     * term's due day and grace period. Empty when the tiers are fine.
     *
     * <p>Mirrors the late_fee_tiers CHECK constraints in V1 and V10, plus two
     * rules the database cannot check: the days must climb, and the first
     * tier must start after the grace period ends.
     */
    public static List<DomainProblem.Problem> problems(FeeMethod lateFeeMethod,
                                                       List<LateFeeTier> tiers,
                                                       int paymentDueDay,
                                                       int gracePeriodDays) {
        List<DomainProblem.Problem> problems = new ArrayList<>();

        if (lateFeeMethod != FeeMethod.TIERED) {
            if (!tiers.isEmpty()) {
                problems.add(DomainProblem.Problem.onField(FIELD,
                        "late_fee_tier.must_be_empty", lateFeeMethod));
            }
            return problems;
        }

        if (tiers.isEmpty()) {
            problems.add(DomainProblem.Problem.onField(FIELD, "late_fee_tier.chart_required"));
            return problems;
        }

        // The last day rent can be paid without a fee. Due on the 1st with
        // 7 grace days means the 8th is still free.
        int lastFreeDay = paymentDueDay + gracePeriodDays;
        int firstDay = tiers.getFirst().fromDay();
        if (firstDay <= lastFreeDay) {
            problems.add(DomainProblem.Problem.onField(FIELD,
                    "late_fee_tier.inside_grace", firstDay, lastFreeDay, paymentDueDay, gracePeriodDays));
        }

        for (int i = 0; i < tiers.size(); i++) {
            LateFeeTier tier = tiers.get(i);
            int tierNumber = i + 1;

            if (tier.fromDay() < 2 || tier.fromDay() > 31) {
                problems.add(DomainProblem.Problem.onField(FIELD,
                        "late_fee_tier.day_out_of_range", tierNumber, tier.fromDay()));
            }
            if (tier.amount().signum() <= 0) {
                problems.add(DomainProblem.Problem.onField(FIELD,
                        "late_fee_tier.amount_required", tierNumber));
            }
            if (i > 0 && tier.fromDay() <= tiers.get(i - 1).fromDay()) {
                problems.add(DomainProblem.Problem.onField(FIELD,
                        "late_fee_tier.out_of_order", tierNumber, tier.fromDay(), tiers.get(i - 1).fromDay()));
            }
        }
        return problems;
    }

    // ---- helpers ------------------------------------------------------------

    // Accepts a JSON number or a numeric string, like the other amount fields do.
    private static BigDecimal toNumber(Object raw, int tierNumber) {
        if (raw == null) {
            throw InvalidRequest.onField(FIELD, "late_fee_tier.incomplete", tierNumber);
        }
        try {
            return new BigDecimal(raw.toString().trim());
        } catch (NumberFormatException e) {
            throw InvalidRequest.onField(FIELD, "late_fee_tier.not_a_number", tierNumber, raw);
        }
    }

    // Always two decimal places, so 20 and 20.00 compare equal in the audit log.
    private static BigDecimal toCents(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
