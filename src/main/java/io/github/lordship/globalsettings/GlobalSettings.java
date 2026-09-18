package io.github.lordship.globalsettings;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The handful of facts that belong to the company rather than to any one park.
 * (singleton in sql)
 *
 * <p>Deliberately small. A value belongs here only when it is the same at every
 * park: the compliance address is, and the landlord's name is not, because each
 * park is held by its own LLC and the lease has to name the one that owns the
 * ground the home sits on.
 */
public record GlobalSettings(
        UUID uuid,
        String complianceEmail,
        OffsetDateTime updatedAt
) {}
