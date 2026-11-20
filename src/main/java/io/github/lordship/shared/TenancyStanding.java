package io.github.lordship.shared;

import java.time.LocalDate;

/**
 * When a tenancy counts as ended, and when a tenancy still holds the tenancy's lot, said once.
 *
 * <p>A tenancy's end date is the tenancy's last day. Two questions are asked of that date, and every screen and
 * every rule asks one of the two through this class, so the answers cannot drift apart:
 * <ul>
 *   <li>Has the tenancy ended? Only once the end date has passed. On the end date itself the tenancy is still
 *       there (NOT_ENDED_SQL, hasEnded).</li>
 *   <li>Does the tenancy still hold the lot on the day another tenancy would start? Until the end date, and not
 *       on the end date itself: the next tenancy may start the day the last one ends (HOLDS_ON_DAY_SQL).</li>
 * </ul>
 *
 * <p>"Today" is the office's day in both places the question is asked: LocalDate.now() in Java and CURRENT_DATE
 * in the database follow the one zone LordshipApplication sets at startup.
 *
 * <p>The sql is written for a tenancy under the alias t, and is put into a query with formatted(). The class
 * sits in shared because the lots module reads the tenancy table by name and cannot depend on the tenancy
 * module, which depends on lots.
 */
public final class TenancyStanding {

    private TenancyStanding() { }

    /** sql: the tenancy t has not ended (no end date, or an end date of today or later). */
    public static final String NOT_ENDED_SQL = "(t.end_date IS NULL OR t.end_date >= CURRENT_DATE)";

    /**
     * sql: the tenancy t still holds the lot on :day, the day another tenancy would start. A null :day means
     * today. The query supplies the day parameter.
     */
    public static final String HOLDS_ON_DAY_SQL =
            "(t.end_date IS NULL OR t.end_date > COALESCE(CAST(:day AS date), CURRENT_DATE))";

    /** The Java form of NOT_ENDED_SQL: whether a tenancy with this end date has ended by today. */
    public static boolean hasEnded(LocalDate endDate, LocalDate today) {
        return endDate != null && endDate.isBefore(today);
    }
}
