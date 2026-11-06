package io.github.lordship;

import io.github.lordship.lots.internal.LotRow;
import io.github.lordship.properties.internal.PropertyRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Deleting, listing and restoring properties and lots, the way Glade's three property views do it.
@Transactional
public class SoftDeleteIT extends IntegrationTest {

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

    private String token;

    @BeforeEach
    void signIn() throws Exception {
        token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
    }

    private boolean isDeleted(String table, UUID id) {
        return jdbc.sql("SELECT deleted_at IS NOT NULL FROM " + table + " WHERE uuid = :id")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    private String lotIds(UUID... ids) {
        StringBuilder body = new StringBuilder("{\"lotIds\": [");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) body.append(", ");
            body.append('"').append(ids[i]).append('"');
        }
        return body.append("]}").toString();
    }

    // ---- lots ----

    @Test
    void deleteLots_deletesThePickedLots_andTheyShowInTheDeletedList() throws Exception {
        PropertyRow property = testData.insertProperty("SD");
        LotRow one = testData.insertLot(property.uuid(), "1");
        LotRow two = testData.insertLot(property.uuid(), "2");
        LotRow kept = testData.insertLot(property.uuid(), "3");

        mockMvc.perform(post("/api/lots/delete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lotIds(one.uuid(), two.uuid())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2));

        assertTrue(isDeleted("lot", one.uuid()));
        assertTrue(isDeleted("lot", two.uuid()));
        assertFalse(isDeleted("lot", kept.uuid()));

        mockMvc.perform(get("/api/lots/deleted")
                        .param("propertyId", property.uuid().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].deletedAt").exists())
                .andExpect(jsonPath("$[0].deletedBy").exists());
    }

    @Test
    void deleteLots_skipsALotThatIsAlreadyDeleted() throws Exception {
        PropertyRow property = testData.insertProperty("SD");
        LotRow lot = testData.insertLot(property.uuid(), "1");

        for (int expected : new int[] {1, 0}) {
            mockMvc.perform(post("/api/lots/delete")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(lotIds(lot.uuid())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.updated").value(expected));
        }
    }

    @Test
    void deleteLots_refusesALotWithAnActiveTenancy_andDeletesNone() throws Exception {
        PropertyRow property = testData.insertProperty("SD");
        LotRow empty = testData.insertLot(property.uuid(), "1");
        LotRow rented = testData.insertLot(property.uuid(), "2");
        testData.insertTenancy(rented.uuid());

        mockMvc.perform(post("/api/lots/delete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lotIds(empty.uuid(), rented.uuid())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("lot.has_tenancy"));

        // all or nothing
        assertFalse(isDeleted("lot", empty.uuid()));
        assertFalse(isDeleted("lot", rented.uuid()));
    }

    @Test
    void deleteLot_refusesALotWithAnActiveTenancy() throws Exception {
        LotRow rented = testData.insertChainToLot();
        testData.insertTenancy(rented.uuid());

        mockMvc.perform(delete("/api/lots/" + rented.uuid())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("lot.has_tenancy"));
    }

    @Test
    void restoreLots_bringsTheLotsBack() throws Exception {
        PropertyRow property = testData.insertProperty("SD");
        LotRow lot = testData.insertLot(property.uuid(), "1");
        mockMvc.perform(delete("/api/lots/" + lot.uuid())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/lots/restore")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lotIds(lot.uuid())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1));

        assertFalse(isDeleted("lot", lot.uuid()));
        mockMvc.perform(get("/api/lots/" + lot.uuid())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void restoreLots_refusesALotWhosePropertyIsDeleted() throws Exception {
        PropertyRow property = testData.insertProperty("SD");
        LotRow lot = testData.insertLot(property.uuid(), "1");
        mockMvc.perform(delete("/api/lots/" + lot.uuid())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/properties/" + property.uuid())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/lots/restore")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lotIds(lot.uuid())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("lot.property_deleted"));
    }

    @Test
    void restoreLots_refusesAnUnknownLot() throws Exception {
        mockMvc.perform(post("/api/lots/restore")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lotIds(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("lot.unknown"));
    }

    // ---- properties ----

    @Test
    void restoreProperty_bringsItBackWithItsLots_butNotLotsDeletedOnTheirOwn() throws Exception {
        PropertyRow property = testData.insertProperty("SD");
        LotRow kept = testData.insertLot(property.uuid(), "1");
        LotRow gone = testData.insertLot(property.uuid(), "2");
        mockMvc.perform(delete("/api/lots/" + gone.uuid())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/properties/" + property.uuid())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/properties/deleted")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + property.uuid() + "')].deletedBy").exists());

        mockMvc.perform(post("/api/properties/" + property.uuid() + "/restore")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(property.uuid().toString()));

        mockMvc.perform(get("/api/screens/property/" + property.uuid())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lots.length()").value(1))
                .andExpect(jsonPath("$.lots[0].id").value(kept.uuid().toString()));
    }

    @Test
    void deleteProperty_refusesWhileALotHasAnActiveTenancy() throws Exception {
        LotRow lot = testData.insertChainToLot();
        testData.insertTenancy(lot.uuid());

        mockMvc.perform(delete("/api/properties/" + lot.propertyId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("property.has_tenancies"));

        assertFalse(isDeleted("property", lot.propertyId()));
    }

    @Test
    void deleteProperty_ignoresTenanciesOnLotsThatAreDeleted() throws Exception {
        LotRow lot = testData.insertChainToLot();
        testData.insertTenancy(lot.uuid());
        jdbc.sql("UPDATE lot SET deleted_at = now() WHERE uuid = :id").param("id", lot.uuid()).update();

        mockMvc.perform(delete("/api/properties/" + lot.propertyId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void createProperty_skipsTheCodeOfADeletedProperty() throws Exception {
        PropertyRow old = testData.insertProperty("Zzyzx Quarry", "1 Old Rd", "ZQ");
        mockMvc.perform(delete("/api/properties/" + old.uuid())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // "ZQ" is the first code the name would make
        mockMvc.perform(post("/api/properties/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"propertyName": "Zzyzx Quarry", "propertyStreet": "2 New Rd",
                                 "propertyCity": "Testville", "propertyState": "WA", "propertyZip": "98000"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.propertyCode").value(org.hamcrest.Matchers.not("ZQ")));
    }

    @Test
    void patchProperty_refusesTheCodeOfADeletedProperty() throws Exception {
        PropertyRow old = testData.insertProperty("SD");
        mockMvc.perform(delete("/api/properties/" + old.uuid())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        PropertyRow other = testData.insertProperty("Other Park", "1 Other St", "OP");

        mockMvc.perform(patch("/api/properties/" + other.uuid())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"propertyCode\": \"sd\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("property.code_in_use"));
    }

    @Test
    void patchProperty_letsAPropertyChangeTheCaseOfItsOwnCode() throws Exception {
        PropertyRow property = testData.insertProperty("SD");

        mockMvc.perform(patch("/api/properties/" + property.uuid())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"propertyCode\": \"Sd\"}"))
                .andExpect(status().isOk());
    }

    // ---- homes ----

    private UUID insertHome(UUID lotId) {
        return jdbc.sql("INSERT INTO mobile_home (lot_id, name) VALUES (:lotId, 'Test home') RETURNING uuid")
                .param("lotId", lotId)
                .query(UUID.class)
                .single();
    }

    @Test
    void deleteHome_thenItShowsInTheDeletedList_andRestoreBringsItBack() throws Exception {
        LotRow lot = testData.insertChainToLot();
        UUID home = insertHome(lot.uuid());

        mockMvc.perform(delete("/api/homes/" + home)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/homes/deleted")
                        .param("lotId", lot.uuid().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(home.toString()))
                .andExpect(jsonPath("$[0].name").value("Test home"))
                .andExpect(jsonPath("$[0].deletedBy").exists());

        mockMvc.perform(post("/api/homes/" + home + "/restore")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(home.toString()));

        assertFalse(isDeleted("mobile_home", home));
    }

    @Test
    void restoreHome_refusesAHomeWhoseLotIsDeleted() throws Exception {
        LotRow lot = testData.insertChainToLot();
        UUID home = insertHome(lot.uuid());
        mockMvc.perform(delete("/api/homes/" + home)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/lots/" + lot.uuid())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/homes/" + home + "/restore")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("home.lot_deleted"));
    }

    @Test
    void restoreHome_answers404_forAnUnknownHome() throws Exception {
        mockMvc.perform(post("/api/homes/" + UUID.randomUUID() + "/restore")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void restoreProperty_answersTheProperty_whenItIsNotDeleted() throws Exception {
        PropertyRow property = testData.insertProperty("SD");

        mockMvc.perform(post("/api/properties/" + property.uuid() + "/restore")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(property.uuid().toString()));
    }

    @Test
    void restoreProperty_answers404_forAnUnknownProperty() throws Exception {
        mockMvc.perform(post("/api/properties/" + UUID.randomUUID() + "/restore")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
