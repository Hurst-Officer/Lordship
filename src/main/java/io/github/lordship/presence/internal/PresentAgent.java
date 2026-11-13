package io.github.lordship.presence.internal;

import java.util.List;
import java.util.UUID;

/**
 * Someone else in Glade, as the other agents see them.
 * place is where they are, like "properties/{propertyId}/lots/{lotId}".
 * field is the box they have selected, like "propertyZoning", or null.
 * typing is true while they are typing in that box.
 * picks are the records they have picked on that screen, like lot ids. Empty when none.
 * color is the color they picked to be shown in (one of the access module's AgentColors.NAMES), or null when they
 * have not picked one; Glade then draws them in a color of the same set chosen by their id.
 */
public record PresentAgent(
        UUID agentId,
        String name,
        String color,
        String place,
        String field,
        boolean typing,
        List<String> picks
) { }
