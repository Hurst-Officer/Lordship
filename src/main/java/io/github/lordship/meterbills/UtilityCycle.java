package io.github.lordship.meterbills;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * How one park passes one service on, from effectiveFrom (a 1st) until a later cycle starts.
 * Billed every 2 months, in even months, two months after the service period ends:
 * cycleMonths 2, anchorMonth 2, lagMonths 2.
 *
 * @param rateGroupId null for the park's own cycle; set for a rate group billed on another rhythm.
 *                    A group with no cycle of its own follows the park's.
 * @param anchorMonth any month (1-12) that carries a charge; the others in between are off-months
 * @param lagMonths   months from the end of the service period to the billing month
 * @param readDay     when submeters are due to be read, if the park has a set day
 */
public record UtilityCycle(
        UUID uuid,
        UUID propertyId,
        UUID rateGroupId,
        UtilityService service,
        Integer cycleMonths,
        Integer anchorMonth,
        Integer lagMonths,
        Integer readDay,
        LocalDate effectiveFrom,
        String notes,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {}
