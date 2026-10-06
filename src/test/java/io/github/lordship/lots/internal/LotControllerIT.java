package io.github.lordship.lots.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
public class LotControllerIT extends IntegrationTest {

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


    @Test
    void createLot_shouldReturn401_whenUnauthorized() throws Exception {
        // Arrange
        String requestBody = """
                {
                    "propertyId" : "%s",
                    "lotNumber" : "12"
                }
                """.formatted(UUID.randomUUID().toString());

        // Act
        mockMvc.perform(post("/api/lots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                // Assert
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createLot_shouldReturn201_withMinimalFields() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID propertyId = testData.insertProperty("L001").uuid();

        String requestBody = """
                {
                    "propertyId" : "%s",
                    "lotNumber" : "12"
                }
                """.formatted(propertyId.toString());

        // Act
        mockMvc.perform(post("/api/lots")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                // Assert: only what was supplied (plus DB defaults) comes back -- everything
                // else is left for a follow-up PATCH, per the create-minimal design.
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.propertyId").value(propertyId.toString()))
                .andExpect(jsonPath("$.lotNumber").value("12"))
                .andExpect(jsonPath("$.isRentable").value(true))
                .andExpect(jsonPath("$.notRentableReason").doesNotExist())
                .andExpect(jsonPath("$.lotAddress").doesNotExist())
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.lotParcel").doesNotExist())
                .andExpect(jsonPath("$.notes").doesNotExist())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.deletedAt").doesNotExist())
                // The DB's default rectangle, since shape_data wasn't supplied at creation.
                .andExpect(jsonPath("$.shapeData.vertices.length()").value(4))
                .andExpect(jsonPath("$.permissibleAgreementTypes").isArray())
                .andExpect(jsonPath("$.permissibleAgreementTypes").isEmpty());
    }

    @Test
    void listByProperty_shouldReturn200_withLots() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID propertyId = testData.insertProperty("L002").uuid();
        testData.insertLot(propertyId, "7");

        // Act
        mockMvc.perform(get("/api/lots")
                        .param("propertyCode", "L002")
                        .header("Authorization", "Bearer " + token))
                // Assert
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].propertyId").value(propertyId.toString()))
                .andExpect(jsonPath("$[0].lotNumber").value("7"));
    }

    @Test
    void patchLot_shouldUpdateField_andReturn200() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID propertyId = testData.insertProperty("L004").uuid();
        UUID lotId = testData.insertLot(propertyId, "5").uuid();

        // Act
        mockMvc.perform(patch("/api/lots/" + lotId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "lotNumber": "99", "description": "Updated lot", "notes": "Front row" }
                                """))
                // Assert
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lotNumber").value("99"))
                .andExpect(jsonPath("$.description").value("Updated lot"))
                .andExpect(jsonPath("$.notes").value("Front row"));
    }

    @Test
    void patchLot_shouldClearField_whenExplicitNullProvided() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID propertyId = testData.insertProperty("L005").uuid();
        UUID lotId = testData.insertLot(propertyId, "6").uuid();

        // Act
        mockMvc.perform(patch("/api/lots/" + lotId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "notes": null }
                                """))
                // Assert
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").doesNotExist());
    }

    @Test
    void patchLot_shouldReturn404_whenNotFound() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);

        // Act
        mockMvc.perform(patch("/api/lots/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "lotNumber": "99" }
                                """))
                // Assert
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteLot_shouldReturn204_andSubsequentGetReturns404() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID propertyId = testData.insertProperty("L003").uuid();
        UUID lotId = testData.insertLot(propertyId, "9").uuid();

        // Act
        mockMvc.perform(delete("/api/lots/" + lotId)
                        .header("Authorization", "Bearer " + token))
                // Assert
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/lots/" + lotId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}