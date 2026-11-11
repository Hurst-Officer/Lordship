package io.github.lordship.meters.internal;

import com.jayway.jsonpath.JsonPath;
import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
public class MeterControllerIT extends IntegrationTest {

    @Value("${lordship.root.email}")
    private String rootEmail;

    @Value("${lordship.root.password}")
    private String rootPassword;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    private String root() throws Exception {
        return TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
    }

    private UUID create(String token, String url, String json) throws Exception {
        MvcResult result = mockMvc.perform(post(url)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        // a read answers with its interval, everything else with the record itself
        return UUID.fromString(body.contains("\"read\"") ? JsonPath.read(body, "$.read.uuid") : JsonPath.read(body, "$.uuid"));
    }

    private UUID waterSubmeter(String token, UUID propertyId) throws Exception {
        return create(token, "/api/meters/submeters", """
                { "propertyId": "%s", "utilityType": "WATER", "unit": "GAL", "multiplier": "10" }
                """.formatted(propertyId));
    }

    @Test
    void theTree_needsASignIn() throws Exception {
        mockMvc.perform(get("/api/meters").param("propertyId", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aMainSitsOnTheProperty_andASubmeterCanFeedTwoLots() throws Exception {
        String token = root();
        UUID propertyId = testData.insertProperty("MT1").uuid();
        UUID lot1 = testData.insertLot(propertyId, "1").uuid();
        UUID lot2 = testData.insertLot(propertyId, "2").uuid();

        UUID main = create(token, "/api/meters/mains", """
                { "propertyId": "%s", "utilityType": "WATER", "unit": "CCF", "title": "City main", "provider": "City" }
                """.formatted(propertyId));
        UUID sub = waterSubmeter(token, propertyId);
        create(token, "/api/meters/submeters/" + sub + "/main", """
                { "mainId": "%s", "effectiveFrom": "2026-01-01" }
                """.formatted(main));
        create(token, "/api/meters/submeters/" + sub + "/lots", """
                { "lotId": "%s", "effectiveFrom": "2026-01-01" }
                """.formatted(lot1));
        create(token, "/api/meters/submeters/" + sub + "/lots", """
                { "lotId": "%s", "effectiveFrom": "2026-01-01" }
                """.formatted(lot2));

        mockMvc.perform(get("/api/meters").param("propertyId", propertyId.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mains[0].title").value("City main"))
                .andExpect(jsonPath("$.settings.length()").value(2))
                .andExpect(jsonPath("$.submeters.length()").value(1))
                .andExpect(jsonPath("$.mainLinks.length()").value(1))
                .andExpect(jsonPath("$.lotLinks.length()").value(2));
    }

    @Test
    void aLot_hasOneWaterSubmeterAtATime_soLinkingAnotherMovesIt() throws Exception {
        String token = root();
        UUID propertyId = testData.insertProperty("MT7").uuid();
        UUID lot = testData.insertLot(propertyId, "1").uuid();
        UUID first = waterSubmeter(token, propertyId);
        UUID second = waterSubmeter(token, propertyId);
        create(token, "/api/meters/submeters/" + first + "/lots", """
                { "lotId": "%s", "effectiveFrom": "2026-01-01" }
                """.formatted(lot));
        create(token, "/api/meters/submeters/" + second + "/lots", """
                { "lotId": "%s", "effectiveFrom": "2026-06-01" }
                """.formatted(lot));

        mockMvc.perform(get("/api/meters").param("propertyId", propertyId.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lotLinks.length()").value(2))
                .andExpect(jsonPath("$.lotLinks[0].effectiveTo").value("2026-06-01"));
    }

    @Test
    void aPowerUnitOnAWaterMeter_isRefused() throws Exception {
        UUID propertyId = testData.insertProperty("MT2").uuid();
        mockMvc.perform(post("/api/meters/submeters")
                        .header("Authorization", "Bearer " + root())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "propertyId": "%s", "utilityType": "WATER", "unit": "KWH" }
                                """.formatted(propertyId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("meter.unit_does_not_suit"));
    }

    @Test
    void aLotFromAnotherPark_isRefused() throws Exception {
        String token = root();
        UUID propertyId = testData.insertProperty("MT3").uuid();
        UUID otherLot = testData.insertChainToLot().uuid();
        UUID sub = waterSubmeter(token, propertyId);

        mockMvc.perform(post("/api/meters/submeters/" + sub + "/lots")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "lotId": "%s", "effectiveFrom": "2026-01-01" }
                                """.formatted(otherLot)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("submeter.lot_not_in_property"));
    }

    @Test
    void aReadThatRanBackwards_isSavedAndFlagged_andASecondReadThatDay_isRefused() throws Exception {
        String token = root();
        UUID propertyId = testData.insertProperty("MT4").uuid();
        UUID sub = waterSubmeter(token, propertyId);

        create(token, "/api/meter-reads", """
                { "submeterId": "%s", "readOn": "2026-09-15", "reading": 1200 }
                """.formatted(sub));
        mockMvc.perform(post("/api/meter-reads")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "submeterId": "%s", "readOn": "2026-10-15", "reading": 1100 }
                                """.formatted(sub)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flags[0]").value("BACKWARDS"))
                .andExpect(jsonPath("$.usage").isEmpty());

        mockMvc.perform(post("/api/meter-reads")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "submeterId": "%s", "readOn": "2026-10-15", "reading": 1300 }
                                """.formatted(sub)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("meter_read.day_taken"));
    }

    @Test
    void aParkOutsideTheAgentsScope_looksLikeNothing() throws Exception {
        String rootToken = root();
        UUID propertyId = testData.insertProperty("MT5").uuid();
        UUID sub = waterSubmeter(rootToken, propertyId);
        var agent = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "meters:view", "meters:edit");

        mockMvc.perform(get("/api/meters").param("propertyId", propertyId.toString())
                        .header("Authorization", "Bearer " + agent.token()))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/meters/submeters/" + sub)
                        .header("Authorization", "Bearer " + agent.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"title\": \"Moved\" }"))
                .andExpect(status().isNotFound());
    }

    @Test
    void enteringReads_needsItsOwnPermission() throws Exception {
        String rootToken = root();
        UUID propertyId = testData.insertProperty("MT6").uuid();
        UUID sub = waterSubmeter(rootToken, propertyId);
        var agent = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "meters:view", "meters:edit");

        mockMvc.perform(post("/api/meter-reads")
                        .header("Authorization", "Bearer " + agent.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "submeterId": "%s", "readOn": "2026-10-15", "reading": 1 }
                                """.formatted(sub)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theGrid_showsEachMetersReadsBetweenTwoDays_countedFromTheReadBefore() throws Exception {
        String token = root();
        UUID propertyId = testData.insertProperty("MT8").uuid();
        UUID sub = waterSubmeter(token, propertyId);
        UUID main = testData.insertMain(propertyId).uuid();
        create(token, "/api/meter-reads", """
                { "submeterId": "%s", "readOn": "2026-08-15", "reading": 1000 }
                """.formatted(sub));
        create(token, "/api/meter-reads", """
                { "submeterId": "%s", "readOn": "2026-09-15", "reading": 1200 }
                """.formatted(sub));
        create(token, "/api/meter-reads", """
                { "submeterId": "%s", "readOn": "2026-10-15", "reading": 1350 }
                """.formatted(sub));
        create(token, "/api/meter-reads", """
                { "mainId": "%s", "readOn": "2026-09-10", "reading": 4410 }
                """.formatted(main));

        // September only: the submeter's September read counts from the August read (200 on the dial, in tens of gallons),
        // the October read is left out, and the main's only read has no earlier read.
        // The submeter comes first because the submeter has the earliest read.
        mockMvc.perform(get("/api/meter-reads/grid")
                        .param("propertyId", propertyId.toString())
                        .param("from", "2026-09-01")
                        .param("to", "2026-09-30")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].submeterId").value(sub.toString()))
                .andExpect(jsonPath("$[0].reads.length()").value(1))
                .andExpect(jsonPath("$[0].reads[0].read.readOn").value("2026-09-15"))
                .andExpect(jsonPath("$[0].reads[0].previousReading").value(1000))
                .andExpect(jsonPath("$[0].reads[0].baseUsage").value("2000.000000"))
                .andExpect(jsonPath("$[1].mainId").value(main.toString()))
                .andExpect(jsonPath("$[1].reads[0].flags[0]").value("NO_PREVIOUS"));
    }

    @Test
    void aReadLeftAboveACorrectedDial_stillTakesANote_andANewNumberIsHeldToTheDial() throws Exception {
        String token = root();
        UUID propertyId = testData.insertProperty("MT9").uuid();
        UUID sub = waterSubmeter(token, propertyId);
        UUID read = create(token, "/api/meter-reads", """
                { "submeterId": "%s", "readOn": "2026-09-15", "reading": 50000 }
                """.formatted(sub));
        // the dial turns out to stop at 9,999 from 1 September on, which leaves the saved read above the dial
        create(token, "/api/meters/settings", """
                { "submeterId": "%s", "effectiveFrom": "2026-09-01", "dialMax": 9999 }
                """.formatted(sub));

        mockMvc.perform(patch("/api/meter-reads/" + read)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"notes\": \"checked on site\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read.notes").value("checked on site"))
                .andExpect(jsonPath("$.flags[0]").value("ABOVE_DIAL"));

        mockMvc.perform(patch("/api/meter-reads/" + read)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"reading\": 50001 }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("meter_read.above_dial"));
    }
}
