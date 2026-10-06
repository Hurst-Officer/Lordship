package io.github.lordship.vehicles.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import io.github.lordship.lots.internal.LotRow;
import io.github.lordship.properties.internal.PropertyRow;
import io.github.lordship.tenancy.Tenancy;
import io.github.lordship.tenancy.internal.TenancyRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;

import java.util.Random;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@Transactional
public class VehicleControllerIT extends IntegrationTest {

    @Autowired
    MockMvc mockMvc;
    Random random = new Random();

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcClient jdbc;

    @Value("${lordship.root.email}")
    private String rootEmail;

    @Value("${lordship.root.password}")
    private String rootPassword;


    private Map<String, Object> buildVehicleRequest(UUID tenancyUuid) {
        return Map.of(
                "tenancyId",   tenancyUuid.toString(),
                "plateNumber",   Integer.toString(random.nextInt(999999))
        );
    }

    private String loginAsRoot() throws Exception {
        return TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
    }

    // ── Unauthorized tests ────────────────────────────────────

    @Test
    void unauthorizedRegisterReturns401() throws Exception {
        mockMvc.perform(post("/vehicles/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildVehicleRequest(UUID.randomUUID()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthorizedGetByIdReturns401() throws Exception {
        mockMvc.perform(get("/vehicles/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthorizedGetByTenancyReturns401() throws Exception {
        mockMvc.perform(get("/vehicles/bytenancy/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthorizedGetByPropertyReturns401() throws Exception {
        mockMvc.perform(get("/vehicles/byproperty/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthorizedPatchReturns401() throws Exception {
        mockMvc.perform(patch("/vehicles/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("color", "Red"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthorizedDeleteReturns401() throws Exception {
        mockMvc.perform(delete("/vehicles/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthorizedGetPolicyReturns401() throws Exception {
        mockMvc.perform(get("/vehicles/policy/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthorizedSetPolicyReturns401() throws Exception {
        mockMvc.perform(put("/vehicles/policy/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "freeVehicleLimit", 2,
                                "extraVehicleFee",  "25.00"
                        ))))
                .andExpect(status().isUnauthorized());
    }

    // ── Authorized tests ──────────────────────────────────────

    @Test
    void createVehicle_returns201_whenAuthorized() throws Exception {
        // Arrange
        String token = loginAsRoot();
        UUID tenancyUuid = testData.insertChainToTenancy().uuid();

        // Act
        mockMvc.perform(post("/api/vehicles/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildVehicleRequest(tenancyUuid))))
        // Assert
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vehicle.id").exists())
                .andExpect(jsonPath("$.vehicle.plateNumber").exists())
                .andExpect(jsonPath("$.plateConflictFlagged").value(false));
    }

    @Test
    void getById_returns200AndVehicle_whenAuthorized() throws Exception {
        // Arrange
        String token = loginAsRoot();
        UUID tenancyUuid = testData.insertChainToTenancy().uuid();

        String registerBody = mockMvc.perform(post("/api/vehicles/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildVehicleRequest(tenancyUuid))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String vehicleUuid = objectMapper.readTree(registerBody)
                .get("vehicle").get("id").asString();

        // Act
        mockMvc.perform(get("/api/vehicles/" + vehicleUuid)
                        .header("Authorization", "Bearer " + token))
        // Assert
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(vehicleUuid));
    }

    @Test
    void getByTenancy_whenAuthorized_returns200() throws Exception {
        String token = loginAsRoot();
        UUID tenancyUuid = testData.insertChainToTenancy().uuid();

        mockMvc.perform(post("/api/vehicles/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildVehicleRequest(tenancyUuid))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/vehicles/bytenancy/" + tenancyUuid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].plateNumber").exists());
    }

    @Test
    void getByProperty_whenAuthorized_returns200() throws Exception {
        String token = loginAsRoot();

        PropertyRow pr = testData.insertProperty("Test Property");
        LotRow lr = testData.insertLot(pr.uuid(), "1");
        TenancyRow tr = testData.insertTenancy(lr.uuid());


        mockMvc.perform(post("/api/vehicles/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildVehicleRequest(tr.uuid()))))
                .andDo(print())
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/vehicles/byproperty/" + pr.uuid())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void authorizedPatchReturns200() throws Exception {
        String token = loginAsRoot();
        UUID tenancyUuid = testData.insertChainToTenancy().uuid();

        String registerBody = mockMvc.perform(post("/api/vehicles/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildVehicleRequest(tenancyUuid))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String vehicleUuid = objectMapper.readTree(registerBody)
                .get("vehicle").get("id").asString();

        mockMvc.perform(patch("/api/vehicles/" + vehicleUuid)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("color", "Red"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.color").value("Red"));
    }

    @Test
    void authorizedDeleteReturns204() throws Exception {
        String token = loginAsRoot();
        UUID tenancyUuid = testData.insertChainToTenancy().uuid();

        String registerBody = mockMvc.perform(post("/api/vehicles/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildVehicleRequest(tenancyUuid))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String vehicleUuid = objectMapper.readTree(registerBody)
                .get("vehicle").get("id").asString();

        // Delete
        mockMvc.perform(delete("/api/vehicles/" + vehicleUuid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // Confirm gone
        mockMvc.perform(get("/api/vehicles/" + vehicleUuid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }


    @Test
    void registerFlagsPlateConflictAcrossTenancies() throws Exception {
        String token = loginAsRoot();
        UUID propertyUuid = testData.insertProperty("TP").uuid();

        UUID lotUuid1 = testData.insertLot(propertyUuid, "1").uuid();
        UUID tenancyUuid1 = testData.insertTenancy(lotUuid1).uuid();

        UUID lotUuid2 = testData.insertLot(propertyUuid, "2").uuid();
        UUID tenancyUuid2 = testData.insertTenancy(lotUuid2).uuid();

        // Use a fixed plate for both so the conflict is detectable
        Map<String, Object> tenancy1 = Map.of(
                "tenancyId",  tenancyUuid1.toString(),
                "plateNumber",  "999999"
        );

        Map<String, Object> tenancy2 = Map.of(
                "tenancyId",  tenancyUuid2.toString(),
                "plateNumber",  "999999"
        );

        // Register plate under tenancy 1
        mockMvc.perform(post("/api/vehicles/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tenancy1)))
                .andExpect(status().isCreated());


        // Register same plate under tenancy 2 — should flag conflict
        mockMvc.perform(post("/api/vehicles/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tenancy2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plateConflictFlagged").value(true))
                .andExpect(jsonPath("$.conflictingVehicles").isArray())
                .andExpect(jsonPath("$.conflictingVehicles[0].plateNumber").value("999999"));
    }
}