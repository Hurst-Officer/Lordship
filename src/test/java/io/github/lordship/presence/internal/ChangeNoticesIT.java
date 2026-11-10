package io.github.lordship.presence.internal;

import io.github.lordship.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

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
 * The database tells Lordship about every committed change to a table Glade shows (V16), and Lordship tells the
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
}
