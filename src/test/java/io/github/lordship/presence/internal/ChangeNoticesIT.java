package io.github.lordship.presence.internal;

import io.github.lordship.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * The database tells Lordship about every committed change to a table Glade shows (V15), and Lordship tells the
 * open Glade windows which places changed (ChangeNotices, ChangeRelay, PresenceHub.changed).
 *
 * <p>Not @Transactional: a notice is only sent when a transaction commits. The rows these tests write are removed
 * again at the end of each test.
 */
public class ChangeNoticesIT extends IntegrationTest {

    // Tables V16 leaves out on purpose (see the migration)
    private static final List<String> NOT_WATCHED = List.of(
            "site_elevation", "property_footprint_stale", "map_feature_edit", "agent_session_spent_token");

    @MockitoSpyBean
    PresenceHub hub;

    @Autowired
    ChangeNotices notices;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    ObjectMapper objectMapper;

    private void awaitListening() throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (!notices.isListening() && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        assertTrue(notices.isListening(), "the change listener never started listening");
    }

    // The hub is told, within a few seconds, about places that pass the test
    private void hubHears(Predicate<Map<String, String>> places) {
        verify(hub, timeout(10_000).atLeastOnce()).changed(argThat(places::test));
    }

    @Test
    void everyTableOfAPropertyOrALot_isWatched() {
        // a table added later must call lordship_watch in its own migration
        List<String> unwatched = jdbc.sql("""
                        SELECT DISTINCT c.table_name
                        FROM information_schema.columns c
                        JOIN information_schema.tables t
                          ON t.table_schema = c.table_schema AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE'
                        WHERE c.table_schema = current_schema()
                          AND c.column_name IN ('property_id', 'lot_id')
                          AND c.table_name NOT IN (:excluded)
                          AND NOT EXISTS (
                              SELECT 1 FROM pg_trigger tr
                              JOIN pg_class cl ON cl.oid = tr.tgrelid
                              WHERE cl.relname = c.table_name AND tr.tgname = 'lordship_change'
                          )
                        ORDER BY 1
                        """)
                .param("excluded", NOT_WATCHED)
                .query(String.class)
                .list();

        assertEquals(List.of(), unwatched,
                "these tables belong to a property or a lot but tell Glade nothing: SELECT lordship_watch('<table>')");
    }

    @Test
    void aCommittedChange_reachesTheHub_asThePlacesThatShowIt() throws Exception {
        // Arrange
        awaitListening();
        String roleName = "Change Notice " + UUID.randomUUID().toString().substring(0, 8);

        // Act: written outside any transaction, so it commits at once
        UUID roleId = jdbc.sql("INSERT INTO agent_role (role_name) VALUES (:name) RETURNING uuid")
                .param("name", roleName)
                .query(UUID.class)
                .single();
        try {
            // Assert
            hubHears(places -> places.containsKey("roles/role/" + roleId) && places.containsKey("roles"));
        } finally {
            jdbc.sql("DELETE FROM agent_role WHERE uuid = :id").param("id", roleId).update();
        }
    }

    @Test
    void aBurstUnderOneProperty_isFoldedIntoTheProperty() throws Exception {
        // Arrange
        awaitListening();
        String propertyId = UUID.randomUUID().toString();

        // Act: 40 lot notices in one transaction, as a billing run over a park would send
        jdbc.sql("""
                        SELECT count(pg_notify('lordship_change', json_build_object(
                            't', 'lot',
                            'r', json_build_object('uuid', gen_random_uuid()::text, 'property_id', :property))::text))
                        FROM generate_series(1, 40)
                        """)
                .param("property", propertyId)
                .query(Long.class)
                .single();

        // Assert: one place for the park, none for its lots
        hubHears(places -> places.containsKey("properties/" + propertyId)
                && places.keySet().stream().noneMatch(place -> place.startsWith("properties/" + propertyId + "/lots/")));
    }

    // ---- the trigger itself (V16 lordship_notify_change): what one write to mobile_home sends ----

    // One park with two lots and a home on lot A, committed, for the trigger tests
    private record HomeOnLotA(UUID propertyId, UUID lotA, UUID lotB, UUID homeId) { }

    private HomeOnLotA homeOnLotA() {
        // a code of the park's own: property codes are unique, and these rows are committed
        UUID propertyId = testData.insertProperty("CN" + UUID.randomUUID().toString().substring(0, 8)).uuid();
        UUID lotA = testData.insertLot(propertyId, "A").uuid();
        UUID lotB = testData.insertLot(propertyId, "B").uuid();
        UUID homeId = jdbc.sql("INSERT INTO mobile_home (lot_id) VALUES (:lot) RETURNING uuid")
                .param("lot", lotA)
                .query(UUID.class)
                .single();
        return new HomeOnLotA(propertyId, lotA, lotB, homeId);
    }

