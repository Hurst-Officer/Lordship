package io.github.lordship.sitemaps.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import io.github.lordship.properties.internal.PropertyRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Features mode: buildings, lights and roads changed by hand, and how those changes survive a pull
@Transactional
public class FeatureEditIT extends IntegrationTest {

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

    private static final String SQUARE_BUILDING = """
            { "type": "Polygon", "coordinates": [[[-122.49997, 47.10003], [-122.49993, 47.10003],
                                                  [-122.49993, 47.10007], [-122.49997, 47.10007]]] }
            """;

    private String token;

    private String rootToken() throws Exception {
        if (token == null) {
            token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        }
        return token;
    }

    // A park with lot 1 on the map, saved through the builder
    private UUID placedPark() throws Exception {
        return placedPark("FE");
    }

    private UUID placedPark(String code) throws Exception {
        PropertyRow park = testData.insertProperty(code);
        String map = """
                { "platWidth": 400, "platHeight": 300,
                  "imageToWorld": { "model": "affine", "pixel_to_3857": [0.1, 0, -13638000, 0, -0.1, 5958000] },
                  "lots": [ { "lotNumber": "1",
                              "vertices": [[0, 0], [50, 0], [50, 50], [0, 50]],
                              "outline": [[-122.5, 47.1], [-122.4999, 47.1], [-122.4999, 47.1001], [-122.5, 47.1001]] } ] }
                """;
        mockMvc.perform(multipart("/api/sitemaps/{id}/builder", park.uuid())
                        .file(new MockMultipartFile("commit", "", "application/json", map.getBytes()))
                        .header("Authorization", "Bearer " + rootToken()))
                .andExpect(status().isOk());
        return park.uuid();
    }

    // A pulled road and a pulled building, as a pull would have saved them
    private UUID pulled(UUID park, String kind, String name, String ref, String wkt, String details) {
        return jdbc.sql("""
                        INSERT INTO map_feature (property_id, kind, name, geom, source, source_ref, details)
                        VALUES (:p, :kind, :name, postgis.ST_GeomFromText(:wkt, 4326), 'osm', :ref, :details::jsonb)
                        RETURNING uuid
                        """)
                .param("p", park).param("kind", kind).param("name", name).param("ref", ref)
                .param("wkt", wkt).param("details", details)
                .query(UUID.class).single();
    }

