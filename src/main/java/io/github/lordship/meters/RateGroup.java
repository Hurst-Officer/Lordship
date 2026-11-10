package io.github.lordship.meters;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Mains that keep a rate of their own: another provider, a phase on another
 * city's lines. Mains in no group share the park's default rate.
 */
public record RateGroup(
        UUID uuid,
        UUID propertyId,
        String name,
        String notes,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {}
