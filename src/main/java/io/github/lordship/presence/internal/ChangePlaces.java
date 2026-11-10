package io.github.lordship.presence.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a database change notice (V16 lordship_notify_change) into the Glade places that show the changed row,
 * so the screens on those places load again. A place is what Glade's screens report to presence:
 * "properties/&lt;propertyId&gt;/lots/&lt;lotId&gt;", "roles/person/&lt;agentId&gt;", "documents/doc/&lt;templateId&gt;"...
 *
 * <p>No database here: ids a notice does not carry (a home's lot, a clause's document) come in Parents,
 * looked up for the whole batch at once (ChangeRelay).
 */
final class ChangePlaces {

    private ChangePlaces() { }

    // "*": every window loads everything it shows again (Glade treats it like its own reconnect)
    static final String EVERYTHING = "*";

    // More changed rows than this under one parent (lots of one property) tell the parent's place instead
    static final int MAX_UNDER_ONE_PARENT = 25;

    // More places than this in one batch tell every window to load everything again instead
    static final int MAX_PLACES = 200;

    // One notice: the table and the ids it carries (uuid, property_id, lot_id, agent_id, template...)
    record Notice(String table, Map<String, String> ids) {
        String id(String key) {
            return ids.get(key);
        }
    }

    // The parents a notice does not carry, by child id. A missing entry (a row deleted since) is simply skipped.
    record Parents(Map<String, String> lotProperty,
                   Map<String, String> homeLot,
                   Map<String, String> personAgent,
                   Map<String, String> sectionTemplate,
                   Map<String, String[]> assignmentPropertyTemplate) {

        static Parents none() {
            return new Parents(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
    }

    // The places that show the row, most specific last. Empty for a table Glade does not show.
    static List<String> placesOf(Notice n, Parents parents) {
        List<String> places = new ArrayList<>();
        String id = n.id("uuid");
        switch (n.table()) {
            case "property" -> {
                places.add("properties");
                add(places, property(id));
            }
            case "lot" -> {
                add(places, property(n.id("property_id")));
                add(places, lot(n.id("property_id"), id));
            }
            case "mobile_home_secured_party" -> {
                String lotId = parents.homeLot().get(n.id("mobile_home_id"));
                lotAndProperty(places, lotId, parents);
            }
            case "person" -> {
                String agentId = parents.personAgent().get(id);
                if (agentId != null) person(places, agentId);
            }
            case "agent" -> {
                places.add("roles");
                person(places, id);
            }
            case "agent_session", "denied_permission" -> add(places, rolesPerson(n.id("agent_id")));
            case "agent_property_assignment" -> {
                add(places, rolesPerson(n.id("agent_id")));
                add(places, self(n.id("agent_id")));
            }
            case "agent_role" -> {
                places.add("roles");
                add(places, role(id));
            }
            case "role_permission" -> {
                places.add("roles");
                add(places, role(n.id("role_id")));
            }
            case "granted_role" -> {
                places.add("roles");
                add(places, role(n.id("role_id")));
                add(places, rolesPerson(n.id("agent_id")));
            }
            case "document_template" -> {
                places.add("documents");
                add(places, doc(id));
            }
            case "document_style", "document_section" -> {
                add(places, doc(n.id("template")));
                if (n.table().equals("document_section")) add(places, section(n.id("template"), id));
            }
            case "template_clause" -> {
                String template = parents.sectionTemplate().get(n.id("section"));
                add(places, doc(template));
                add(places, section(template, n.id("section")));
            }
            case "property_document_assignment" -> {
                places.add("documents");
                add(places, doc(n.id("document_template")));
                add(places, park(n.id("property"), n.id("document_template")));
            }
            case "property_document_customization" -> {
                String[] pt = parents.assignmentPropertyTemplate().get(n.id("assignment"));
                if (pt != null) {
                    add(places, doc(pt[1]));
                    add(places, park(pt[0], pt[1]));
                }
            }
            default -> {
                // Anything else that belongs to a lot or a property: its lot's place and its property's
                if (n.id("lot_id") != null) {
                    lotAndProperty(places, n.id("lot_id"), parents);
                } else {
                    add(places, property(n.id("property_id")));
                }
            }
        }
        return places;
    }

    /**
     * The places of a whole batch, each with the table that changed there.
     * Many rows under one parent (a billing run over every lot of a park) tell the parent instead of each row,
     * and a batch too big to list tells everything ("*").
     */
    static Map<String, String> coalesced(Map<String, String> tableByPlace) {
        Map<String, Integer> underParent = new LinkedHashMap<>();
        for (String place : tableByPlace.keySet()) {
            String parent = parentOf(place);
            if (parent != null) underParent.merge(parent, 1, Integer::sum);
        }
        Map<String, String> result = new LinkedHashMap<>();
        tableByPlace.forEach((place, table) -> {
            String parent = parentOf(place);
            if (parent != null && underParent.get(parent) > MAX_UNDER_ONE_PARENT) {
                result.putIfAbsent(parent, table);
            } else {
                result.putIfAbsent(place, table);
            }
        });
        if (result.size() > MAX_PLACES) {
            return Map.of(EVERYTHING, "");
        }
        return result;
    }

    // The place a screen of the parent listens on: a lot's property ("properties/<p>" for "properties/<p>/lots/<l>"),
    // a section's document ("documents/doc/<d>" for "documents/doc/<d>/section/<s>"). Null for anything else,
    // which is never folded into another place.
    static String parentOf(String place) {
        String[] parts = place.split("/");
        if (parts.length == 4 && parts[0].equals("properties") && parts[2].equals("lots")) {
            return parts[0] + "/" + parts[1];
        }
        if (parts.length == 5 && parts[0].equals("documents") && parts[1].equals("doc") && parts[3].equals("section")) {
            return parts[0] + "/" + parts[1] + "/" + parts[2];
        }
        return null;
    }

    private static void lotAndProperty(List<String> places, String lotId, Parents parents) {
        String propertyId = lotId == null ? null : parents.lotProperty().get(lotId);
        add(places, property(propertyId));
        add(places, lot(propertyId, lotId));
    }

    // A person who signs in: their page in Roles and their own page
    private static void person(List<String> places, String agentId) {
        add(places, rolesPerson(agentId));
        add(places, self(agentId));
    }

    private static void add(List<String> places, String place) {
        if (place != null && !places.contains(place)) places.add(place);
    }

    private static String property(String propertyId) {
        return propertyId == null ? null : "properties/" + propertyId;
    }

    private static String lot(String propertyId, String lotId) {
        return propertyId == null || lotId == null ? null : "properties/" + propertyId + "/lots/" + lotId;
    }

    private static String rolesPerson(String agentId) {
        return agentId == null ? null : "roles/person/" + agentId;
    }

    private static String self(String agentId) {
        return agentId == null ? null : "self/" + agentId;
    }

    private static String role(String roleId) {
        return roleId == null ? null : "roles/role/" + roleId;
    }

    private static String doc(String templateId) {
        return templateId == null ? null : "documents/doc/" + templateId;
    }

    private static String section(String templateId, String sectionId) {
        return templateId == null || sectionId == null ? null : "documents/doc/" + templateId + "/section/" + sectionId;
    }

    private static String park(String propertyId, String templateId) {
        return propertyId == null || templateId == null ? null : "documents/" + propertyId + "/doc/" + templateId;
    }

    // The ids ChangeRelay must look up for these notices, by kind
    static Set<String> lotIdsNeeded(Collection<Notice> notices, Map<String, String> homeLot) {
        Set<String> ids = new LinkedHashSet<>();
        for (Notice n : notices) {
            if (n.table().equals("mobile_home_secured_party")) {
                String lotId = homeLot.get(n.id("mobile_home_id"));
                if (lotId != null) ids.add(lotId);
            } else if (!n.table().equals("lot") && n.id("lot_id") != null) {
                ids.add(n.id("lot_id"));
            }
        }
        return ids;
    }
}
