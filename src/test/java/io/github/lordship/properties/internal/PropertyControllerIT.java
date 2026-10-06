package io.github.lordship.properties.internal;

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

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Transactional
public class PropertyControllerIT extends IntegrationTest {

    @Value("${lordship.root.email}")
    private String rootEmail;

    @Value("${lordship.root.password}")
    private String rootPassword;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    private String loginAsRoot() throws Exception {
        return TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
    }

    // ── Unauthorized tests ────────────────────────────────────

    @Test
    void createProperty_shouldReturn401_whenNoTokenProvided() throws Exception {
        mockMvc.perform(post("/api/properties/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "propertyName": "Test Mobile Park",
                                    "propertyStreet": "999 Test Ave",
                                    "propertyCity": "Testville",
                                    "propertyState": "WA",
                                    "propertyZip": "98000"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getProperty_shouldReturn401_whenNoTokenProvided() throws Exception {
        mockMvc.perform(get("/api/properties/TST01"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getAllProperties_shouldReturn401_whenNoTokenProvided() throws Exception {
        mockMvc.perform(get("/api/properties"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void patchProperty_shouldReturn401_whenNoTokenProvided() throws Exception {
        mockMvc.perform(patch("/api/properties/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "propertyName": "Updated Name" }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteProperty_shouldReturn401_whenNoTokenProvided() throws Exception {
        mockMvc.perform(delete("/api/properties/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    // ── Authorized tests ──────────────────────────────────────

    @Test
    void createProperty_shouldReturn201_withCorrectFields() throws Exception {
        String token = loginAsRoot();

        mockMvc.perform(post("/api/properties/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "propertyName": "Test Mobile Park",
                                    "propertyStreet": "999 Test Ave",
                                    "propertyCity": "Testville",
                                    "propertyState": "WA",
                                    "propertyZip": "98000"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.propertyCode").exists())
                .andExpect(jsonPath("$.propertyName").value("Test Mobile Park"))
                .andExpect(jsonPath("$.propertyStreet").value("999 Test Ave"))
                .andExpect(jsonPath("$.propertyCity").value("Testville"))
                .andExpect(jsonPath("$.propertyState").value("WA"))
                .andExpect(jsonPath("$.propertyZip").value("98000"))
                .andExpect(jsonPath("$.customFields").isEmpty())
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void createProperty_shouldReturn400_whenPropertyNameIsBlank() throws Exception {
        String token = loginAsRoot();

        mockMvc.perform(post("/api/properties/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "propertyName": "",
                                    "propertyStreet": "999 Test Ave",
                                    "propertyCity": "Testville",
                                    "propertyState": "WA",
                                    "propertyZip": "98000"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getProperty_withCorrectFields_shouldReturn200() throws Exception {
        String token = loginAsRoot();

        // Create first
        MvcResult createResult = mockMvc.perform(post("/api/properties/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "propertyName": "Test Mobile Park",
                                    "propertyStreet": "999 Test Ave",
                                    "propertyCity": "Testville",
                                    "propertyState": "WA",
                                    "propertyZip": "98000"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String propertyUuid = JsonPath.read(
                createResult.getResponse().getContentAsString(), "$.id");

        // Fetch by code
        mockMvc.perform(get("/api/properties/{propertyUuid}", propertyUuid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(propertyUuid))
                .andExpect(jsonPath("$.propertyName").value("Test Mobile Park"));
    }

    @Test
    void getProperty_whenPropertyDoesNotExist_shouldReturn404() throws Exception {
        String token = loginAsRoot();

        mockMvc.perform(get("/api/properties/019fc8cb-65fd-74f4-bc78-bfe53b4ea03d")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void getProperty_whenFormatIncorrect_shouldReturn400() throws Exception {
        // Arrange
        String token = loginAsRoot();

        // Act
        mockMvc.perform(get("/api/properties/doggy8cb-65fd")
                .header("Authorization", "Bearer " + token))
        // Assert
                .andExpect(status().isBadRequest());
    }

    @Test
    void getAllProperties_shouldReturn200_andIncludeCreatedProperty() throws Exception {
        String token = loginAsRoot();

        MvcResult createResult = mockMvc.perform(post("/api/properties/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "propertyName": "Test Mobile Park",
                                    "propertyStreet": "999 Test Ave",
                                    "propertyCity": "Testville",
                                    "propertyState": "WA",
                                    "propertyZip": "98000"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String propertyUuid = JsonPath.read(
                createResult.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/properties/" + propertyUuid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(propertyUuid));
    }

    private String createTestProperty() throws Exception {
        String token = loginAsRoot();

        MvcResult createResult = mockMvc.perform(post("/api/properties/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "propertyName": "Test Mobile Park",
                                    "propertyStreet": "999 Test Ave",
                                    "propertyCity": "Testville",
                                    "propertyState": "WA",
                                    "propertyZip": "98000"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        return JsonPath.read(
                createResult.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void patchProperty_shouldReturn200_andOnlyUpdateSentFields() throws Exception {
        // Arrange
        String token = loginAsRoot();
        String propertyUuid = createTestProperty();

        // Only patch the name — address should stay untouched
        mockMvc.perform(patch("/api/properties/{uuid}", propertyUuid)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "propertyName": "Updated Park Name",
                                    "propertyParcel": "10-48-0002-0015-00-3"
                                 }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.propertyName").value("Updated Park Name"))
                .andExpect(jsonPath("$.propertyStreet").value("999 Test Ave"));
    }

    @Test
    void patchProperty_shouldReturn404_whenPropertyDoesNotExist() throws Exception {
        String token = loginAsRoot();

        mockMvc.perform(patch("/api/properties/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "propertyName": "Updated Park Name" }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void patchProperty_shouldReturn200_whenPatchingAllPatchableFields() throws Exception {
        String token = loginAsRoot();
        String propertyUuid = createTestProperty();



        mockMvc.perform(patch("/api/properties/{uuid}", propertyUuid)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "propertyName": "Updated Park Name",
                                    "propertyStreet": "989 Test Ave",
                                    "propertyZip": "94123",
                                    "propertyState" : "WA",
                                    "propertyParcel" : "11-12-0001-0014-00-1",
                                    "propertyZoning" : "Residential"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.propertyName").value("Updated Park Name"))
                .andExpect(jsonPath("$.propertyZip").value("94123"))
                .andExpect(jsonPath("$.propertyState").value("WA"))
                .andExpect(jsonPath("$.propertyParcel").value("11-12-0001-0014-00-1"))
                .andExpect(jsonPath("$.propertyZoning").value("Residential"))
                .andExpect(jsonPath("$.propertyStreet").value("989 Test Ave"));
    }

    @Test
    void deleteProperty_shouldReturn204_andSubsequentGetReturns404() throws Exception {
        String token = loginAsRoot();

        MvcResult createResult = mockMvc.perform(post("/api/properties/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "propertyName": "Test Mobile Park",
                                    "propertyStreet": "999 Test Ave",
                                    "propertyCity": "Testville",
                                    "propertyState": "WA",
                                    "propertyZip": "98000"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String propertyUuid = JsonPath.read(
                createResult.getResponse().getContentAsString(), "$.id");

        // Delete
        mockMvc.perform(delete("/api/properties/{propertyUuid}", propertyUuid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // Confirm gone
        mockMvc.perform(get("/api/properties/{propertyUuid}", propertyUuid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void createProperty_shouldReturn400_whenCityIsMissing() throws Exception {
        String token = loginAsRoot();

        mockMvc.perform(post("/api/properties/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "propertyName": "Test Mobile Park",
                                    "propertyStreet": "999 Test Ave",
                                    "propertyState": "WA",
                                    "propertyZip": "98000"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createProperty_shouldReturn400_whenStateIsNotTwoCapitalLetters() throws Exception {
        String token = loginAsRoot();

        mockMvc.perform(post("/api/properties/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "propertyName": "Test Mobile Park",
                                    "propertyStreet": "999 Test Ave",
                                    "propertyCity": "Testville",
                                    "propertyState": "Washington",
                                    "propertyZip": "98000"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(not(containsString("{"))));
    }

    @Test
    void createProperty_shouldReturn400_whenZipIsMalformed() throws Exception {
        String token = loginAsRoot();

        mockMvc.perform(post("/api/properties/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "propertyName": "Test Mobile Park",
                                    "propertyStreet": "999 Test Ave",
                                    "propertyCity": "Testville",
                                    "propertyState": "WA",
                                    "propertyZip": "9800"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(not(containsString("{"))));
    }

    @Test
    void patchProperty_shouldSaveCustomFields() throws Exception {
        String token = loginAsRoot();
        String propertyUuid = createTestProperty();

        mockMvc.perform(patch("/api/properties/{uuid}", propertyUuid)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "customFields": { "Gate code": "4412", "Water shutoff": "Behind the office" } }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customFields['Gate code']").value("4412"))
                .andExpect(jsonPath("$.customFields['Water shutoff']").value("Behind the office"));

        mockMvc.perform(get("/api/properties/{uuid}", propertyUuid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customFields['Gate code']").value("4412"));
    }

    @Test
    void patchProperty_shouldReturn400_whenCustomFieldsIsNotAnObject() throws Exception {
        String token = loginAsRoot();
        String propertyUuid = createTestProperty();

        mockMvc.perform(patch("/api/properties/{uuid}", propertyUuid)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "customFields": ["Gate code"] }
                                """))
                .andExpect(status().isBadRequest());
    }
}
