package io.github.lordship.meterbilling.internal;

import com.jayway.jsonpath.JsonPath;
import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
public class MeterBillsControllerIT extends IntegrationTest {

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

    private ResultActions send(String token, String url, String json) throws Exception {
        return mockMvc.perform(post(url)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private UUID newBill(String token, UUID propertyId, String periodStart, String periodEnd) throws Exception {
        String body = send(token, "/api/utility-bills", """
                { "propertyId": "%s", "provider": "City", "periodStart": "%s", "periodEnd": "%s", "printedTotal": "1848.44" }
                """.formatted(propertyId, periodStart, periodEnd))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.bill.uuid"));
    }

    @Test
    void bills_needASignIn() throws Exception {
        mockMvc.perform(get("/api/utility-bills").param("propertyId", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aBillsReadsBecomeTheMainsReads_andItsLinesAreCheckedAgainstTheTotal() throws Exception {
        String token = root();
        UUID propertyId = testData.insertProperty("UB1").uuid();
        UUID mainId = testData.insertMain(propertyId).uuid();
        UUID billId = newBill(token, propertyId, "2026-08-01", "2026-10-01");

        // 412 CCF between the reads, and 412 printed: they agree
        send(token, "/api/utility-bills/" + billId + "/meters", """
                { "mainId": "%s", "priorReadOn": "2026-08-01", "priorReading": 4410,
                  "currentReadOn": "2026-10-01", "currentReading": 4822, "printedUsage": "412", "printedUnit": "CCF" }
                """.formatted(mainId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.meters[0].usageMatches").value(true))
                .andExpect(jsonPath("$.meters[0].priorRead.source").value("PROVIDER"));

        send(token, "/api/utility-bills/" + billId + "/lines", """
                { "service": "WATER", "description": "Water usage", "amount": "1810.44", "printedRate": "4.394272",
                  "rateUnit": "CCF", "billingMonth": "2026-11-01" }
                """).andExpect(status().isCreated());
        send(token, "/api/utility-bills/" + billId + "/lines", """
                { "service": "WATER", "description": "Ambulance fee", "amount": "25.00", "billingMonth": "2026-11-01" }
                """).andExpect(status().isCreated());
        send(token, "/api/utility-bills/" + billId + "/lines", """
                { "service": "WATER", "description": "Late fee", "amount": "13.00", "passedOn": false, "billingMonth": "2026-11-20" }
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[2].billingMonth").value("2026-11-01"))
                .andExpect(jsonPath("$.linesTotal").value("1848.44"))
                .andExpect(jsonPath("$.passedOnTotal").value("1835.44"))
                .andExpect(jsonPath("$.totalMatches").value(true));

        mockMvc.perform(get("/api/utility-bills").param("propertyId", propertyId.toString())
                        .param("billingMonth", "2026-11-01")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void aBillsReads_andTheDaysBetweenThem_stayAsTheBillCountedThem() throws Exception {
        String token = root();
        UUID propertyId = testData.insertProperty("UB4").uuid();
        UUID mainId = testData.insertMain(propertyId).uuid();
        String body = send(token, "/api/utility-bills/" + newBill(token, propertyId, "2026-08-01", "2026-10-01") + "/meters", """
                { "mainId": "%s", "priorReadOn": "2026-08-01", "priorReading": 4410,
                  "currentReadOn": "2026-10-01", "currentReading": 4822 }
                """.formatted(mainId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String currentRead = JsonPath.read(body, "$.meters[0].currentRead.uuid");

        mockMvc.perform(patch("/api/meter-reads/" + currentRead)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"reading\": 5822 }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("meter_read.fixed_by_bill"));

        // a staff read between the bill's two reads would change its usage too
        send(token, "/api/meter-reads", """
                { "mainId": "%s", "readOn": "2026-09-01", "reading": 4400 }
                """.formatted(mainId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("meter_read.inside_bill"));
    }

    @Test
    void theNextBill_reusesTheLastBillsNewRead_butRefusesADifferentNumberForTheSameDay() throws Exception {
        String token = root();
        UUID propertyId = testData.insertProperty("UB2").uuid();
        UUID mainId = testData.insertMain(propertyId).uuid();

        send(token, "/api/utility-bills/" + newBill(token, propertyId, "2026-08-01", "2026-10-01") + "/meters", """
                { "mainId": "%s", "priorReadOn": "2026-08-01", "priorReading": 4410,
                  "currentReadOn": "2026-10-01", "currentReading": 4822 }
                """.formatted(mainId)).andExpect(status().isCreated());

        send(token, "/api/utility-bills/" + newBill(token, propertyId, "2026-10-01", "2026-11-01") + "/meters", """
                { "mainId": "%s", "priorReadOn": "2026-10-01", "priorReading": 4800,
                  "currentReadOn": "2026-11-01", "currentReading": 4990 }
                """.formatted(mainId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("meter_read.provider_differs"));
    }

    @Test
    void aNewMainsFirstBill_startsFromItsStartRead_andCountsARollover() throws Exception {
        String token = root();
        UUID propertyId = testData.insertProperty("UB6").uuid();
        UUID mainId = testData.insertMain(propertyId).uuid();

        // staff took the main's first read when it went in; the provider's first bill starts from that same number
        send(token, "/api/meter-reads", """
                { "mainId": "%s", "readOn": "2026-08-01", "reading": 99950, "kind": "START" }
                """.formatted(mainId)).andExpect(status().isCreated());

        // the dial (99,999 at most) passed its top during the bill: 50 up to the top, then 30 = 80 CCF
        send(token, "/api/utility-bills/" + newBill(token, propertyId, "2026-08-01", "2026-10-01") + "/meters", """
                { "mainId": "%s", "priorReadOn": "2026-08-01", "priorReading": 99950,
                  "currentReadOn": "2026-10-01", "currentReading": 30, "currentRollover": true }
                """.formatted(mainId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.meters[0].priorRead.kind").value("START"))
                .andExpect(jsonPath("$.meters[0].priorRead.source").value("STAFF"))
                .andExpect(jsonPath("$.meters[0].currentRead.kind").value("ROLLOVER"))
                .andExpect(jsonPath("$.meters[0].readsBaseUsage").value("59844.155844"))
                .andExpect(jsonPath("$.meters[0].baseUsage").value("59844.155844"));
    }

    @Test
    void readsThatRunBackwards_areRefusedOnABill_unlessItsUsageIsPrinted() throws Exception {
        String token = root();
        UUID propertyId = testData.insertProperty("UB7").uuid();
        UUID mainId = testData.insertMain(propertyId).uuid();

        send(token, "/api/utility-bills/" + newBill(token, propertyId, "2026-08-01", "2026-10-01") + "/meters", """
                { "mainId": "%s", "priorReadOn": "2026-08-01", "priorReading": 4822,
                  "currentReadOn": "2026-10-01", "currentReading": 4410 }
                """.formatted(mainId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("utility_bill.reads_not_countable"));
    }

    @Test
    void aRateGroupBilledOnAnotherRhythm_hasItsOwnCycle_andOtherGroupsFollowThePark() throws Exception {
        String token = root();
        UUID propertyId = testData.insertProperty("UB8").uuid();
        String county = JsonPath.read(send(token, "/api/meters/rate-groups", """
                { "propertyId": "%s", "name": "Phase 2 (County)" }
                """.formatted(propertyId)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.uuid");
        String annex = JsonPath.read(send(token, "/api/meters/rate-groups", """
                { "propertyId": "%s", "name": "Annex" }
                """.formatted(propertyId)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.uuid");

        // the city bills monthly; the county bills Phase 2 every 2 months, in even months
        send(token, "/api/utility-cycles", """
                { "propertyId": "%s", "service": "WATER", "cycleMonths": 1, "anchorMonth": 1, "lagMonths": 1,
                  "effectiveFrom": "2026-01-01" }
                """.formatted(propertyId)).andExpect(status().isCreated());
        send(token, "/api/utility-cycles", """
                { "propertyId": "%s", "rateGroupId": "%s", "service": "WATER", "cycleMonths": 2, "anchorMonth": 2,
                  "lagMonths": 1, "effectiveFrom": "2026-01-01" }
                """.formatted(propertyId, county))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rateGroupId").value(county));

        mockMvc.perform(get("/api/utility-cycles/proposed-month")
                        .param("propertyId", propertyId.toString()).param("service", "WATER").param("periodEnd", "2026-08-20")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.billingMonth").value("2026-09-01"));
        mockMvc.perform(get("/api/utility-cycles/proposed-month")
                        .param("propertyId", propertyId.toString()).param("rateGroupId", county)
                        .param("service", "WATER").param("periodEnd", "2026-08-20")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.billingMonth").value("2026-10-01"));
        // a group with no cycle of its own follows the park's
        mockMvc.perform(get("/api/utility-cycles/proposed-month")
                        .param("propertyId", propertyId.toString()).param("rateGroupId", annex)
                        .param("service", "WATER").param("periodEnd", "2026-08-20")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.billingMonth").value("2026-09-01"));

        // a bill entered under the county group takes the county's month for a line with none given
        String bill = JsonPath.read(send(token, "/api/utility-bills", """
                { "propertyId": "%s", "rateGroupId": "%s", "provider": "County", "periodStart": "2026-06-20",
                  "periodEnd": "2026-08-20" }
                """.formatted(propertyId, county)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.bill.uuid");
        send(token, "/api/utility-bills/" + bill + "/lines", """
                { "service": "WATER", "description": "Water", "amount": "100.00" }
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].billingMonth").value("2026-10-01"));
    }

    @Test
    void theSameBillEnteredTwice_isRefused_untilThePersonConfirms() throws Exception {
        String token = root();
        UUID propertyId = testData.insertProperty("UB5").uuid();
        newBill(token, propertyId, "2026-08-01", "2026-10-01");
        String again = """
                { "propertyId": "%s", "provider": "city ", "periodStart": "2026-08-01", "periodEnd": "2026-10-01"%s }
                """;

        send(token, "/api/utility-bills", again.formatted(propertyId, ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("utility_bill.possible_duplicate"));
        mockMvc.perform(get("/api/utility-bills/look-alikes")
                        .param("propertyId", propertyId.toString())
                        .param("periodStart", "2026-08-01").param("periodEnd", "2026-10-01").param("provider", "City")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        send(token, "/api/utility-bills", again.formatted(propertyId, ", \"allowDuplicate\": true"))
                .andExpect(status().isCreated());
    }

    @Test
    void theParksCycle_proposesEachLinesBillingMonth() throws Exception {
        String token = root();
        UUID propertyId = testData.insertProperty("UB3").uuid();

        // every 2 months, in even months, 1 month after the period ends
        send(token, "/api/utility-cycles", """
                { "propertyId": "%s", "service": "WATER", "cycleMonths": 2, "anchorMonth": 2, "lagMonths": 1,
                  "effectiveFrom": "2026-01-15" }
                """.formatted(propertyId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.effectiveFrom").value("2026-01-01"));

        mockMvc.perform(get("/api/utility-cycles/proposed-month")
                        .param("propertyId", propertyId.toString())
                        .param("service", "WATER")
                        .param("periodEnd", "2026-08-20")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billingMonth").value("2026-10-01"));

        // a line with no month takes the cycle's; sewer has no cycle here, so its month must be given
        UUID billId = newBill(token, propertyId, "2026-07-20", "2026-08-20");
        send(token, "/api/utility-bills/" + billId + "/lines", """
                { "service": "WATER", "description": "Water", "amount": "100.00" }
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].billingMonth").value("2026-10-01"));
        send(token, "/api/utility-bills/" + billId + "/lines", """
                { "service": "SEWER", "description": "Sewer", "amount": "100.00" }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("utility_bill.no_cycle"));
    }
}
