package io.github.lordship.persons.internal;


import com.jayway.jsonpath.JsonPath;
import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import io.github.lordship.access.Agent;
import io.github.lordship.access.AgentService;
import io.github.lordship.persons.Person;
import io.github.lordship.persons.PersonService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
public class PersonControllerIT extends IntegrationTest {

    @Value("${lordship.root.email}")
    private String rootEmail;

    @Value("${lordship.root.password}")
    private String rootPassword;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    AgentService agentService;

    @Autowired
    PersonService personService;

    @Test
    void createPerson_shouldReturn201_withCorrectFields() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        String requestBody = """
                {
                    "nameFull" : "Linda Belcher"
                }
                """;

        // Act
        mockMvc.perform(post("/api/persons/create")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        // Assert
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.nameFull").value("Linda Belcher"));
    }


    @Test
    void getPerson_shouldReturn404_whenPersonDoesNotExist() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID randomUuid = UUID.randomUUID();

        // Act
        mockMvc.perform(get("/api/persons/{uuid}", randomUuid)
                .header("Authorization", "Bearer " + token))
        // Assert
                .andExpect(status().isNotFound());
    }

    @Test
    void getPerson_shouldMaskSsn_whenUserLacksPersonsSsnViewPermission() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        String createPersonRequest = """
                {
                    "nameFull" : "Private Piggy"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/persons/create")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPersonRequest))
            .andExpect(status().isCreated())
            .andReturn();

        String personUuid = JsonPath.read(createResult.getResponse().getContentAsString(), "$.id");

        // set ssn
        mockMvc.perform(patch("/api/persons/{uuid}", personUuid)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "social" : "123-45-6789" }
                        """))
                .andExpect(status().isOk());

        // make an agent who by default doesn't have the view ssn permissions
        MvcResult registerResult = mockMvc.perform(post("/api/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token)
                .content("""
                        {
                            "nameFull" : "Tony Crook",
                            "workEmail" : "loser@lordship.com",
                            "password" : "iLoveMyMommy"
                        }
                        """))
                .andExpect(status().isCreated())
                .andReturn();

        // get Tony's id and grant him property manager permissions
        String newAgentUuid = JsonPath.read(registerResult.getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/api/granted-roles/grant")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format("""
                        {
                            "agentId" : "%s",
                            "roleName" : "Property Manager"
                        }
                        """, newAgentUuid)))
                .andExpect(status().isCreated());

        String newAgentToken = TestAuthSupport.loginAsAgent(mockMvc, objectMapper, "loser@lordship.com", "iLoveMyMommy");

        // Act
        mockMvc.perform(get("/api/persons/{uuid}", personUuid)
                .header("Authorization", "Bearer " + newAgentToken)
                .contentType(MediaType.APPLICATION_JSON))
        // Assert
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.social").value("***-**-6789"));
    }

    @Test
    void getPerson_shouldReturnPlaintextSsn_whenUserHasPersonsSsnViewPermission() throws Exception {
        // Arrange
        String rootToken = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);

        MvcResult mvcResult = mockMvc.perform(post("/api/persons/create")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + rootToken)
                .content("""
                        {
                            "nameFull" : "Don Social"
                        }
                        """))
                .andExpect(status().isCreated())
                .andReturn();

        String personUuid = JsonPath.read(mvcResult.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(patch("/api/persons/{uuid}", personUuid)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + rootToken)
                .content("""
                        {
                            "social" : "123-45-6789"
                        }
                        """))
                .andExpect(status().isOk());

        // Act
        mockMvc.perform(get("/api/persons/{uuid}", personUuid)
                .header("Authorization", "Bearer " + rootToken)
                .contentType(MediaType.APPLICATION_JSON))
        // Assert
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.social").value("123-45-6789"));
    }

    @Test
    void getPerson_shouldReturnUserOwnPersonRecord() throws Exception {
        // Arrange
        String rootToken = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        Optional<Agent> rootAgent = agentService.findByWorkEmail(rootEmail);
        assertTrue(rootAgent.isPresent());

        UUID rootPersonUuid = rootAgent.get().personId();

        // Act
        mockMvc.perform(get("/api/persons/self")
                .header("Authorization", "Bearer " + rootToken))
        // Assert
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(rootPersonUuid.toString()));
    }

    @Test
    void deletePerson_shouldReturn204_andSubsequentGetReturns404() throws Exception {
        // Arrange
        String rootToken = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        MvcResult mvcResult = mockMvc.perform(post("/api/persons/create")
                .header("Authorization", "Bearer " + rootToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "nameFull" : "Don Mock"
                        }
                        """))
                .andExpect(status().isCreated())
                .andReturn();

        String personUuid = JsonPath.read(mvcResult.getResponse().getContentAsString(), "$.id");

        // Act
        mockMvc.perform(delete("/api/persons/{uuid}", personUuid)
                .header("Authorization", "Bearer " + rootToken))
        // Assert
            .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/persons/{uuid}", personUuid)
                .header("Authorization", "Bearer " + rootToken))
           .andExpect(status().isNotFound());
    }

    @Test
    void getAnyPerson_shouldReturn401_whenNoTokenProvided() throws Exception {
        mockMvc.perform(get("/api/persons/{uuid}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }


    @Test
    public void patchPerson_shouldClearField_whenExplicitNullIsProvided() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        Optional<Agent> rootAgent = agentService.findByWorkEmail(rootEmail);
        assertTrue(rootAgent.isPresent());
        Optional<Person> personOptional = personService.findByID(rootAgent.get().personId());
        assertTrue(personOptional.isPresent());
        Person person = personOptional.get();

          // Step 1: set birthday
        String setBirthdayBody = """
            {
                "birthday": "1990-01-01"
            }
            """;

        mockMvc.perform(patch("/api/persons/{uuid}", person.uuid())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setBirthdayBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.birthday").value("1990-01-01"));

          // --- Step 2: now explicitly null it out
        String clearBirthdayBody = """
            {
                "birthday": null
            }
            """;

        // Act
        mockMvc.perform(patch("/api/persons/{uuid}", person.uuid())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clearBirthdayBody))
        // Assert
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.birthday").doesNotExist());

        // --- Step 3: verify against the actual DB-backed service too, not just the response DTO
        Person updated = personService.findByID(person.uuid()).orElseThrow();
        assertNull(updated.birthday());
    }
}
