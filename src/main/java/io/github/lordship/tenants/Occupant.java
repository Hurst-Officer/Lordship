package io.github.lordship.tenants;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One person living in a home without being on the lease -- children, an
 * elderly parent, and people who are there whether or not anyone approved it.
 * Not liable, never invoiced, never a signature block.
 *
 * <p>The point is the question the office cannot answer today: who lives in
 * which unit, and since when. That information arrives on every tenant
 * information form and is currently thrown away.
 */
public record Occupant(
        UUID uuid,
        UUID tenancyId,
        UUID personId,
        LocalDate startDate,
        LocalDate endDate,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) { }