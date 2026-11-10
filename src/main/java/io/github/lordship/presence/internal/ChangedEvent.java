package io.github.lordship.presence.internal;

import java.util.List;

/**
 * The data of a "changed" event on the presence stream: rows changed in the database (ChangeNotices), whoever
 * changed them. Each place is a Glade screen that shows one of them, with the table that changed there.
 * Place "*" means load everything again (notices may have been missed). Nobody is named: the database does not
 * know who made the change.
 */
public record ChangedEvent(List<Place> places) {

    public record Place(String place, String table) { }
}
