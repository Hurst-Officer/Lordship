package io.github.lordship.access;

import java.util.List;

/**
 * The colors an agent can pick to be shown in to coworkers in Glade: their initials, the boxes they have selected,
 * and the lots they have picked on a map. Lordship keeps the name only; Glade owns what each name looks like, by day
 * and by night (the --agent-* colors in Glade's styles.css, listed in src/parts/Presence/Presence.tsx).
 * The two lists must hold the same names: a name added here and not there is drawn in a color picked by the
 * agent's id, and a name added there and not here is refused (agent.color_unknown).
 * None is a color the maps themselves use (green, purple and orange are a lot's own colors, fern is "you").
 */
public final class AgentColors {

    private AgentColors() { }

    public static final List<String> NAMES = List.of(
            "lake", "lagoon", "indigo", "plum", "rose", "wine", "brick", "honey", "umber", "slate");
}