    private void remove(HomeOnLotA park) {
        jdbc.sql("DELETE FROM mobile_home WHERE uuid = :id").param("id", park.homeId()).update();
        jdbc.sql("DELETE FROM lot WHERE property_id = :id").param("id", park.propertyId()).update();
        jdbc.sql("DELETE FROM property WHERE uuid = :id").param("id", park.propertyId()).update();
    }

    /**
     * The notices one write sends about one row, in the order Postgres sent them.
     * Read on a connection of the test's own that LISTENs the way ChangeNotices does, so the test sees the notices
     * themselves, not the places the hub makes of them. The write runs once LISTEN is in place.
     * Waits for the expected number of notices, then a moment more, so one notice too many is caught.
     * Notices about other rows are left out.
     */
    private List<JsonNode> noticesAbout(UUID rowId, int expected, Runnable write) throws Exception {
        try (Connection listener = dataSource.getConnection();
             Statement statement = listener.createStatement()) {
            listener.setAutoCommit(true);
            statement.execute("LISTEN " + ChangeNotices.CHANNEL);
            try {
                PgNotifications arriving = new PgNotifications(listener);

                write.run();

                List<JsonNode> about = new ArrayList<>();
                long until = System.currentTimeMillis() + 10_000;
                while (about.size() < expected && System.currentTimeMillis() < until) {
                    addAbout(rowId, arriving.poll(250), about);
                }
                addAbout(rowId, arriving.poll(500), about);
                return about;
            } finally {
                // the connection goes back to the pool, and must not keep collecting notices there
                statement.execute("UNLISTEN *");
            }
        }
    }

    private void addAbout(UUID rowId, List<String> payloads, List<JsonNode> about) {
        for (String payload : payloads) {
            if (payload.contains(rowId.toString())) {
                about.add(objectMapper.readTree(payload));
            }
        }
    }

    // The lot each notice names, "no lot" for a notice that carries no lot_id
    private static List<String> lotsIn(List<JsonNode> notices) {
        List<String> lots = new ArrayList<>();
        for (JsonNode notice : notices) {
            JsonNode ids = notice.get("r");
            lots.add(ids.has("lot_id") ? ids.get("lot_id").asString() : "no lot");
        }
        return lots;
    }

    @Test
    void anUpdateThatChangesNoId_sendsOneNotice() throws Exception {
        // Arrange
        HomeOnLotA park = homeOnLotA();
        try {
            // Act
            List<JsonNode> notices = noticesAbout(park.homeId(), 1, () ->
                    jdbc.sql("UPDATE mobile_home SET name = 'Unit 7' WHERE uuid = :id").param("id", park.homeId()).update());

            // Assert
            assertEquals(List.of(park.lotA().toString()), lotsIn(notices));
            assertEquals("mobile_home", notices.get(0).get("t").asString());
        } finally {
            remove(park);
        }
    }

    @Test
    void movingAHomeToAnotherLot_sendsTheNewLot_thenTheLotTheHomeLeft() throws Exception {
        // Arrange
        HomeOnLotA park = homeOnLotA();
        try {
            // Act
            List<JsonNode> notices = noticesAbout(park.homeId(), 2, () ->
                    jdbc.sql("UPDATE mobile_home SET lot_id = :lot WHERE uuid = :id")
                            .param("lot", park.lotB())
                            .param("id", park.homeId())
                            .update());

            // Assert: without the second notice, a window on lot A would never load again
            assertEquals(List.of(park.lotB().toString(), park.lotA().toString()), lotsIn(notices));
        } finally {
            remove(park);
        }
    }

    @Test
    void takingAHomeOffItsLot_stillSendsTheLotTheHomeLeft() throws Exception {
        // Arrange
        HomeOnLotA park = homeOnLotA();
        try {
            // Act
            List<JsonNode> notices = noticesAbout(park.homeId(), 2, () ->
                    jdbc.sql("UPDATE mobile_home SET lot_id = NULL WHERE uuid = :id").param("id", park.homeId()).update());

            // Assert: the first notice (the home as saved) names no lot, so only the second notice reaches lot A
            assertEquals(List.of("no lot", park.lotA().toString()), lotsIn(notices));
        } finally {
            remove(park);
        }
    }

    @Test
    void aDelete_sendsTheIdsTheRowHad() throws Exception {
        // Arrange
        HomeOnLotA park = homeOnLotA();
        try {
            // Act: a hard delete, which the screens never do but psql or a cleanup may
            List<JsonNode> notices = noticesAbout(park.homeId(), 1, () ->
                    jdbc.sql("DELETE FROM mobile_home WHERE uuid = :id").param("id", park.homeId()).update());

            // Assert
            assertEquals(List.of(park.lotA().toString()), lotsIn(notices));
        } finally {
            remove(park);
        }
    }
}
