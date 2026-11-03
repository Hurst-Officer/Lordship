package io.github.lordship.presence.internal;

import java.util.UUID;

/**
 * Someone else in Glade, as the other agents see them.
 * place is where they are, like "properties/{propertyId}/lots/{lotId}".
 * field is the box they have selected, like "propertyZoning", or null.
 * typing is true while they are typing in that box.
 */
public record PresentAgent(
        UUID agentId,
        String name,
        String place,
        String field,
        boolean typing
) { }
