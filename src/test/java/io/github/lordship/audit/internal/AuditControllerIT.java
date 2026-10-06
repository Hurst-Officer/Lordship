package io.github.lordship.audit.internal;

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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.hamcrest.Matchers.nullValue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
public class AuditControllerIT extends IntegrationTest {

    @Value("${lordship.root.password}")
    private String rootPassword;

    @Value("${lordship.root.email}")
    private String rootEmail;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void shouldReturnMaskedAuditLog_WhenNewAgentChangesPersonInfo() throws Exception {
        // Arrange
        String rootAuthToken = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        String registerAgentBody = """
            {
                "nameFull": "Jimmy John",
                "workEmail": "JimmyJohn@lordship.com",
                "password": "jx341004rtrtrt"
            }
            """;

        MvcResult result1 = mockMvc.perform(post("/api/agents")
                .header("Authorization", "Bearer " + rootAuthToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerAgentBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workEmail").value("JimmyJohn@lordship.com"))
                .andExpect(jsonPath("$.nameFull").value("Jimmy John"))
                .andExpect(jsonPath("$.id").exists())
                .andReturn();

        String jimmyUuid = JsonPath.read(result1.getResponse().getContentAsString(), "$.id");
        assertFalse(jimmyUuid.isEmpty());
        String grantRoleForJimmy = String.format("""
                {
                    "agentId" : "%s",
                    "roleName" : "Property Manager"
                }
                """, jimmyUuid);

        mockMvc.perform(post("/api/granted-roles/grant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + rootAuthToken)
                        .content(grantRoleForJimmy))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.agentId").exists())
                .andExpect(jsonPath("$.roleId").exists())
                .andExpect(jsonPath("$.grantedBy").exists());

        String jimmyLogin = """
                {
                    "workEmail" : "JimmyJohn@lordship.com",
                    "password" : "jx341004rtrtrt"
                }
                """;

        MvcResult result3 = mockMvc.perform(post("/api/agents/auth")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jimmyLogin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workEmail").value("JimmyJohn@lordship.com"))
                .andExpect(jsonPath("$.nameFull").value("Jimmy John"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.token").exists())
                .andReturn();

        String jimmyUUID = JsonPath.read(result3.getResponse().getContentAsString(), "$.id");
        String jimmyToken = JsonPath.read(result3.getResponse().getContentAsString(), "$.token");
        assertFalse(jimmyToken.isEmpty());

        String jimmyCreatePerson = """
                {
                    "nameFull" : "Linda Belcher"
                }
                """;

        MvcResult result4 = mockMvc.perform(post("/api/persons/create")
                        .header("Authorization", "Bearer " + jimmyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                .content(jimmyCreatePerson))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.nameFull").value("Linda Belcher"))
                .andReturn();

        UUID lindaPersonId = UUID.fromString(JsonPath.read(result4.getResponse().getContentAsString(), "$.id"));

        String jimmyPatchesLinda1 =
                """
                    {
                        "birthday" : "1990-05-12"
                    }
               """;
        String jimmyPatchesLinda2 =
                """
                    {
                        "social" : "547-25-6371"
                    }
               """;
        String jimmyPatchesLinda3 =
                """
                    {
                        "personalEmail" : "lindaisAwesome@yahoo.com",
                        "personalPhone" : "360-211-4510"
                    }
               """;
        String jimmyPatchesLinda4 =
                """
                    {
                        "personalPhone" : null
                    }
               """;

        // Act
        MvcResult patch1Result = mockMvc.perform(patch("/api/persons/" + lindaPersonId)
                        .header("Authorization", "Bearer " + jimmyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jimmyPatchesLinda1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(lindaPersonId.toString()))
                .andExpect(jsonPath("$.birthday").value("1990-05-12"))
                .andReturn();

        MvcResult patch2Result = mockMvc.perform(patch("/api/persons/" + lindaPersonId)
                        .header("Authorization", "Bearer " + jimmyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jimmyPatchesLinda2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(lindaPersonId.toString()))
                .andExpect(jsonPath("$.social").value("***-**-6371"))
                .andReturn();

        MvcResult patch3Result = mockMvc.perform(patch("/api/persons/" + lindaPersonId)
                        .header("Authorization", "Bearer " + jimmyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jimmyPatchesLinda3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(lindaPersonId.toString()))
                .andExpect(jsonPath("$.personalEmail").value("lindaisAwesome@yahoo.com"))
                .andExpect(jsonPath("$.personalPhone").value("360-211-4510"))
                .andReturn();

        MvcResult patch4Result = mockMvc.perform(patch("/api/persons/" + lindaPersonId)
                        .header("Authorization", "Bearer " + jimmyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jimmyPatchesLinda4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(lindaPersonId.toString()))
                .andExpect(jsonPath("$.personalPhone").value(nullValue()))
                .andReturn();

        // Act
        MvcResult getLogsResult = mockMvc.perform(get("/api/auditlogs/byagent/" + jimmyUUID)
                        .header("Authorization", "Bearer " + jimmyToken)
                        .param("page", "0")
                        .param("pageSize", "10")
                        .param("sortBy", "changed_at")
                        .param("ascending", "true"))
        // Assert
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(5))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andReturn();

        System.out.println(getLogsResult.getResponse().getContentAsString());
        String json = getLogsResult.getResponse().getContentAsString();
        List<Map<String, Object>> logs = JsonPath.read(json, "$.content");

        // Insert
        assertEquals("INSERT", logs.getFirst().get("operation"));
        assertEquals("{\"nameFull\":\"Linda Belcher\"}", logs.getFirst().get("valueAfter"));

        // Update birthday
        assertEquals("UPDATE", logs.get(1).get("operation"));
        assertEquals("{\"birthday\":null}", logs.get(1).get("valueBefore"));
        assertEquals("{\"birthday\":\"1990-05-12\"}", logs.get(1).get("valueAfter"));

        // Update social (masked)
        assertEquals("UPDATE", logs.get(2).get("operation"));
        assertEquals("{\"social\":null}", logs.get(2).get("valueBefore"));
        assertEquals("{\"social\":\"***-**-6371\"}", logs.get(2).get("valueAfter"));

    // UPDATE personalEmail + personalPhone
        assertEquals("UPDATE", logs.get(3).get("operation"));
        assertEquals("{\"personalPhone\":null,\"personalEmail\":null}", logs.get(3).get("valueBefore"));
        assertEquals("{\"personalPhone\":\"360-211-4510\",\"personalEmail\":\"lindaisAwesome@yahoo.com\"}", logs.get(3).get("valueAfter"));

    // UPDATE personalPhone → null
        assertEquals("UPDATE", logs.get(4).get("operation"));
        assertEquals("{\"personalPhone\":\"360-211-4510\"}", logs.get(4).get("valueBefore"));
        assertEquals("{\"personalPhone\":null}", logs.get(4).get("valueAfter"));
    }
}
