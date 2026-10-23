package io.github.lordship;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// A property's latitude and longitude, used to place it on the global map
@Transactional
public class PropertyLocationIT extends IntegrationTest {

    @Value("${lordship.root.email}")
    private String rootEmail;

    @Value("${lordship.root.password}")
    private String rootPassword;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    private String rootToken() throws Exception {
        return TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
    }

    private UUID createProperty(String token) throws Exception {
        String body = mockMvc.perform(post("/api/properties/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "propertyName": "Location Test Park", "propertyStreet": "1 Test Way",
                                  "propertyCity": "Lakewood", "propertyState": "WA", "propertyZip": "98439" }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asString());
    }

    private ResultActions patchProperty(String token, UUID id, String json) throws Exception {
        return mockMvc.perform(patch("/api/properties/{uuid}", id)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    @Test
    void aNewProperty_hasNoLocation() throws Exception {
        String token = rootToken();
        UUID id = createProperty(token);

        patchProperty(token, id, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(nullValue()))
                .andExpect(jsonPath("$.longitude").value(nullValue()));
    }

    @Test
    void patch_setsBothTogether() throws Exception {
        String token = rootToken();
        UUID id = createProperty(token);

        patchProperty(token, id, """
                { "latitude": 47.12581570581361, "longitude": -122.53809564877622 }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(47.12581570581361))
                .andExpect(jsonPath("$.longitude").value(-122.53809564877622));
    }

    @Test
    void patch_refusesOnlyOneOfThePair() throws Exception {
        String token = rootToken();
        UUID id = createProperty(token);

        patchProperty(token, id, """
                { "latitude": 47.1 }
                """)
                .andExpect(status().isBadRequest());
    }

    @Test
    void patch_refusesALatitudeOffTheGlobe() throws Exception {
        String token = rootToken();
        UUID id = createProperty(token);

        patchProperty(token, id, """
                { "latitude": 95, "longitude": -122.5 }
                """)
                .andExpect(status().isBadRequest());
    }

    @Test
    void patch_refusesText() throws Exception {
        String token = rootToken();
        UUID id = createProperty(token);

        patchProperty(token, id, """
                { "latitude": "north", "longitude": -122.5 }
                """)
                .andExpect(status().isBadRequest());
    }

    @Test
    void patch_canClearBoth() throws Exception {
        String token = rootToken();
        UUID id = createProperty(token);
        patchProperty(token, id, """
                { "latitude": 47.1, "longitude": -122.5 }
                """).andExpect(status().isOk());

        patchProperty(token, id, """
                { "latitude": null, "longitude": null }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(nullValue()));
    }
}
