package io.github.lordship.sitemaps.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import io.github.lordship.TestAuthSupport.TestAgent;
import io.github.lordship.lots.internal.LotRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The map screens: GET /api/sitemaps/footprints and GET /api/sitemaps/{propertyId}.
// The seed (V15) places CD on the map with 54 lot outlines.
@Transactional
public class SiteMapScreenIT extends IntegrationTest {

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

    private UUID cedrona() {
        return jdbc.sql("SELECT uuid FROM property WHERE property_code = 'CD' AND deleted_at IS NULL")
                .query(UUID.class)
                .single();
    }

    private Integer footprintLotCount(UUID propertyId) {
        return jdbc.sql("SELECT lot_count FROM property_footprint WHERE property_id = :id")
                .param("id", propertyId)
                .query(Integer.class)
                .optional()
                .orElse(null);
    }

    // A small square lot near Lakewood, WA
    private void insertOutline(UUID lotId, UUID propertyId) {
        jdbc.sql("""
                INSERT INTO lot_outline (lot_id, property_id, outline)
                VALUES (:lot, :property, postgis.ST_GeomFromText(
                    'POLYGON((-122.5 47.1, -122.4999 47.1, -122.4999 47.1001, -122.5 47.1001, -122.5 47.1))', 4326))
                """)
                .param("lot", lotId)
                .param("property", propertyId)
                .update();
    }

    @Test
    void footprints_includeTheSeededPark() throws Exception {
        mockMvc.perform(get("/api/sitemaps/footprints")
                        .header("Authorization", "Bearer " + rootToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].propertyId", hasItem(cedrona().toString())))
                .andExpect(jsonPath("$[?(@.propertyId == '%s')].lotCount".formatted(cedrona()), hasItem(54)))
                .andExpect(jsonPath("$[?(@.propertyId == '%s')].outline.type".formatted(cedrona()), hasItem("Polygon")));
    }

    @Test
    void addingAndRemovingALotShape_rebuildsTheFootprint() {
        LotRow lot = testData.insertChainToLot();
        assertEquals(null, footprintLotCount(lot.propertyId()));

        insertOutline(lot.uuid(), lot.propertyId());
        assertEquals(1, footprintLotCount(lot.propertyId()));

        jdbc.sql("DELETE FROM lot_outline WHERE lot_id = :lot").param("lot", lot.uuid()).update();
        assertEquals(null, footprintLotCount(lot.propertyId()));
    }

    @Test
    void siteMap_returnsLotsWithLabelsAndTheirSurroundings() throws Exception {
        mockMvc.perform(get("/api/sitemaps/{id}", cedrona())
                        .header("Authorization", "Bearer " + rootToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lots.length()").value(54))
                .andExpect(jsonPath("$.lots[0].outline.type").value("Polygon"))
                .andExpect(jsonPath("$.lots[0].lotNumber").exists())
                .andExpect(jsonPath("$.lots[0].labelLatitude").isNumber())
                .andExpect(jsonPath("$.features[*].kind", hasItem("road")));
    }

    @Test
    void siteMap_returns404_forAParkThatWasNeverPlaced() throws Exception {
        mockMvc.perform(get("/api/sitemaps/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + rootToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void maps_needPropertiesView() throws Exception {
        TestAgent agent = TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken());

        mockMvc.perform(get("/api/sitemaps/footprints")
                        .header("Authorization", "Bearer " + agent.token()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/sitemaps/{id}", cedrona())
                        .header("Authorization", "Bearer " + agent.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void propertyList_isAtTheRootPath() throws Exception {
        mockMvc.perform(get("/api/properties")
                        .header("Authorization", "Bearer " + rootToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].propertyCode", hasItem("CD")))
                .andExpect(jsonPath("$[?(@.propertyCode == 'CD')].latitude", hasItem(47.12581570581361)));
    }
}
