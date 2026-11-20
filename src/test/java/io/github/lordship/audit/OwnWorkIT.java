package io.github.lordship.audit;

import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import io.github.lordship.TestAuthSupport.TestAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Own recent work (OwnWork): whoever added a record may delete and restore that record for a few days without
// the delete permission. Tried on lots (and once on a property), through the endpoints Glade uses.
// No worker here holds a delete permission.
@Transactional
public class OwnWorkIT extends IntegrationTest {

    // A worker who can add lots and sees every property, but cannot delete lots
    private static final String[] CAN_ADD_LOTS =
            { "properties:view", "lots:view", "lots:create", "lots:edit", "assignments:assign-all" };

    @Value("${lordship.root.email}")
    private String rootEmail;

    @Value("${lordship.root.password}")
    private String rootPassword;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcClient jdbc;

    private String rootToken;
    private UUID propertyId;

    @BeforeEach
    void signInAndAddAProperty() throws Exception {
        rootToken = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        propertyId = testData.insertProperty("OW").uuid();
    }

    private TestAgent worker(String... permissions) throws Exception {
        return TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, permissions);
    }

    // Adds a lot over HTTP, so the audit log names the agent who added the lot
    private UUID addLot(String token, String lotNumber) throws Exception {
        return addLot(token, propertyId, lotNumber);
    }

    private UUID addLot(String token, UUID toProperty, String lotNumber) throws Exception {
        MvcResult added = mockMvc.perform(post("/api/lots")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "propertyId": "%s", "lotNumber": "%s" }
                                """.formatted(toProperty, lotNumber)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(added.getResponse().getContentAsString()).get("id").asString());
    }

    private ResultActions deleteLots(String token, UUID lotId) throws Exception {
        return mockMvc.perform(post("/api/lots/delete")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lotIds\": [\"" + lotId + "\"]}"));
    }

    private ResultActions restoreLots(String token, UUID lotId) throws Exception {
        return mockMvc.perform(post("/api/lots/restore")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lotIds\": [\"" + lotId + "\"]}"));
    }

    private boolean isDeleted(UUID lotId) {
        return jdbc.sql("SELECT deleted_at IS NOT NULL FROM lot WHERE uuid = :id")
                .param("id", lotId)
                .query(Boolean.class)
                .single();
    }

    @Test
    void makerInsideTheWindow_deletesAndRestoresTheirOwnLot() throws Exception {
        // Arrange
        TestAgent maker = worker(CAN_ADD_LOTS);
        UUID lotId = addLot(maker.token(), "1");
        UUID secondLotId = addLot(maker.token(), "2");

        // Assert: the property screen marks both lots as the maker's to take back
        mockMvc.perform(get("/api/screens/property/{id}", propertyId)
                        .header("Authorization", "Bearer " + maker.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mine.lot.length()").value(2));

        // Act + Assert: the list endpoint Glade's undo uses
        deleteLots(maker.token(), lotId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1));
        assertTrue(isDeleted(lotId));

        // Assert: the deleted list shows the maker the maker's own deleted lot
        mockMvc.perform(get("/api/lots/deleted")
                        .param("propertyId", propertyId.toString())
                        .header("Authorization", "Bearer " + maker.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(lotId.toString()));

        // Act + Assert: the restore (a redo of the add)
        restoreLots(maker.token(), lotId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1));
        assertFalse(isDeleted(lotId));

        // Act + Assert: the single delete
        mockMvc.perform(delete("/api/lots/{id}", secondLotId)
                        .header("Authorization", "Bearer " + maker.token()))
                .andExpect(status().isNoContent());
        assertTrue(isDeleted(secondLotId));
    }

    @Test
    void makerOutsideTheWindow_isRefusedWithACode() throws Exception {
        // Arrange: the lot was added eight days ago
        TestAgent maker = worker(CAN_ADD_LOTS);
        UUID lotId = addLot(maker.token(), "1");
        jdbc.sql("""
                        UPDATE audit_log SET changed_at = now() - interval '8 days'
                         WHERE table_name = 'lot' AND record_id = :id
                        """)
                .param("id", lotId.toString())
                .update();

        // Act + Assert
        deleteLots(maker.token(), lotId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("own_work.window_passed"));
        assertFalse(isDeleted(lotId));
    }

    @Test
    void anotherAgent_isForbidden() throws Exception {
        // Arrange
        TestAgent maker = worker(CAN_ADD_LOTS);
        TestAgent other = worker(CAN_ADD_LOTS);
        UUID lotId = addLot(maker.token(), "1");

        // Act + Assert
        deleteLots(other.token(), lotId)
                .andExpect(status().isForbidden());
        assertFalse(isDeleted(lotId));
    }

    @Test
    void makerWhoLostTheCreatePermission_isForbidden() throws Exception {
        // Arrange: lots:create is taken from the maker's role after the lot was added
        TestAgent maker = worker(CAN_ADD_LOTS);
        UUID lotId = addLot(maker.token(), "1");
        jdbc.sql("""
                        DELETE FROM role_permission
                         WHERE role_id = :role
                           AND permission_id = (SELECT uuid FROM permission WHERE permission_name = 'lots:create')
                        """)
                .param("role", maker.roleId())
                .update();
        authorizationCache.invalidateAll();

        // Act + Assert
        deleteLots(maker.token(), lotId)
                .andExpect(status().isForbidden());
        assertFalse(isDeleted(lotId));
    }

    @Test
    void makerWhoseLotIsOutOfScope_isForbidden() throws Exception {
        // Arrange: a maker who sees only assigned properties adds a lot, then loses the property
        TestAgent maker = worker("lots:view", "lots:create");
        jdbc.sql("""
                        INSERT INTO agent_property_assignment (agent_id, property_id, assigned_by)
                        VALUES (:agent, :property, :agent)
                        """)
                .param("agent", maker.uuid())
                .param("property", propertyId)
                .update();
        authorizationCache.invalidateAll();
        UUID lotId = addLot(maker.token(), "1");

        jdbc.sql("UPDATE agent_property_assignment SET removed_at = now() WHERE agent_id = :agent")
                .param("agent", maker.uuid())
                .update();
        authorizationCache.invalidateAll();

        // Act + Assert
        deleteLots(maker.token(), lotId)
                .andExpect(status().isForbidden());
        assertFalse(isDeleted(lotId));
    }

    @Test
    void makerWhoseLotSomeoneElseChanged_isRefusedWithACode() throws Exception {
        // Arrange: a coworker edits the lot after the maker added the lot
        TestAgent maker = worker(CAN_ADD_LOTS);
        UUID lotId = addLot(maker.token(), "1");
        mockMvc.perform(patch("/api/lots/{id}", lotId)
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\": \"Corner lot\"}"))
                .andExpect(status().isOk());

        // Act + Assert
        deleteLots(maker.token(), lotId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("own_work.built_on"));
        assertFalse(isDeleted(lotId));
    }

    @Test
    void makerCannotRestoreALotSomeoneElseDeleted() throws Exception {
        // Arrange: a coworker with lots:delete deletes the maker's lot
        TestAgent maker = worker(CAN_ADD_LOTS);
        UUID lotId = addLot(maker.token(), "1");
        deleteLots(rootToken, lotId)
                .andExpect(status().isOk());

        // Act + Assert
        restoreLots(maker.token(), lotId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("own_work.not_deleted_by_you"));
        assertTrue(isDeleted(lotId));

        // Assert: the coworker's delete is not in the maker's deleted list
        mockMvc.perform(get("/api/lots/deleted")
                        .param("propertyId", propertyId.toString())
                        .header("Authorization", "Bearer " + maker.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void makerWhoseLotCarriesSomeoneElsesHome_isRefusedWithACode() throws Exception {
        // Arrange: a coworker puts a home on the lot after the maker added the lot
        TestAgent maker = worker(CAN_ADD_LOTS);
        UUID lotId = addLot(maker.token(), "1");
        mockMvc.perform(post("/api/homes")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lotId\": \"" + lotId + "\"}"))
                .andExpect(status().isCreated());

        // Act + Assert
        deleteLots(maker.token(), lotId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("own_work.built_on"));
        assertFalse(isDeleted(lotId));
    }

    @Test
    void makerOfAProperty_isRefusedOnceSomeoneElseWorkedOnOneOfTheLots() throws Exception {
        // Arrange: a worker who can add properties and lots, and delete neither, adds a property with a lot
        TestAgent maker = worker("properties:view", "properties:create", "lots:view", "lots:create",
                "assignments:assign-all");
        MvcResult added = mockMvc.perform(post("/api/properties/create")
                        .header("Authorization", "Bearer " + maker.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "propertyName": "Own Work Park", "propertyStreet": "1 Main St",
                                  "propertyCity": "Port Orchard", "propertyState": "WA", "propertyZip": "98366" }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID ownPropertyId = UUID.fromString(
                objectMapper.readTree(added.getResponse().getContentAsString()).get("id").asString());
        UUID lotId = addLot(maker.token(), ownPropertyId, "1");

        // Assert: while only the maker has worked on the property, the property is the maker's to take back
        mockMvc.perform(get("/api/screens/property/{id}", ownPropertyId)
                        .header("Authorization", "Bearer " + maker.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mine.property.length()").value(1));

        // Arrange: a coworker edits the maker's lot. The property itself has no row by anyone else.
        mockMvc.perform(patch("/api/lots/{id}", lotId)
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\": \"Corner lot\"}"))
                .andExpect(status().isOk());

        // Act + Assert: the coworker's work is under the property, so the property stays
        mockMvc.perform(delete("/api/properties/{id}", ownPropertyId)
                        .header("Authorization", "Bearer " + maker.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("own_work.built_on"));
    }
}
