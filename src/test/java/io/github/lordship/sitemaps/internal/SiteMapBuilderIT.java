package io.github.lordship.sitemaps.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import io.github.lordship.TestAuthSupport.TestAgent;
import io.github.lordship.properties.internal.PropertyRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The map builder: GET and POST /api/sitemaps/{propertyId}/builder, the saved drawing, and the search box.
@Transactional
public class SiteMapBuilderIT extends IntegrationTest {

    // A 1 x 1 white PNG, standing in for a plat drawing
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4//8/AAX+Av4N70a4AAAAAElFTkSuQmCC");

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

    // One lot as the builder sends it: a square on the drawing, and a small square near Lakewood, WA.
    // offset moves it east so lots do not sit on top of each other.
    private static String lot(String number, int offset) {
        double west = -122.5 + offset * 0.0002;
        double east = west + 0.0001;
        return """
                { "lotNumber": "%s",
                  "vertices": [[%d, 0], [%d, 0], [%d, 50], [%d, 50]],
                  "outline": [[%s, 47.1], [%s, 47.1], [%s, 47.1001], [%s, 47.1001]] }
                """.formatted(number, offset * 60, offset * 60 + 50, offset * 60 + 50, offset * 60,
                west, east, east, west);
    }

    private static String map(String... lots) {
        return """
                { "platWidth": 400, "platHeight": 300, "platFileName": "plat.png",
                  "imagery": "Esri World Imagery",
                  "imageToWorld": { "model": "affine", "pixel_to_3857": [0.1, 0, -13638000, 0, -0.1, 5958000] },
                  "lots": [%s] }
                """.formatted(String.join(",", lots));
    }

    private MockMultipartHttpServletRequestBuilder save(UUID propertyId, String body, boolean withDrawing) {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/sitemaps/{id}/builder", propertyId);
        request.file(new MockMultipartFile("commit", "", "application/json", body.getBytes()));
        if (withDrawing) {
            request.file(new MockMultipartFile("plat", "plat.png", "image/png", PNG));
        }
        return request;
    }

    private int outlines(UUID propertyId) {
        return jdbc.sql("SELECT count(*) FROM lot_outline WHERE property_id = :p")
                .param("p", propertyId).query(Integer.class).single();
    }

