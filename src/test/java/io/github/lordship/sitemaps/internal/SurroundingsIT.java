package io.github.lordship.sitemaps.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import io.github.lordship.properties.internal.PropertyRow;
import io.github.lordship.sitemaps.internal.SurroundingsRepository.PullArea;
import io.github.lordship.sitemaps.internal.SurroundingsService.Fetched;
import io.github.lordship.shared.DomainProblem.Problem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Pulling a park's surroundings. The sources are never asked here:
// each test hands the save what OpenStreetMap, the parcel service and USGS would have said.
@Transactional
public class SurroundingsIT extends IntegrationTest {

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

    @Autowired
    SurroundingsService surroundings;

    @Autowired
    SurroundingsRepository repository;

    // One road beside lot 1, and one building inside it
    private static final String OVERPASS = """
            [ { "type": "way", "id": 1, "tags": { "highway": "residential", "name": "Basin St SW" },
                "geometry": [ { "lat": 47.1002, "lon": -122.5005 }, { "lat": 47.1002, "lon": -122.4995 } ] },
              { "type": "way", "id": 2, "tags": { "building": "yes" },
                "geometry": [ { "lat": 47.10003, "lon": -122.49997 }, { "lat": 47.10003, "lon": -122.49993 },
                              { "lat": 47.10007, "lon": -122.49993 }, { "lat": 47.10007, "lon": -122.49997 },
                              { "lat": 47.10003, "lon": -122.49997 } ] } ]
            """;

    // The park's own parcel, under lot 1, and a neighbor to the east
    private static final String PARCELS = """
            [ { "type": "Feature", "properties": { "PARCEL_ID_NR": "P1", "SITUS_ADDRESS": "1 Park Rd" },
                "geometry": { "type": "Polygon", "coordinates": [[[-122.5001, 47.0999], [-122.4998, 47.0999],
                              [-122.4998, 47.1002], [-122.5001, 47.1002], [-122.5001, 47.0999]]] } },
              { "type": "Feature", "properties": { "PARCEL_ID_NR": "P2", "SITUS_ADDRESS": "9 Next Door Ln" },
                "geometry": { "type": "Polygon", "coordinates": [[[-122.4997, 47.0999], [-122.4994, 47.0999],
                              [-122.4994, 47.1002], [-122.4997, 47.1002], [-122.4997, 47.0999]]] } } ]
            """;

    private String rootToken() throws Exception {
        return TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
    }

