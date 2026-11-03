package io.github.lordship.presence.internal;

import java.util.List;

// The data of each "roster" event on the presence stream. The viewer is never in it.
public record RosterResponse(List<PresentAgent> agents) { }