    @Test
    void save_addsNewLotNumbersAndPlacesEveryLot() throws Exception {
        PropertyRow park = testData.insertProperty("MB");
        testData.insertLot(park.uuid(), "1");

        mockMvc.perform(save(park.uuid(), map(lot("1", 0), lot("2", 1)), true)
                        .header("Authorization", "Bearer " + rootToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placed").value(2))
                .andExpect(jsonPath("$.created", containsInAnyOrder("2")))
                .andExpect(jsonPath("$.unplaced", hasSize(0)))
                .andExpect(jsonPath("$.totalSquareFeet").isNumber());

        assertEquals(2, outlines(park.uuid()));
        String shape = jdbc.sql("""
                        SELECT shape_data ->> 'vertices' FROM lot
                         WHERE property_id = :p AND lot_number = '2' AND deleted_at IS NULL
                        """)
                .param("p", park.uuid()).query(String.class).single();
        assertEquals("[[60.0, 0.0], [110.0, 0.0], [110.0, 50.0], [60.0, 50.0]]", shape);
    }

    @Test
    void open_returnsThePlacementTheLotsAndTheDrawing() throws Exception {
        PropertyRow park = testData.insertProperty("MB");
        testData.insertLot(park.uuid(), "1");
        testData.insertLot(park.uuid(), "9");
        String token = rootToken();

        mockMvc.perform(get("/api/sitemaps/{id}/builder", park.uuid()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.propertyCode").value("MB"))
                .andExpect(jsonPath("$.placement").value(nullValue()))
                .andExpect(jsonPath("$.imagery.tiles").isString())
                .andExpect(jsonPath("$.lots[*].lotNumber", hasItems("1", "9")));

        mockMvc.perform(save(park.uuid(), map(lot("1", 0)), true).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unplaced", containsInAnyOrder("9")));

        mockMvc.perform(get("/api/sitemaps/{id}/builder", park.uuid()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placement.platWidth").value(400))
                .andExpect(jsonPath("$.placement.hasDrawing").value(true))
                .andExpect(jsonPath("$.placement.imageToWorld.model").value("affine"))
                .andExpect(jsonPath("$.lots[?(@.lotNumber == '1')].outline[0][1]", hasItem(47.1)))
                .andExpect(jsonPath("$.lots[?(@.lotNumber == '1')].vertices[1][0]", hasItem(50.0)))
                .andExpect(jsonPath("$.lots[?(@.lotNumber == '9')].outline", hasItem(nullValue())));

        mockMvc.perform(get("/api/sitemaps/{id}/builder/plat", park.uuid()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(PNG));
    }

    @Test
    void saveWithoutADrawing_keepsTheLastOne() throws Exception {
        PropertyRow park = testData.insertProperty("MB");
        String token = rootToken();

        mockMvc.perform(save(park.uuid(), map(lot("1", 0)), true).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(save(park.uuid(), map(lot("1", 0), lot("2", 1)), false).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/sitemaps/{id}/builder/plat", park.uuid()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG));
        assertEquals(2, outlines(park.uuid()));
    }

    @Test
    void lotsLeftOffTheMap_loseTheirOutlineButStay() throws Exception {
        PropertyRow park = testData.insertProperty("MB");
        String token = rootToken();
        mockMvc.perform(save(park.uuid(), map(lot("1", 0), lot("2", 1)), true).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(save(park.uuid(), map(lot("1", 0)), false).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unplaced", containsInAnyOrder("2")));

        assertEquals(1, outlines(park.uuid()));
        Integer live = jdbc.sql("SELECT count(*) FROM lot WHERE property_id = :p AND deleted_at IS NULL")
                .param("p", park.uuid()).query(Integer.class).single();
        assertEquals(2, live);
    }

    @Test
    void lotNumbersAreMatchedLoosely() throws Exception {
        PropertyRow park = testData.insertProperty("MB");
        testData.insertLot(park.uuid(), "Club House");

        mockMvc.perform(save(park.uuid(), map(lot("CLUBHOUSE", 0)), true)
                        .header("Authorization", "Bearer " + rootToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created", hasSize(0)));
    }

    @Test
    void save_listsEveryProblemAtOnce() throws Exception {
        PropertyRow park = testData.insertProperty("MB");
        // The corners of lot 7 are in the wrong order, so its edges cross
        String bowTie = """
                { "lotNumber": "7",
                  "vertices": [[0, 0], [50, 50], [50, 0], [0, 50]],
                  "outline": [[-122.5, 47.1], [-122.4999, 47.1001], [-122.4999, 47.1], [-122.5, 47.1001]] }
                """;

        mockMvc.perform(save(park.uuid(), map(lot("12,", 0), lot("Club House", 1), lot("CLUBHOUSE", 2), bowTie), true)
                        .header("Authorization", "Bearer " + rootToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("sitemap.not_ready"))
                .andExpect(jsonPath("$.problems[*].code", containsInAnyOrder(
                        "sitemap.lot_number_invalid",
                        "sitemap.lot_numbers_repeat",
                        "sitemap.lot_outline_crosses_itself")));

        assertEquals(0, outlines(park.uuid()));
    }

    @Test
    void save_writesOneAuditEntryForTheMap() throws Exception {
        PropertyRow park = testData.insertProperty("MB");
        mockMvc.perform(save(park.uuid(), map(lot("1", 0), lot("2", 1)), true)
                        .header("Authorization", "Bearer " + rootToken()))
                .andExpect(status().isOk());

        String after = jdbc.sql("""
                        SELECT value_after FROM audit_log
                         WHERE table_name = 'site_map' AND record_id = :p
                        """)
                .param("p", park.uuid().toString()).query(String.class).single();
        assertTrue(after.contains("\"lotsOnMap\":2"), after);
        assertTrue(after.contains("\"lotsAdded\""), after);
    }

    @Test
    void geocode_readsTypedCoordinates() throws Exception {
        mockMvc.perform(get("/api/sitemaps/geocode").param("q", "47.1258, -122.5381")
                        .header("Authorization", "Bearer " + rootToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.latitude").value(47.1258))
                .andExpect(jsonPath("$.longitude").value(-122.5381));
    }

    @Test
    void builder_needsSitemapsEdit() throws Exception {
        PropertyRow park = testData.insertProperty("MB");
        TestAgent agent = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken(), "properties:view");

        mockMvc.perform(get("/api/sitemaps/{id}/builder", park.uuid()).header("Authorization", "Bearer " + agent.token()))
                .andExpect(status().isForbidden());
        mockMvc.perform(save(park.uuid(), map(lot("1", 0)), true).header("Authorization", "Bearer " + agent.token()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/sitemaps/geocode").param("q", "Lakewood, WA")
                        .header("Authorization", "Bearer " + agent.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void officeStaff_canBuildMaps() {
        Integer granted = jdbc.sql("""
                        SELECT count(*) FROM role_permission rp
                          JOIN agent_role r ON r.uuid = rp.role_id
                          JOIN permission p ON p.uuid = rp.permission_id
                         WHERE r.role_name = 'Office Staff' AND p.permission_name = 'sitemaps:edit'
                        """)
                .query(Integer.class).single();
        assertEquals(1, granted);
    }
}