    // A park with lot 1 on the map: a small square near Lakewood, WA, saved through the builder
    private UUID placedPark() throws Exception {
        PropertyRow park = testData.insertProperty("SR");
        String map = """
                { "platWidth": 400, "platHeight": 300, "platFileName": "plat.png",
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

    private PullArea area(UUID propertyId) {
        return repository.pullArea(propertyId, 150).orElseThrow();
    }

    // Heights rising gently to the east, about 30 m to 50 m
    private static Fetched fetched(JsonNode osm, JsonNode parcels, boolean withHeights, PullArea area,
                                   List<Problem> problems) {
        Elevation.GridRequest asked = Elevation.gridFor(
                new Elevation.Box(area.south(), area.west(), area.north(), area.east()));
        HeightTiff.Grid heights = null;
        if (withHeights) {
            float[] values = new float[asked.width() * asked.height()];
            for (int r = 0; r < asked.height(); r++) {
                for (int c = 0; c < asked.width(); c++) {
                    values[r * asked.width() + c] = 30f + 20f * c / asked.width();
                }
            }
            heights = new HeightTiff.Grid(asked.width(), asked.height(), values);
        }
        return new Fetched(osm, parcels, heights, asked, problems);
    }

    private int count(UUID propertyId, String kind) {
        return jdbc.sql("SELECT count(*) FROM map_feature WHERE property_id = :p AND kind = :k AND deleted_at IS NULL")
                .param("p", propertyId).param("k", kind).query(Integer.class).single();
    }

    @Test
    void aPull_savesEverySourceAndTiesTheBuildingToItsLot() throws Exception {
        UUID park = placedPark();
        PullArea area = area(park);

        surroundings.save(park, area, fetched(objectMapper.readTree(OVERPASS), objectMapper.readTree(PARCELS),
                true, area, List.of()));

        assertEquals(1, count(park, "road"));
        assertEquals(1, count(park, "building"));
        assertEquals(1, count(park, "park_boundary"));
        assertEquals(1, count(park, "parcel"), "the park's own parcel leaves the neighbor parcels");
        assertTrue(count(park, "contour") > 0);

        String role = jdbc.sql("""
                        SELECT f.details ->> 'role'
                          FROM map_feature f JOIN lot l ON l.uuid = f.lot_id
                         WHERE f.property_id = :p AND f.kind = 'building' AND l.lot_number = '1'
                        """)
                .param("p", park).query(String.class).single();
        assertEquals("home", role);

        String credits = jdbc.sql("SELECT array_to_string(credits, ' | ') FROM site_map WHERE property_id = :p")
                .param("p", park).query(String.class).single();
        assertTrue(credits.contains("OpenStreetMap"));
        assertTrue(credits.contains("Parcels"));
        assertTrue(credits.contains("USGS"));
        assertTrue(jdbc.sql("SELECT boundary IS NOT NULL FROM site_map WHERE property_id = :p")
                .param("p", park).query(Boolean.class).single());

        String token = rootToken();
        mockMvc.perform(get("/api/sitemaps/{id}/surroundings", park).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("done"))
                .andExpect(jsonPath("$.counts.road").value(1))
                .andExpect(jsonPath("$.counts.home").value(1));
        mockMvc.perform(get("/api/sitemaps/{id}/hillshade.png", park).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void aSourceThatIsDown_keepsWhatWasSavedFromItBefore() throws Exception {
        UUID park = placedPark();
        PullArea area = area(park);
        surroundings.save(park, area, fetched(objectMapper.readTree(OVERPASS), objectMapper.readTree(PARCELS),
                true, area, List.of()));

        // OpenStreetMap is down this time
        surroundings.save(park, area, fetched(null, objectMapper.readTree(PARCELS), true, area,
                List.of(Problem.of("surroundings.osm_unreachable", "overpass-api.de answered 504"))));

        assertEquals(1, count(park, "road"));
        assertEquals(1, count(park, "building"));
        mockMvc.perform(get("/api/sitemaps/{id}/surroundings", park).header("Authorization", "Bearer " + rootToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes", hasItem(containsString("OpenStreetMap could not be reached"))));
    }

    @Test
    void anEmptyAnswer_whereThereWereRoadsBefore_isTreatedAsDown() throws Exception {
        UUID park = placedPark();
        PullArea area = area(park);
        surroundings.save(park, area, fetched(objectMapper.readTree(OVERPASS), objectMapper.readTree(PARCELS),
                false, area, List.of()));

        surroundings.save(park, area, fetched(objectMapper.readTree("[]"), objectMapper.readTree(PARCELS),
                false, area, List.of()));

        assertEquals(1, count(park, "road"));
    }

    @Test
    void renamesHidesAndHandMadeRows_surviveAPull() throws Exception {
        UUID park = placedPark();
        PullArea area = area(park);
        jdbc.sql("""
                        INSERT INTO map_feature_edit (property_id, source_ref, name, hidden)
                        VALUES (:p, 'osm:way/1', 'Main St', NULL),
                               (:p, 'osm:way/2', NULL, TRUE)
                        """)
                .param("p", park).update();
        jdbc.sql("""
                        INSERT INTO map_feature (property_id, kind, name, geom, source, source_ref)
                        VALUES (:p, 'light', 'Gate light',
                                postgis.ST_SetSRID(postgis.ST_MakePoint(-122.49995, 47.10015), 4326), 'manual', 'manual')
                        """)
                .param("p", park).update();

        surroundings.save(park, area, fetched(objectMapper.readTree(OVERPASS), objectMapper.readTree(PARCELS),
                false, area, List.of()));

        String road = jdbc.sql("SELECT name FROM map_feature WHERE property_id = :p AND kind = 'road'")
                .param("p", park).query(String.class).single();
        assertEquals("Main St", road);
        assertEquals(0, count(park, "building"));
        assertEquals(1, count(park, "light"));
    }

    @Test
    void refresh_withNoLotsOnTheMap_isRefused() throws Exception {
        PropertyRow park = testData.insertProperty("SR");
        mockMvc.perform(post("/api/sitemaps/{id}/surroundings", park.uuid())
                        .header("Authorization", "Bearer " + rootToken()))
                .andExpect(status().isConflict());
    }

    @Test
    void status_ofAParkNeverPulled_isNever() throws Exception {
        PropertyRow park = testData.insertProperty("SR");
        mockMvc.perform(get("/api/sitemaps/{id}/surroundings", park.uuid())
                        .header("Authorization", "Bearer " + rootToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("never"));
    }
}
