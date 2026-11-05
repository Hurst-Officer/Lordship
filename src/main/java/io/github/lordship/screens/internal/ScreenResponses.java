package io.github.lordship.screens.internal;

import io.github.lordship.audit.History;
import io.github.lordship.homes.internal.HomeResponse;
import io.github.lordship.lots.internal.LotResponse;
import io.github.lordship.properties.internal.PropertyResponse;

import java.util.List;

// What GET /api/screens/... returns: everything one Glade screen shows, except the map layers.
public final class ScreenResponses {

    private ScreenResponses() { }

    // The property page (X2).
    // lots is null without lots:view. history is null without audit:view.
    public record PropertyScreen(
            PropertyResponse property,
            List<LotResponse> lots,
            History.PageHistory history
    ) { }

    // The lot page (X3).
    // property is the lot's property, for the page's header. homes is null without homes:view.
    // history is null without audit:view.
    public record LotScreen(
            LotResponse lot,
            PropertyResponse property,
            List<HomeResponse> homes,
            History.PageHistory history
    ) { }
}
