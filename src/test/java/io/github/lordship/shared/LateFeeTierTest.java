package io.github.lordship.shared;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LateFeeTierTest {

    /** The chart from the old leases: 9th $20, 13th $28, 16th $34, 21st $44, 25th $60. */
    private static final List<LateFeeTier> OLD_LEASE_CHART = List.of(
            new LateFeeTier(9, new BigDecimal("20.00")),
            new LateFeeTier(13, new BigDecimal("28.00")),
            new LateFeeTier(16, new BigDecimal("34.00")),
            new LateFeeTier(21, new BigDecimal("44.00")),
            new LateFeeTier(25, new BigDecimal("60.00")));

    // ---- database JSON ------------------------------------------------------

    @Test
    void toJson_shouldUseSnakeCaseKeys_toMatchTheCheckConstraints() {
        // Act
        String json = LateFeeTier.toJson(List.of(new LateFeeTier(9, new BigDecimal("20.00"))));

        // Assert -- the CHECK constraints in V1 and V10 look for from_day and amount
        assertEquals("[{\"from_day\":9,\"amount\":20.00}]", json);
    }

    @Test
    void toJson_shouldWriteAnEmptyArray_forNoTiers() {
        assertEquals("[]", LateFeeTier.toJson(List.of()));
    }

    @Test
    void fromJson_shouldReadWhatToJsonWrote() {
        // Act
        List<LateFeeTier> read = LateFeeTier.fromJson(LateFeeTier.toJson(OLD_LEASE_CHART));

        // Assert
        assertEquals(OLD_LEASE_CHART, read);
    }

    @Test
    void fromJson_shouldReadPostgresSpacing_andFillInCents() {
        // Arrange -- Postgres prints jsonb with spaces, and a whole number has no cents
        String json = "[{\"amount\": 20, \"from_day\": 9}]";

        // Act / Assert
        assertEquals(List.of(new LateFeeTier(9, new BigDecimal("20.00"))), LateFeeTier.fromJson(json));
    }

    @Test
    void fromJson_shouldGiveAnEmptyList_forNull() {
        assertEquals(List.of(), LateFeeTier.fromJson(null));
    }

    // ---- PATCH bodies -------------------------------------------------------

    @Test
    void fromRequest_shouldAcceptNumbersAndNumericStrings() {
        // Act
        List<LateFeeTier> tiers = LateFeeTier.fromRequest(List.of(
                Map.of("fromDay", 9, "amount", 20),
                Map.of("fromDay", "13", "amount", "28.5")));

        // Assert
        assertEquals(List.of(
                new LateFeeTier(9, new BigDecimal("20.00")),
                new LateFeeTier(13, new BigDecimal("28.50"))), tiers);
    }

    @Test
    void fromRequest_shouldTreatNullAsNoTiers() {
        assertEquals(List.of(), LateFeeTier.fromRequest(null));
    }

    @Test
    void fromRequest_shouldRefuseSomethingThatIsNotAList() {
        InvalidRequest refused = assertThrows(InvalidRequest.class,
                () -> LateFeeTier.fromRequest(Map.of("fromDay", 9, "amount", 20)));

        assertEquals("late_fee_tier.not_a_list", refused.problem().code());
        assertEquals(LateFeeTier.FIELD, refused.problem().field());
    }

    @Test
    void fromRequest_shouldRefuseAFractionalDay() {
        InvalidRequest refused = assertThrows(InvalidRequest.class,
                () -> LateFeeTier.fromRequest(List.of(Map.of("fromDay", 9.5, "amount", 20))));

        assertEquals("late_fee_tier.day_not_whole", refused.problem().code());
    }

    @Test
    void fromRequest_shouldRefuseAnAmountThatIsNotANumber() {
        InvalidRequest refused = assertThrows(InvalidRequest.class,
                () -> LateFeeTier.fromRequest(List.of(Map.of("fromDay", 9, "amount", "twenty"))));

        assertEquals("late_fee_tier.not_a_number", refused.problem().code());
    }

    // ---- rules for a finished chart -----------------------------------------

    @Test
    void problems_shouldAcceptTheOldLeaseChart() {
        // Due on the 1st with 7 grace days: the 8th is the last free day
        assertEquals(List.of(), LateFeeTier.problems(FeeMethod.TIERED, OLD_LEASE_CHART, 1, 7));
    }

    @Test
    void problems_shouldRefuseAFirstTierOnTheLastFreeDay() {
        // Arrange -- the 8th is still inside the grace period
        List<LateFeeTier> tiers = List.of(new LateFeeTier(8, new BigDecimal("20.00")));

        // Act
        List<DomainProblem.Problem> problems = LateFeeTier.problems(FeeMethod.TIERED, tiers, 1, 7);

        // Assert
        assertEquals(1, problems.size());
        assertEquals("late_fee_tier.inside_grace", problems.getFirst().code());
        assertEquals(List.of(8, 8, 1, 7), problems.getFirst().args());
    }

    @Test
    void problems_shouldRefuseRepeatedDays() {
        List<LateFeeTier> tiers = List.of(
                new LateFeeTier(9, new BigDecimal("20.00")),
                new LateFeeTier(9, new BigDecimal("28.00")));

        List<DomainProblem.Problem> problems = LateFeeTier.problems(FeeMethod.TIERED, tiers, 1, 7);

        assertEquals(List.of("late_fee_tier.out_of_order"),
                problems.stream().map(DomainProblem.Problem::code).toList());
    }

    @Test
    void problems_shouldRefuseADayPastTheMonth() {
        List<LateFeeTier> tiers = List.of(new LateFeeTier(32, new BigDecimal("20.00")));

        List<DomainProblem.Problem> problems = LateFeeTier.problems(FeeMethod.TIERED, tiers, 1, 7);

        assertEquals(List.of("late_fee_tier.day_out_of_range"),
                problems.stream().map(DomainProblem.Problem::code).toList());
    }

    @Test
    void problems_shouldRequireAChart_whenTiered() {
        List<DomainProblem.Problem> problems = LateFeeTier.problems(FeeMethod.TIERED, List.of(), 1, 7);

        assertEquals(List.of("late_fee_tier.chart_required"),
                problems.stream().map(DomainProblem.Problem::code).toList());
    }

    @Test
    void problems_shouldRefuseAChart_whenNotTiered() {
        List<DomainProblem.Problem> problems = LateFeeTier.problems(FeeMethod.FLAT, OLD_LEASE_CHART, 1, 7);

        assertEquals(List.of("late_fee_tier.must_be_empty"),
                problems.stream().map(DomainProblem.Problem::code).toList());
    }

    @Test
    void problems_shouldAcceptNoChart_whenNotTiered() {
        assertEquals(List.of(), LateFeeTier.problems(FeeMethod.FLAT, List.of(), 1, 7));
    }
}