    private String send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                        int expected, String body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request.header("Authorization", "Bearer " + rootToken()))
                .andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void aNewBuilding_isSavedWithItsTypeAndBecomesTheLotsHome() throws Exception {
        UUID park = placedPark();

        String created = send(post("/api/sitemaps/{id}/features", park), 201, """
                { "kind": "building", "name": "Clubhouse", "buildingType": "Clubhouse", "geometry": %s }
                """.formatted(SQUARE_BUILDING));
        UUID id = UUID.fromString(objectMapper.readTree(created).get("id").asString());

        String role = jdbc.sql("SELECT details ->> 'role' FROM map_feature WHERE uuid = :id")
                .param("id", id).query(String.class).optional().orElse(null);
        // A Clubhouse is never a lot's home; it is an outbuilding of the lot
        assertEquals("outbuilding", role);

        mockMvc.perform(get("/api/sitemaps/{id}/features", park).header("Authorization", "Bearer " + rootToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features[0].buildingType").value("Clubhouse"))
                .andExpect(jsonPath("$.features[0].pulled").value(false))
                .andExpect(jsonPath("$.features[0].geometry.type").value("Polygon"));
    }

    @Test
    void aBadShape_isRefused() throws Exception {
        UUID park = placedPark();
        String bowTie = """
                { "kind": "building", "geometry": { "type": "Polygon",
                  "coordinates": [[[-122.5, 47.1], [-122.4999, 47.1001], [-122.4999, 47.1], [-122.5, 47.1001]]] } }
                """;
        send(post("/api/sitemaps/{id}/features", park), 400, bowTie);
        send(post("/api/sitemaps/{id}/features", park), 400, """
                { "kind": "light", "geometry": { "type": "LineString", "coordinates": [[0, 0], [1, 1]] } }
                """);
        send(post("/api/sitemaps/{id}/features", park), 400, """
                { "kind": "road", "geometry": { "type": "LineString", "coordinates": [[0, 0], [1, 1]] } }
                """);
    }

    @Test
    void aParkWithNoSavedMap_cannotHaveFeatures() throws Exception {
        PropertyRow park = testData.insertProperty("FE3");
        send(post("/api/sitemaps/{id}/features", park.uuid()), 409, """
                { "kind": "light", "geometry": { "type": "Point", "coordinates": [-122.5, 47.1] } }
                """);
    }

    @Test
    void renamingARoad_canRenameEveryPieceAndIsKeptForTheNextPull() throws Exception {
        UUID park = placedPark();
        UUID first = pulled(park, "road", "Basin St", "osm:way/1", "LINESTRING(-122.5 47.1002, -122.4998 47.1002)", "{}");
        pulled(park, "road", "Basin St", "osm:way/2", "LINESTRING(-122.4998 47.1002, -122.4995 47.1002)", "{}");

        send(post("/api/sitemaps/{id}/features/{f}/rename", park, first), 200, """
                { "name": "Basin Street SW", "allPieces": true }
                """);

        Integer renamed = jdbc.sql("SELECT count(*) FROM map_feature WHERE property_id = :p AND name = 'Basin Street SW'")
                .param("p", park).query(Integer.class).single();
        assertEquals(2, renamed);
        Integer kept = jdbc.sql("SELECT count(*) FROM map_feature_edit WHERE property_id = :p AND name = 'Basin Street SW'")
                .param("p", park).query(Integer.class).single();
        assertEquals(2, kept);
    }

    @Test
    void reshapingAPulledBuilding_makesAHandMadeCopy_andDeletingTheCopyBringsTheOriginalBack() throws Exception {
        UUID park = placedPark();
        UUID original = pulled(park, "building", null, "osm:way/7",
                "POLYGON((-122.49997 47.10003, -122.49993 47.10003, -122.49993 47.10007, -122.49997 47.10003))", "{}");

        String shaped = send(post("/api/sitemaps/{id}/features/{f}/shape", park, original), 200, """
                { "geometry": %s, "buildingType": "Storage" }
                """.formatted(SQUARE_BUILDING));
        UUID copy = UUID.fromString(objectMapper.readTree(shaped).get("id").asString());

        String ref = jdbc.sql("SELECT source_ref FROM map_feature WHERE uuid = :id")
                .param("id", copy).query(String.class).single();
        assertEquals("replaces:osm:way/7", ref);
        Boolean hidden = jdbc.sql("SELECT hidden FROM map_feature_edit WHERE property_id = :p AND source_ref = 'osm:way/7'")
                .param("p", park).query(Boolean.class).single();
        assertEquals(true, hidden);

        String deleted = send(delete("/api/sitemaps/{id}/features/{f}", park, copy), 200, null);
        assertEquals("reverted", objectMapper.readTree(deleted).get("outcome").asString());
        Boolean stillHidden = jdbc.sql("SELECT hidden FROM map_feature_edit WHERE property_id = :p AND source_ref = 'osm:way/7'")
                .param("p", park).query(Boolean.class).optional().orElse(null);
        assertEquals(null, stillHidden);
    }

    @Test
    void deletingAPulledRoad_hidesIt_andUnhideBringsItBack() throws Exception {
        UUID park = placedPark();
        UUID road = pulled(park, "road", "Alley", "osm:way/3", "LINESTRING(-122.5 47.1002, -122.4998 47.1002)", "{}");

        String deleted = send(delete("/api/sitemaps/{id}/features/{f}", park, road), 200, null);
        assertEquals("hidden", objectMapper.readTree(deleted).get("outcome").asString());

        mockMvc.perform(get("/api/sitemaps/{id}/features", park).header("Authorization", "Bearer " + rootToken()))
                .andExpect(jsonPath("$.hidden").value(1));

        String unhidden = send(post("/api/sitemaps/{id}/features/unhide", park), 200, null);
        assertEquals(1, objectMapper.readTree(unhidden).get("broughtBack").asInt());
    }

    @Test
    void aRoadsWidth_isChangedOnAHandMadeCopy() throws Exception {
        UUID park = placedPark();
        UUID road = pulled(park, "road", "Alley", "osm:way/4", "LINESTRING(-122.5 47.1002, -122.4998 47.1002)",
                "{\"width_m\": 5}");

        send(post("/api/sitemaps/{id}/features/{f}/shape", park, road), 400, "{ \"widthMetres\": 900 }");
        String shaped = send(post("/api/sitemaps/{id}/features/{f}/shape", park, road), 200, "{ \"widthMetres\": 7.5 }");
        UUID copy = UUID.fromString(objectMapper.readTree(shaped).get("id").asString());

        mockMvc.perform(get("/api/sitemaps/{id}/features", park).header("Authorization", "Bearer " + rootToken()))
                .andExpect(jsonPath("$.features[?(@.id == '%s')].widthMetres".formatted(copy), hasItem(7.5)));
    }

    @Test
    void aFeatureOfAnotherPark_isNotFound() throws Exception {
        UUID park = placedPark();
        UUID other = placedPark("FE2");
        UUID road = pulled(other, "road", "Alley", "osm:way/5", "LINESTRING(-122.5 47.1002, -122.4998 47.1002)", "{}");

        send(delete("/api/sitemaps/{id}/features/{f}", park, road), 404, null);
    }
}
