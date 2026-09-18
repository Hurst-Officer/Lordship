package io.github.lordship.instruments;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RentHistoryTest {

    @Test
    void disclosedYears_shouldBeTheFiveCompleteYearsBeforeTheTerm() {
        // Act
        List<Integer> years = RentHistory.disclosedYears(LocalDate.of(2026, 11, 1));

        // Assert -- oldest first, and 2026 is the rate the lease states above
        assertEquals(List.of(2021, 2022, 2023, 2024, 2025), years);
    }

    @Test
    void disclosedYears_shouldNotCareWhichMonthTheTermStarts() {
        // Arrange -- a January lease and a December lease disclose the same years
        // Act + Assert
        assertEquals(RentHistory.disclosedYears(LocalDate.of(2026, 1, 1)),
                RentHistory.disclosedYears(LocalDate.of(2026, 12, 31)));
    }

    @Test
    void disclosedYears_shouldBeEmpty_forPaperWithNoTerm() {
        // Act + Assert -- a notice does not expire and discloses nothing
        assertEquals(List.of(), RentHistory.disclosedYears(null));
    }
}
