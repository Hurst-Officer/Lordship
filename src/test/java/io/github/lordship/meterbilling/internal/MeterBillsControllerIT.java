package io.github.lordship.meterbilling.internal;

import com.jayway.jsonpath.JsonPath;
import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import io.github.lordship.meterbills.internal.MeterBillsCreateRequest;
import io.github.lordship.meters.MeterMeasurement;
import io.github.lordship.meters.MeterType;
import io.github.lordship.meters.internal.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
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


    private UUID createMeter(String token, UUID lotId, boolean isMaster) throws Exception {
        MeterCreateRequest req = new MeterCreateRequest(
                lotId,
                0.0,
                0.0,
                MeterType.WATER,
                MeterMeasurement.GAL,
                isMaster,
                99999,
                1.0,
                15,
                false
        );



        MvcResult result = mockMvc.perform(post("/meters/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private UUID createSecondMeter(String token, UUID lotId, boolean isMaster) throws Exception {
        MeterCreateRequest req = new MeterCreateRequest(
                lotId,
                0.0,
                0.0,
                MeterType.WATER,
                MeterMeasurement.GAL,
                isMaster,
                99999,
                1.0,
                15,
                true
        );



        MvcResult result = mockMvc.perform(post("/meters/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private void recordRead(String token, UUID meterUuid, int amount, OffsetDateTime readAt) throws Exception {
        MeterReadCreateRequest req = new MeterReadCreateRequest(
                amount,
                readAt,
                false,
                0
        );

        mockMvc.perform(post("/meters/{uuid}/reads", meterUuid)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }

    private void linkMeters(String token, UUID parentUuid, UUID childUuid, LocalDate effectiveFrom) throws Exception {
        MeterRelationCreateRequest req = new MeterRelationCreateRequest(
                parentUuid,
                childUuid,
                false,
                effectiveFrom
        );

        mockMvc.perform(post("/meters/relationships")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }

    private UUID createMeterBill(String token, UUID meterUuid, int billedAmount, double rateAmount,
                                 LocalDate periodStart, LocalDate periodEnd) throws Exception {

        MeterBillsCreateRequest req = new MeterBillsCreateRequest(
                meterUuid,
                BigDecimal.valueOf(billedAmount),
                BigDecimal.valueOf(rateAmount),
                MeterMeasurement.GAL,
                periodStart,
                periodEnd
        );

        MvcResult result = mockMvc.perform(post("/meterbills/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }



    @Test
    void createMeterBill_shouldReturn201_withCorrectFields() throws Exception {
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID propertyId = testData.insertProperty("AB").uuid();
        UUID lotId = testData.insertLot(propertyId, "5A").uuid();
        UUID meterUuid = createMeter(token, lotId, true);

        MeterBillsCreateRequest req = new MeterBillsCreateRequest(
                meterUuid,
                new BigDecimal("500"),
                new BigDecimal("0.0148"),
                MeterMeasurement.GAL,
                LocalDate.now().minusMonths(1),
                LocalDate.now()
        );

        mockMvc.perform(post("/meterbills/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }

    @Test
    void createMeterBill_shouldReturn401_whenNoTokenProvided() throws Exception {
        String body = """
                {
                    "billedMeterId": "%s", "billedAmount": 500, "rateAmount": 0.0148,
                    "rateUnit": "GAL", "periodStart": "2026-01-01", "periodEnd": "2026-02-01"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/meterbills/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getById_shouldReturn200_afterCreate() throws Exception {
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID propertyId = testData.insertProperty("AB").uuid();
        UUID lotId = testData.insertLot(propertyId, "5A").uuid();
        UUID meterUuid = createMeter(token, lotId, true);
        UUID billUuid = createMeterBill(token, meterUuid, 500, 0.0148,
                LocalDate.now().minusMonths(1), LocalDate.now());

        mockMvc.perform(get("/meterbills/{uuid}", billUuid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void getById_shouldReturn404_whenBillDoesNotExist() throws Exception {
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);

        mockMvc.perform(get("/meterbills/{uuid}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }


    @Test
    void patchMeterBill_shouldUpdateBilledAmount() throws Exception {
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID propertyId = testData.insertProperty("AB").uuid();
        UUID lotId = testData.insertLot(propertyId, "5A").uuid();
        UUID meterUuid = createMeter(token, lotId, true);
        UUID billUuid = createMeterBill(token, meterUuid, 500, 0.0148,
                LocalDate.now().minusMonths(1), LocalDate.now());

        mockMvc.perform(patch("/meterbills/{uuid}", billUuid)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"billedAmount\": 650 }"))
                .andExpect(status().isOk());
    }

    @Test
    void patchMeterBill_shouldReturn404_whenBillDoesNotExist() throws Exception {
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);

        mockMvc.perform(patch("/meterbills/{uuid}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"billedAmount\": 650 }"))
                .andExpect(status().isNotFound());
    }


    @Test
    void deleteMeterBill_shouldReturn204_andSubsequentGetReturns404() throws Exception {
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID propertyId = testData.insertProperty("AB").uuid();
        UUID lotId = testData.insertLot(propertyId, "5A").uuid();
        UUID meterUuid = createMeter(token, lotId, true);
        UUID billUuid = createMeterBill(token, meterUuid, 500, 0.0148,
                LocalDate.now().minusMonths(1), LocalDate.now());

        mockMvc.perform(delete("/meterbills/{uuid}", billUuid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/meterbills/{uuid}", billUuid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }


    @Test
    void calculateCharge_shouldReturn200_withCorrectCalculatedAmount() throws Exception {
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID propertyId = testData.insertProperty("AB").uuid();
        UUID lotId = testData.insertLot(propertyId, "5A").uuid();
        UUID lotId2 = testData.insertLot(propertyId, "5B").uuid();
        UUID parentUuid = createMeter(token, lotId, true);
        UUID childUuid = createSecondMeter(token, lotId2, false);

        LocalDate periodStart = LocalDate.now().minusMonths(1);
        LocalDate periodEnd = LocalDate.now();

        linkMeters(token, parentUuid, childUuid, periodStart);
        recordRead(token, childUuid, 1000, periodStart.atStartOfDay().atOffset(ZoneOffset.UTC));
        recordRead(token, childUuid, 1450, periodEnd.atStartOfDay().atOffset(ZoneOffset.UTC));
        createMeterBill(token, parentUuid, 500, 0.0148, periodStart, periodEnd);

        mockMvc.perform(get("/meterbills/{lotMeterId}/charge", childUuid)
                        .header("Authorization", "Bearer " + token)
                        .param("periodStart", periodStart.toString())
                        .param("periodEnd", periodEnd.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void calculateCharge_shouldReturn422_whenNoParentLinked() throws Exception {
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID propertyId = testData.insertProperty("AB").uuid();
        UUID lotId = testData.insertLot(propertyId, "5A").uuid();
        UUID orphanChild = createMeter(token, lotId, false);

        mockMvc.perform(get("/meterbills/{lotMeterId}/charge", orphanChild)
                        .header("Authorization", "Bearer " + token)
                        .param("periodStart", LocalDate.now().minusMonths(1).toString())
                        .param("periodEnd", LocalDate.now().toString()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void calculateCharge_shouldReturn404_whenNoRateOnFileForParent() throws Exception {
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID propertyId = testData.insertProperty("AB").uuid();
        UUID lotId = testData.insertLot(propertyId, "5A").uuid();
        UUID lotId2 = testData.insertLot(propertyId, "5B").uuid();
        UUID parentUuid = createMeter(token, lotId, true);
        UUID childUuid = createSecondMeter(token, lotId2, false);

        LocalDate periodStart = LocalDate.now().minusMonths(1);
        LocalDate periodEnd = LocalDate.now();

        linkMeters(token, parentUuid, childUuid, periodStart);
        recordRead(token, childUuid, 1000, periodStart.atStartOfDay().atOffset(ZoneOffset.UTC));
        recordRead(token, childUuid, 1450, periodEnd.atStartOfDay().atOffset(ZoneOffset.UTC));
        // meter-bill invoice will not be created as there is no rate

        mockMvc.perform(get("/meterbills/{lotMeterId}/charge", childUuid)
                        .header("Authorization", "Bearer " + token)
                        .param("periodStart", periodStart.toString())
                        .param("periodEnd", periodEnd.toString()))
                .andExpect(status().isNotFound());
    }
}