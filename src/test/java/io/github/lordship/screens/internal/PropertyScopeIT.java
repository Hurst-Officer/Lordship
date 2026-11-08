package io.github.lordship.screens.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import io.github.lordship.TestAuthSupport.TestAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// An agent without assignments:assign-all only sees the properties they are assigned to.
// Anything in another property is treated as if it did not exist: 404, left out of lists,
// or lot.unknown inside a selection.
@Transactional
public class PropertyScopeIT extends IntegrationTest {

    @Value("${lordship.root.email}")
    private String rootEmail;

    @Value("${lordship.root.password}")
    private String rootPassword;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    private String rootToken;
    private TestAgent manager;
    private String token;
    private UUID assignedId;
    private UUID otherId;
    private UUID assignedLotId;
    private UUID otherLotId;

    @BeforeEach
    void setUp() throws Exception {
        rootToken = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        manager = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken,
                "properties:view", "properties:create", "lots:view", "lots:edit", "homes:view", "audit:view",
                "assignments:assign");

        assignedId = testData.insertProperty("SA").uuid();
        otherId = testData.insertProperty("SB").uuid();
        assignedLotId = testData.insertLot(assignedId, "1").uuid();
        otherLotId = testData.insertLot(otherId, "1").uuid();

        mockMvc.perform(post("/api/property-assignments/assign")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "agentId": "%s", "propertyId": "%s" }
                                """.formatted(manager.uuid(), assignedId)))
                .andExpect(status().isCreated());
        // the agent's scope is read again on their next request
        authorizationCache.invalidateAll();

        token = manager.token();
    }

    @Test
    void propertyList_hasOnlyTheAssignedProperty() throws Exception {
        mockMvc.perform(get("/api/properties").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(assignedId.toString())))
                .andExpect(jsonPath("$[*].id", not(hasItem(otherId.toString()))));
    }

    @Test
    void otherProperty_is404() throws Exception {
        mockMvc.perform(get("/api/properties/" + assignedId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/properties/" + otherId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void lotInOtherProperty_is404_andLeftOutOfItsList() throws Exception {
        mockMvc.perform(get("/api/lots/" + assignedLotId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/lots/" + otherLotId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/lots").param("propertyCode", "SB").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void selectionWithALotInOtherProperty_isRefusedAsUnknown() throws Exception {
        mockMvc.perform(put("/api/lots/rentable")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "lotIds": ["%s", "%s"], "isRentable": true }
                                """.formatted(assignedLotId, otherLotId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("lot.unknown"));
    }

    @Test
    void screensAndHistoryOfOtherProperty_are404() throws Exception {
        mockMvc.perform(get("/api/screens/property/" + assignedId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/screens/property/" + otherId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/screens/lot/" + otherLotId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/auditlogs/record/property/" + otherId)
                        .param("field", "propertyName")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/auditlogs/record/lot/" + otherLotId)
                        .param("field", "lotNumber")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void assigningOtherProperty_is404_soNoOneWidensTheirOwnAccess() throws Exception {
        mockMvc.perform(post("/api/property-assignments/assign")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "agentId": "%s", "propertyId": "%s" }
                                """.formatted(manager.uuid(), otherId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void newProperty_isAssignedToItsCreator() throws Exception {
        String body = mockMvc.perform(post("/api/properties/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "propertyName": "Scope Creek", "propertyStreet": "1 Creek Rd",
                                  "propertyCity": "Testville", "propertyState": "WA", "propertyZip": "98000" }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String newId = objectMapper.readTree(body).get("id").asString();

        mockMvc.perform(get("/api/properties/" + newId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void lotHistory_needsLotsView() throws Exception {
        TestAgent noLots = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken,
                "properties:view", "audit:view");
        mockMvc.perform(get("/api/auditlogs/record/lot/" + assignedLotId)
                        .param("field", "lotNumber")
                        .header("Authorization", "Bearer " + noLots.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void historyOfAnUnknownTable_saysWhy() throws Exception {
        mockMvc.perform(get("/api/auditlogs/record/agent_session/" + assignedId)
                        .param("field", "workEmail")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("audit.table_unknown"))
                .andExpect(jsonPath("$.field").value("table"));
    }

    @Test
    void mapOfOtherProperty_is404() throws Exception {
        mockMvc.perform(get("/api/sitemaps/" + otherId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/sitemaps/" + otherId + "/terrain").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
