package io.github.lordship.presence.internal;

import io.github.lordship.presence.internal.ChangePlaces.Notice;
import io.github.lordship.presence.internal.ChangePlaces.Parents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A batch of database change notices (ChangeNotices) to every open Glade window: each notice becomes the places
 * that show its row (ChangePlaces), and PresenceHub tells the windows that may see them.
 *
 * <p>The parents a notice does not carry (a home's lot, a lot's property, a person's agent, a clause's document)
 * are read here, one query per kind for the whole batch. These read other modules' tables by name on purpose:
 * the notices themselves come from the database, below every module.
 *
 * <p>A changed agent or person also changes how that agent is shown on the roster (their name, and the color they
 * picked), so their open windows are given both again (PresenceHub.agentsChanged). A coworker's new color or new
 * name then shows at once, without anyone opening a new window.
 */
@Component
class ChangeRelay {

    private static final Logger log = LoggerFactory.getLogger(ChangeRelay.class);

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final PresenceHub hub;

    ChangeRelay(JdbcClient jdbc, ObjectMapper objectMapper, PresenceHub hub) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.hub = hub;
    }

    // One batch of notices, as Postgres sent them (V15 lordship_notify_change)
    void relay(List<String> payloads) {
        List<Notice> notices = new ArrayList<>();
        for (String payload : payloads) {
            parse(payload).ifPresent(notices::add);
        }
        if (notices.isEmpty()) return;

        Parents parents = parentsOf(notices);
        Map<String, String> tableByPlace = new LinkedHashMap<>();
        for (Notice notice : notices) {
            for (String place : ChangePlaces.placesOf(notice, parents)) {
                tableByPlace.putIfAbsent(place, notice.table());
            }
        }
        hub.changed(ChangePlaces.coalesced(tableByPlace));
        refreshAgents(notices, parents);
    }

    // The agents whose own row or whose person changed: the roster takes their name and color as they are now.
    // A failure here is only logged, because the places above were already told.
    private void refreshAgents(List<Notice> notices, Parents parents) {
        Set<String> agentIds = new LinkedHashSet<>(idsOf(notices, "agent", "uuid"));
        for (String personId : idsOf(notices, "person", "uuid")) {
            String agentId = parents.personAgent().get(personId);
            if (agentId != null) agentIds.add(agentId);
        }
        List<UUID> asked = uuids(agentIds);
        if (asked.isEmpty()) return;
        try {
            Map<UUID, PresenceHub.Shown> shown = new HashMap<>();
            jdbc.sql("SELECT a.uuid, p.name_full, a.color FROM agent a JOIN person p ON p.uuid = a.person_id WHERE a.uuid IN (:ids)")
                    .param("ids", asked)
                    .query((rs, i) -> {
                        String name = rs.getString(2);
                        shown.put(UUID.fromString(rs.getString(1)),
                                new PresenceHub.Shown(name == null || name.isBlank() ? PresenceHub.NO_NAME : name, rs.getString(3)));
                        return i;
                    })
                    .list();
            hub.agentsChanged(shown);
        } catch (RuntimeException e) {
            log.warn("The roster could not take {} changed agents ({})", asked.size(), e.toString());
        }
    }

    // Notices may have been missed (the listener was away): every window loads everything again
    void everything() {
        hub.changed(Map.of(ChangePlaces.EVERYTHING, ""));
    }

    // { "t": table, "r": { "uuid": ..., "property_id": ... } }. Empty for anything else.
    Optional<Notice> parse(String payload) {
        try {
            Map<?, ?> root = objectMapper.readValue(payload, Map.class);
            if (!(root.get("t") instanceof String table) || !(root.get("r") instanceof Map<?, ?> row)) {
                return Optional.empty();
            }
            Map<String, String> ids = new HashMap<>();
            row.forEach((key, value) -> {
                if (key instanceof String k && value instanceof String v) ids.put(k, v);
            });
            return Optional.of(new Notice(table, ids));
        } catch (RuntimeException e) {
            log.warn("A change notice could not be read: {}", payload);
            return Optional.empty();
        }
    }

    private Parents parentsOf(List<Notice> notices) {
        Map<String, String> homeLot = pairs("SELECT uuid, lot_id FROM mobile_home WHERE uuid IN (:ids)",
                idsOf(notices, "mobile_home_secured_party", "mobile_home_id"));
        Map<String, String> lotProperty = pairs("SELECT uuid, property_id FROM lot WHERE uuid IN (:ids)",
                ChangePlaces.lotIdsNeeded(notices, homeLot));
        Map<String, String> personAgent = pairs("SELECT person_id, uuid FROM agent WHERE person_id IN (:ids)",
                idsOf(notices, "person", "uuid"));
        Map<String, String> sectionTemplate = pairs("SELECT uuid, template FROM document_section WHERE uuid IN (:ids)",
                idsOf(notices, "template_clause", "section"));
        Map<String, String[]> assignments = new HashMap<>();
        List<UUID> assignmentIds = uuids(idsOf(notices, "property_document_customization", "assignment"));
        if (!assignmentIds.isEmpty()) {
            jdbc.sql("SELECT uuid, property, document_template FROM property_document_assignment WHERE uuid IN (:ids)")
                    .param("ids", assignmentIds)
                    .query((rs, i) -> assignments.put(rs.getString(1), new String[] { rs.getString(2), rs.getString(3) }))
                    .list();
        }
        return new Parents(lotProperty, homeLot, personAgent, sectionTemplate, assignments);
    }

    // The values of one id key, over the notices of one table
    private static Set<String> idsOf(List<Notice> notices, String table, String key) {
        Set<String> ids = new LinkedHashSet<>();
        for (Notice notice : notices) {
            if (notice.table().equals(table) && notice.id(key) != null) ids.add(notice.id(key));
        }
        return ids;
    }

    // child id -> parent id, from a query whose first column is the child and second the parent
    private Map<String, String> pairs(String sql, Set<String> ids) {
        List<UUID> asked = uuids(ids);
        Map<String, String> found = new HashMap<>();
        if (asked.isEmpty()) return found;
        jdbc.sql(sql)
                .param("ids", asked)
                .query((rs, i) -> {
                    if (rs.getString(2) != null) found.put(rs.getString(1), rs.getString(2));
                    return i;
                })
                .list();
        return found;
    }

    private static List<UUID> uuids(Set<String> ids) {
        return ids.stream().map(ChangeRelay::uuidOrNull).filter(Objects::nonNull).toList();
    }

    private static UUID uuidOrNull(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
