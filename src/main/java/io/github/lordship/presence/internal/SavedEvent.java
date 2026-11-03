package io.github.lordship.presence.internal;

import java.util.UUID;

/**
 * The data of a "saved" event on the presence stream: someone saved something on a screen.
 * field is the box they saved, like "propertyZoning", or a word for a list change, like "lots".
 * byYou is true when the viewer is the same agent, in another window.
 * It says where to look again. The new value itself comes from the screen's own GET.
 */
public record SavedEvent(
        UUID agentId,
        String name,
        String place,
        String field,
        boolean byYou
) { }
