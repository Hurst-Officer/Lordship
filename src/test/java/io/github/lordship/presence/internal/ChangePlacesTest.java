package io.github.lordship.presence.internal;

import io.github.lordship.audit.AuditService;
import io.github.lordship.presence.internal.ChangePlaces.Notice;
import io.github.lordship.presence.internal.ChangePlaces.Parents;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A database change notice becomes the Glade places whose screens show the row (V15, ChangeNotices).
 * The places must be spelled the way Glade's screens report them (views/&lt;concern&gt;/steps.ts), or nothing reloads.
 */
public class ChangePlacesTest {

    private static Notice notice(String table, String... keyValues) {
        Map<String, String> ids = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            ids.put(keyValues[i], keyValues[i + 1]);
        }
        return new Notice(table, ids);
    }

    // The tables the Properties screens show. For these the Properties places are the right places.
    private static final Set<String> SHOWN_ON_PROPERTIES = Set.of("property", "lot", "mobile_home");

    /**
     * Guards the next concern. A table a screen shows (AuditService.SCREEN_TABLES, the list a concern's history
     * bars already need) must tell a place of that concern. A table placesOf has no case for falls to the default
     * and tells the Properties places only, so the new concern's screens would never load again after a change
     * made elsewhere.
     */
    @Test
    void everyTableAScreenShows_tellsAPlaceOfItsOwnConcern() {
        // one notice carrying every id V15 can send, and parents that answer every lookup
        Map<String, String> ids = new LinkedHashMap<>();
        for (String key : ChangePlaces.CARRIED_IDS) {
            ids.put(key, key.toUpperCase());
        }
        Parents parents = new Parents(
                Map.of("LOT_ID", "PROPERTY_ID"),
                Map.of("MOBILE_HOME_ID", "LOT_ID"),
                Map.of("UUID", "AGENT_ID"),
                Map.of("SECTION", "TEMPLATE"),
                Map.of("ASSIGNMENT", new String[] {"PROPERTY", "TEMPLATE"}));

        for (String table : new TreeSet<>(AuditService.SCREEN_TABLES)) {
            List<String> places = ChangePlaces.placesOf(new Notice(table, ids), parents);

            assertFalse(places.isEmpty(), table + " is shown by a screen, but a change to " + table
                    + " tells no place: add a case to ChangePlaces.placesOf");
            if (!SHOWN_ON_PROPERTIES.contains(table)) {
                assertTrue(places.stream().anyMatch(place -> !place.startsWith("properties")),
                        table + " is shown by a screen outside Properties, but a change to " + table + " tells only "
                                + places + ": add a case to ChangePlaces.placesOf, and any new parent id column to "
                                + "ChangePlaces.CARRIED_IDS and to V15 lordship_notify_change");
            }
        }
    }

    @Test
    void aLot_tellsItsPropertyAndItself() {
        List<String> places = ChangePlaces.placesOf(notice("lot", "uuid", "L", "property_id", "P"), Parents.none());

        assertEquals(List.of("properties/P", "properties/P/lots/L"), places);
    }

    @Test
    void aProperty_tellsTheListAndItself() {
        assertEquals(List.of("properties", "properties/P"),
                ChangePlaces.placesOf(notice("property", "uuid", "P"), Parents.none()));
    }

    @Test
    void aRowOfALot_findsTheLotsProperty() {
        Parents parents = new Parents(Map.of("L", "P"), Map.of(), Map.of(), Map.of(), Map.of());

        assertEquals(List.of("properties/P", "properties/P/lots/L"),
                ChangePlaces.placesOf(notice("tenancy", "uuid", "T", "lot_id", "L"), parents));
        // a home's secured party goes through the home's lot
        Parents viaHome = new Parents(Map.of("L", "P"), Map.of("H", "L"), Map.of(), Map.of(), Map.of());
        assertEquals(List.of("properties/P", "properties/P/lots/L"),
                ChangePlaces.placesOf(notice("mobile_home_secured_party", "uuid", "S", "mobile_home_id", "H"), viaHome));
    }

    @Test
    void anyOtherRowOfAProperty_tellsTheProperty() {
        assertEquals(List.of("properties/P"),
                ChangePlaces.placesOf(notice("utility_bill", "uuid", "B", "property_id", "P"), Parents.none()));
    }

    @Test
    void aTableGladeDoesNotShow_tellsNothing() {
        assertEquals(List.of(), ChangePlaces.placesOf(notice("global_settings", "uuid", "G"), Parents.none()));
    }

    @Test
    void aSignIn_tellsThePersonsPageInRoles() {
        assertEquals(List.of("roles/person/A"),
                ChangePlaces.placesOf(notice("agent_session", "uuid", "S", "agent_id", "A"), Parents.none()));
    }

    @Test
    void aRoleGiven_tellsTheListTheRoleAndThePerson() {
        assertEquals(List.of("roles", "roles/role/R", "roles/person/A"),
                ChangePlaces.placesOf(notice("granted_role", "uuid", "G", "agent_id", "A", "role_id", "R"), Parents.none()));
    }

    @Test
    void aPerson_tellsTheirAgentsPages_andNothingForSomeoneWhoDoesNotSignIn() {
        Parents parents = new Parents(Map.of(), Map.of(), Map.of("PE", "A"), Map.of(), Map.of());

        assertEquals(List.of("roles/person/A", "self/A"),
                ChangePlaces.placesOf(notice("person", "uuid", "PE"), parents));
        assertEquals(List.of(), ChangePlaces.placesOf(notice("person", "uuid", "TENANT"), parents));
    }

    @Test
    void documents_tellTheDocumentItsSectionAndThePark() {
        Parents parents = new Parents(Map.of(), Map.of(), Map.of(), Map.of("S", "D"),
                Map.of("AS", new String[] { "P", "D" }));

        assertEquals(List.of("documents/doc/D", "documents/doc/D/section/S"),
                ChangePlaces.placesOf(notice("template_clause", "uuid", "C", "section", "S"), parents));
        assertEquals(List.of("documents", "documents/doc/D", "documents/P/doc/D"),
                ChangePlaces.placesOf(notice("property_document_assignment", "uuid", "AS", "property", "P",
                        "document_template", "D"), parents));
        assertEquals(List.of("documents/doc/D", "documents/P/doc/D"),
                ChangePlaces.placesOf(notice("property_document_customization", "uuid", "X", "assignment", "AS"), parents));
    }

    @Test
    void aParentThatCannotBeFound_isSkipped() {
        // a clause whose section was deleted since: nothing to build its places from
        assertEquals(List.of(), ChangePlaces.placesOf(notice("template_clause", "uuid", "C", "section", "S"), Parents.none()));
    }

    @Test
    void manyLotsOfOneProperty_foldIntoTheProperty() {
        Map<String, String> places = new LinkedHashMap<>();
        places.put("properties/P", "lot");
        for (int i = 0; i < 30; i++) places.put("properties/P/lots/L" + i, "lot");
        places.put("properties/Q/lots/ONE", "lot");

        Map<String, String> folded = ChangePlaces.coalesced(places);

        assertEquals(Map.of("properties/P", "lot", "properties/Q/lots/ONE", "lot"), folded);
    }

    @Test
    void aFewLots_stayAsTheyAre_andRolesPlacesAreNeverFolded() {
        Map<String, String> places = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) places.put("properties/P/lots/L" + i, "lot");
        for (int i = 0; i < 30; i++) places.put("roles/person/A" + i, "agent_session");

        assertEquals(places, ChangePlaces.coalesced(places));
    }

    @Test
    void aBatchTooBigToList_tellsEverything() {
        Map<String, String> places = new LinkedHashMap<>();
        for (int i = 0; i < ChangePlaces.MAX_PLACES + 1; i++) places.put("roles/person/A" + i, "agent");

        Map<String, String> folded = ChangePlaces.coalesced(places);

        assertEquals(1, folded.size());
        assertTrue(folded.containsKey(ChangePlaces.EVERYTHING));
    }
}
