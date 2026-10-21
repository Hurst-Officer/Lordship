package io.github.lordship;

import io.github.lordship.TestAuthSupport.TestAgent;
import io.github.lordship.access.PermissionText;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Sheet 3: every permission has a group, English text and Spanish text.
@Transactional
public class PermissionTextIT extends IntegrationTest {

    private static final String ENGLISH_FILE = "messages.properties";
    private static final String SPANISH_FILE = "messages_es.properties";

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

    private String rootToken() throws Exception {
        return TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
    }

    // Read one file on its own. Spring would fill a missing Spanish entry with English.
    private static Properties bundle(String fileName) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(
                new ClassPathResource(fileName).getInputStream(), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private List<String> seededPermissions() {
        return jdbc.sql("SELECT permission_name FROM permission ORDER BY permission_name")
                .query(String.class)
                .list();
    }

    private List<String> seededGroups() {
        return jdbc.sql("SELECT group_key FROM permission_group ORDER BY sort_order")
                .query(String.class)
                .list();
    }

    // Adds "file: key" to missing when the key is absent or blank in that file.
    private static void requireText(Properties file, String fileName, String key, List<String> missing) {
        String text = file.getProperty(key);
        if (text == null || text.isBlank()) {
            missing.add(fileName + ": " + key);
        }
    }

    private static Set<String> permissionKeys(Properties file) {
        Set<String> keys = new TreeSet<>();
        for (String key : file.stringPropertyNames()) {
            if (key.startsWith("permission.") || key.startsWith("permission_group.")) {
                keys.add(key);
            }
        }
        return keys;
    }

    // ---- seed and bundles ------------------------------------------------------

    @Test
    void everySeededPermission_hasAGroup() {
        List<String> withoutGroup = jdbc.sql("""
                SELECT p.permission_name
                FROM permission p
                LEFT JOIN permission_group g ON g.uuid = p.group_id
                WHERE g.uuid IS NULL
                """)
                .query(String.class)
                .list();

        assertTrue(withoutGroup.isEmpty(), "permissions without a group: " + withoutGroup);
    }

    @Test
    void everySeededPermission_hasEnglishAndSpanishText() throws IOException {
        Properties english = bundle(ENGLISH_FILE);
        Properties spanish = bundle(SPANISH_FILE);
        List<String> missing = new ArrayList<>();

        for (String permission : seededPermissions()) {
            String key = PermissionText.permissionKey(permission);
            requireText(english, ENGLISH_FILE, key + ".label", missing);
            requireText(english, ENGLISH_FILE, key + ".description", missing);
            requireText(spanish, SPANISH_FILE, key + ".label", missing);
            requireText(spanish, SPANISH_FILE, key + ".description", missing);
        }

        assertTrue(missing.isEmpty(), "A permission is not finished until it has its text. Missing: " + missing);
    }

    @Test
    void everyGroup_hasAnEnglishAndASpanishLabel() throws IOException {
        Properties english = bundle(ENGLISH_FILE);
        Properties spanish = bundle(SPANISH_FILE);
        List<String> missing = new ArrayList<>();

        for (String group : seededGroups()) {
            String key = PermissionText.groupKey(group) + ".label";
            requireText(english, ENGLISH_FILE, key, missing);
            requireText(spanish, SPANISH_FILE, key, missing);
        }

        assertTrue(missing.isEmpty(), "Missing group labels: " + missing);
    }

    @Test
    void bothFiles_haveTheSamePermissionKeys() throws IOException {
        assertEquals(permissionKeys(bundle(ENGLISH_FILE)), permissionKeys(bundle(SPANISH_FILE)));
    }

    @Test
    void noTwoGroups_andNoTwoPermissionsInAGroup_shareASortOrder() {
        int groupClashes = jdbc.sql("""
                SELECT count(*) - count(DISTINCT sort_order) FROM permission_group
                """).query(Integer.class).single();
        int permissionClashes = jdbc.sql("""
                SELECT count(*) - count(DISTINCT (group_id, sort_order)) FROM permission
                """).query(Integer.class).single();

        assertEquals(0, groupClashes);
        assertEquals(0, permissionClashes);
    }

    // ---- GET /api/permissions --------------------------------------------------

    private ResultActions getPermissions(String token, String language) throws Exception {
        var request = get("/api/permissions").header("Authorization", "Bearer " + token);
        if (language != null) {
            request = request.header("Accept-Language", language);
        }
        return mockMvc.perform(request);
    }

    @Test
    void permissions_areSortedByGroup_andStartWithTheStaffDirectory() throws Exception {
        getPermissions(rootToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].permissionName").value("agents:view"))
                .andExpect(jsonPath("$[0].groupKey").value("staff_access"))
                .andExpect(jsonPath("$[-1:].groupKey", contains("system")));
    }

    @Test
    void permissions_areInEnglish_withNoHeader() throws Exception {
        getPermissions(rootToken(), null)
                .andExpect(jsonPath("$[0].label").value("See the staff directory"))
                .andExpect(jsonPath("$[0].groupLabel").value("Staff and access"))
                .andExpect(jsonPath("$[?(@.permissionName == 'pets:view')].description",
                        contains("Lets this role see pets.")));
    }

    @Test
    void permissions_areInEnglish_forEn() throws Exception {
        getPermissions(rootToken(), "en")
                .andExpect(jsonPath("$[0].label").value("See the staff directory"));
    }

    @Test
    void permissions_areInSpanish_forEs() throws Exception {
        getPermissions(rootToken(), "es")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].label").value("Ver el directorio del personal"))
                .andExpect(jsonPath("$[0].groupLabel").value("Personal y acceso"))
                .andExpect(jsonPath("$[?(@.permissionName == 'persons_ssn:view')].label",
                        contains("Ver números de Seguro Social")));
    }

    @Test
    void permissions_fallBackToEnglish_forAnUnknownLanguage() throws Exception {
        getPermissions(rootToken(), "fr")
                .andExpect(jsonPath("$[0].label").value("See the staff directory"));
    }

    // ---- GET /api/personnel/roles/{roleId} ---------------------------------------

    @Test
    void roleView_carriesTheSameText_inEnglishAndSpanish() throws Exception {
        TestAgent viewer = TestAuthSupport.agentWithPermissions(
                mockMvc, objectMapper, rootToken(), "role_permissions:view", "permissions:view");

        mockMvc.perform(get("/api/personnel/roles/{id}", viewer.roleId())
                        .header("Authorization", "Bearer " + viewer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[0].permissionName").value("agents:view"))
                .andExpect(jsonPath("$.permissions[0].label").value("See the staff directory"))
                .andExpect(jsonPath("$.permissions[0].groupKey").value("staff_access"))
                .andExpect(jsonPath("$.permissions[0].groupLabel").value("Staff and access"))
                .andExpect(jsonPath("$.permissions[?(@.permissionName == 'permissions:view')].granted",
                        contains(true)));

        mockMvc.perform(get("/api/personnel/roles/{id}", viewer.roleId())
                        .header("Authorization", "Bearer " + viewer.token())
                        .header("Accept-Language", "es"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[0].label").value("Ver el directorio del personal"))
                .andExpect(jsonPath("$.permissions[0].groupLabel").value("Personal y acceso"))
                .andExpect(jsonPath("$.permissions[0].description").value(
                        "Permite a este rol ver el nombre, correo y teléfono de cada agente."));
    }
}
