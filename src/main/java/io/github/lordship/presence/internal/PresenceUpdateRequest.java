package io.github.lordship.presence.internal;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Body of POST /api/presence.
 * client is the random id one Glade window made for itself.
 * A null place means the window is somewhere without presence (a login screen, a concern without it yet).
 */
public record PresenceUpdateRequest(
        @NotNull UUID client,
        String place,
        String field,
        boolean typing
) { }
