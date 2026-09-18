package io.github.lordship.instruments;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Which years a lease has to disclose.
 */
public final class RentHistory {

    /** The disclosure is five years. Not four, not however many we happen to hold. */
    public static final int YEARS = 5;

    /** Printed when the company cannot know what was charged -- never a blank or a zero. */
    public static final String UNKNOWN = "Unknown";

    private RentHistory() {}

    /**
     * The five complete years before the lease begins, oldest first.
     *
     * <p>The year the term starts is excluded: that rent is the rate the lease
     * states above, so repeating it as history would print the same figure
     * twice and imply a year of it that has not happened yet.
     */
    public static List<Integer> disclosedYears(LocalDate termStart) {
        if (termStart == null) {
            return List.of();
        }
        int firstYear = termStart.getYear() - YEARS;
        return IntStream.range(0, YEARS).mapToObj(i -> firstYear + i).toList();
    }
}
