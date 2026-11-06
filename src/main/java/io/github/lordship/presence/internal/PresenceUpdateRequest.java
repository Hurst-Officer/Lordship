package io.github.lordship.presence.internal;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Body of POST /api/presence.
 * client is the random id one Glade window made for itself.
 * A null place means the window is somewhere without presence (a login screen, a concern without it yet).
 * picks are the records the window has picked on that screen, like the lot ids picked on a property's map.
 * Null or left out means none.
 */
public record PresenceUpdateRequest(
        @NotNull UUID client,
        String place,
        String field,
        boolean typing,
        List<String> picks
) { }
