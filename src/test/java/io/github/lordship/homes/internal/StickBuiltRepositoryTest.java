package io.github.lordship.homes.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.homes.StructureType;
import io.github.lordship.lots.internal.LotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
public class StickBuiltRepositoryTest extends IntegrationTest {

    @Autowired
    StickBuiltRepository stickBuiltRepository;

    @Autowired
    HomeRepository homeRepository;

    @Autowired
    LotRepository lotRepository;

    private UUID lotOn(String propertyCode, String lotNumber) {
        UUID propertyId = testData.insertProperty(propertyCode).uuid();
        return testData.insertLot(propertyId, lotNumber).uuid();
    }

    // ── save ─────────────────────────────────────────────────────────────────────

    @Test
    void save_shouldPersistMinimalRow_andApplyDefaults() {
        UUID lotId = lotOn("SB01", "4B");

        StickBuiltRow saved = stickBuiltRepository.save(lotId, null).orElseThrow();

        assertNotNull(saved.uuid());
        assertEquals(lotId, saved.lotId());
        assertNotNull(saved.createdAt());
        assertNull(saved.deletedAt());

        // DB defaults. park_owned is TRUE here, the opposite of mobile_home: a stick
        // built on our land is ours unless the lot is deeded away.
        assertEquals("SQFT", saved.areaUnits());
        assertTrue(saved.parkOwned());

        assertEquals("Building on lot 4B", saved.name());

        assertNull(saved.structureType());
        assertNull(saved.yearBuilt());
        assertNull(saved.area());
    }

    @Test
    void save_shouldReturnEmpty_whenTheLotDoesNotExist() {
        assertTrue(stickBuiltRepository.save(UUID.randomUUID(), null).isEmpty());
    }

    @Test
    void save_shouldReturnEmpty_whenTheLotIsSoftDeleted() {
        UUID lotId = lotOn("SB02", "7");
        lotRepository.softDelete(lotId);

        assertTrue(stickBuiltRepository.save(lotId, null).isEmpty());
    }

    @Test
    void save_shouldRejectACreatedByThatNamesNoAgent() {
        UUID lotId = lotOn("SB03", "1");

        assertThrows(DataIntegrityViolationException.class, () ->
                stickBuiltRepository.save(lotId, UUID.randomUUID()));
    }

    // ── one structure per lot ────────────────────────────────────────────────────
    // One violation per test method: Postgres aborts the transaction on the first
    // failed statement and refuses everything after it until rollback.

    @Test
    void save_shouldBeRefused_whenTheLotAlreadyHoldsAStickBuilt() {
        UUID lotId = lotOn("SB04", "1");
        stickBuiltRepository.save(lotId, null).orElseThrow();

        assertThrows(DataIntegrityViolationException.class, () ->
                stickBuiltRepository.save(lotId, null));
    }

    @Test
    void save_shouldBeRefused_whenTheLotAlreadyHoldsAMobileHome() {
        UUID lotId = lotOn("SB05", "1");
        homeRepository.save(lotId, null).orElseThrow();

        assertThrows(DataIntegrityViolationException.class, () ->
                stickBuiltRepository.save(lotId, null));
    }

    @Test
    void mobileHomeSave_shouldBeRefused_whenTheLotAlreadyHoldsAStickBuilt() {
        // the mirror trigger: the rule only holds if both tables ask
        UUID lotId = lotOn("SB06", "1");
        stickBuiltRepository.save(lotId, null).orElseThrow();

        assertThrows(DataIntegrityViolationException.class, () ->
                homeRepository.save(lotId, null));
    }

    @Test
    void mobileHomePatch_shouldBeRefused_whenMovedOntoAStickBuiltLot() {
        // UPDATE OF lot_id, not just insert -- a home can be moved into the conflict
        UUID occupied = lotOn("SB07", "1");
        stickBuiltRepository.save(occupied, null).orElseThrow();

        UUID free = lotOn("SB08", "2");
        HomeRow roamer = homeRepository.save(free, null).orElseThrow();

        assertThrows(DataIntegrityViolationException.class, () ->
                homeRepository.patch(roamer.uuid(), Map.of("lot_id", occupied)));
    }

    @Test
    void save_shouldSucceed_onceTheMobileHomeIsSoftDeleted() {
        UUID lotId = lotOn("SB09", "1");
        HomeRow home = homeRepository.save(lotId, null).orElseThrow();
        homeRepository.softDelete(home.uuid());

        assertTrue(stickBuiltRepository.save(lotId, null).isPresent());
    }

    @Test
    void save_shouldSucceed_onceTheEarlierStickBuiltIsSoftDeleted() {
        UUID lotId = lotOn("SB10", "1");
        StickBuiltRow first = stickBuiltRepository.save(lotId, null).orElseThrow();
        stickBuiltRepository.softDelete(first.uuid());

        assertTrue(stickBuiltRepository.save(lotId, null).isPresent());
    }

    @Test
    void patch_shouldNotTripOverItsOwnRow() {
        // the trigger excludes NEW.uuid, so an ordinary patch on the lot's only stick
        // built must not read itself as a second one
        UUID lotId = lotOn("SB11", "1");
        StickBuiltRow saved = stickBuiltRepository.save(lotId, null).orElseThrow();

        Optional<StickBuiltRow> patched = stickBuiltRepository.patch(saved.uuid(), Map.of("lot_id", lotId));

        assertTrue(patched.isPresent());
        assertEquals(lotId, patched.get().lotId());
    }

    // ── reads ────────────────────────────────────────────────────────────────────

    @Test
    void findById_shouldReturnRow_whenExists() {
        UUID lotId = lotOn("SB12", "2");
        StickBuiltRow saved = stickBuiltRepository.save(lotId, null).orElseThrow();

        assertEquals(saved.uuid(), stickBuiltRepository.findById(saved.uuid()).orElseThrow().uuid());
    }

    @Test
    void findById_doesNotFindStickBuilt_afterSoftDelete() {
        UUID lotId = lotOn("SB13", "2");
        StickBuiltRow saved = stickBuiltRepository.save(lotId, null).orElseThrow();

        assertTrue(stickBuiltRepository.softDelete(saved.uuid()));
        assertTrue(stickBuiltRepository.findById(saved.uuid()).isEmpty());
    }

    @Test
    void softDelete_shouldReturnFalse_whenAlreadyDeleted() {
        UUID lotId = lotOn("SB14", "2");
        StickBuiltRow saved = stickBuiltRepository.save(lotId, null).orElseThrow();

        assertTrue(stickBuiltRepository.softDelete(saved.uuid()));
        assertFalse(stickBuiltRepository.softDelete(saved.uuid()));
    }

    @Test
    void findByLot_shouldReturnTheOne_orNothing() {
        UUID lotId = lotOn("SB15", "3");
        assertTrue(stickBuiltRepository.findByLot(lotId).isEmpty());

        StickBuiltRow saved = stickBuiltRepository.save(lotId, null).orElseThrow();
        assertEquals(saved.uuid(), stickBuiltRepository.findByLot(lotId).orElseThrow().uuid());

        stickBuiltRepository.softDelete(saved.uuid());
        assertTrue(stickBuiltRepository.findByLot(lotId).isEmpty());
    }

    @Test
    void findByProperty_shouldReachStickBuiltsThroughTheirLots() {
        UUID propertyId = testData.insertProperty("SB16").uuid();
        stickBuiltRepository.save(testData.insertLot(propertyId, "1").uuid(), null).orElseThrow();
        stickBuiltRepository.save(testData.insertLot(propertyId, "2").uuid(), null).orElseThrow();

        // a different park must not appear
        stickBuiltRepository.save(lotOn("SB17", "1"), null).orElseThrow();

        List<StickBuiltRow> found = stickBuiltRepository.findByProperty("SB16");

        assertEquals(2, found.size());
    }

    @Test
    void findByProperty_shouldReturnEmpty_forAnUnknownCode() {
        assertTrue(stickBuiltRepository.findByProperty("NOPE").isEmpty());
    }

    @Test
    void findLotNumber_shouldFeedTheGeneratedName() {
        UUID lotId = lotOn("SB18", "12C");

        assertEquals(Optional.of("12C"), stickBuiltRepository.findLotNumber(lotId));
        assertTrue(stickBuiltRepository.findLotNumber(UUID.randomUUID()).isEmpty());
    }

    // ── patch ────────────────────────────────────────────────────────────────────

    @Test
    void patch_shouldUpdateFields_andReturnUpdatedRow() {
        UUID lotId = lotOn("SB19", "4B");
        StickBuiltRow saved = stickBuiltRepository.save(lotId, null).orElseThrow();

        Map<String, Object> changes = Map.of(
                "structure_type", "APARTMENT",
                "year_built", 1974,
                "floor", 2,
                "bedroom_count", 2,
                "bathroom_count", new BigDecimal("1.5"),
                "area", new BigDecimal("850.00"),
                "area_units", "SQFT",
                "park_owned", false,
                "appearance", "blue door, second floor balcony",
                "note", "roof recoated 2024"
        );

        StickBuiltRow row = stickBuiltRepository.patch(saved.uuid(), changes).orElseThrow();

        assertEquals(StructureType.APARTMENT, row.structureType());
        assertEquals(1974, row.yearBuilt());
        assertEquals(2, row.floor());
        assertEquals(0, new BigDecimal("850.00").compareTo(row.area()));
        assertFalse(row.parkOwned());
    }

    @Test
    void patch_shouldAcceptANegativeFloor_forABasement() {
        UUID lotId = lotOn("SB20", "1");
        StickBuiltRow saved = stickBuiltRepository.save(lotId, null).orElseThrow();

        assertEquals(-1, stickBuiltRepository.patch(saved.uuid(), Map.of("floor", -1)).orElseThrow().floor());
    }

    @Test
    void patch_shouldClearAField_whenGivenNull() {
        UUID lotId = lotOn("SB21", "1");
        StickBuiltRow saved = stickBuiltRepository.save(lotId, null).orElseThrow();
        stickBuiltRepository.patch(saved.uuid(), Map.of("note", "something"));

        Map<String, Object> changes = new HashMap<>();
        changes.put("note", null);

        assertNull(stickBuiltRepository.patch(saved.uuid(), changes).orElseThrow().note());
    }

    @Test
    void patch_shouldReturnUnchangedRow_withEmptyChanges() {
        UUID lotId = lotOn("SB22", "1");
        StickBuiltRow saved = stickBuiltRepository.save(lotId, null).orElseThrow();

        StickBuiltRow patched = stickBuiltRepository.patch(saved.uuid(), Map.of()).orElseThrow();

        assertEquals(saved.uuid(), patched.uuid());
        assertEquals(saved.name(), patched.name());
    }

    @Test
    void patch_shouldReturnEmpty_whenRowIsSoftDeleted() {
        UUID lotId = lotOn("SB23", "1");
        StickBuiltRow saved = stickBuiltRepository.save(lotId, null).orElseThrow();
        stickBuiltRepository.softDelete(saved.uuid());

        assertTrue(stickBuiltRepository.patch(saved.uuid(), Map.of("note", "gone")).isEmpty());
    }

    @Test
    void patch_shouldThrow_whenColumnIsNotAllowed() {
        // thrown in Java before any SQL, so these can share a method
        UUID lotId = lotOn("SB24", "1");
        StickBuiltRow saved = stickBuiltRepository.save(lotId, null).orElseThrow();

        assertThrows(InvalidDataAccessApiUsageException.class, () ->
                stickBuiltRepository.patch(saved.uuid(), Map.of("created_by", UUID.randomUUID())));
        assertThrows(InvalidDataAccessApiUsageException.class, () ->
                stickBuiltRepository.patch(saved.uuid(), Map.of("deleted_at", OffsetDateTime.now(ZoneOffset.UTC))));
        assertThrows(InvalidDataAccessApiUsageException.class, () ->
                stickBuiltRepository.patch(saved.uuid(), Map.of("uuid", UUID.randomUUID())));
    }

    // ── the CHECK constraints, reached past the service ──────────────────────────

    @Test
    void patch_shouldBeRejected_byTheStructureTypeCheck() {
        UUID lotId = lotOn("SB25", "1");
        StickBuiltRow saved = stickBuiltRepository.save(lotId, null).orElseThrow();

        assertThrows(DataIntegrityViolationException.class, () ->
                stickBuiltRepository.patch(saved.uuid(), Map.of("structure_type", "CASTLE")));
    }

    @Test
    void patch_shouldBeRejected_whenFloorIsZero() {
        UUID lotId = lotOn("SB26", "1");
        StickBuiltRow saved = stickBuiltRepository.save(lotId, null).orElseThrow();

        assertThrows(DataIntegrityViolationException.class, () ->
                stickBuiltRepository.patch(saved.uuid(), Map.of("floor", 0)));
    }

    @Test
    void patch_shouldBeRejected_whenAreaIsNotPositive() {
        UUID lotId = lotOn("SB27", "1");
        StickBuiltRow saved = stickBuiltRepository.save(lotId, null).orElseThrow();

        assertThrows(DataIntegrityViolationException.class, () ->
                stickBuiltRepository.patch(saved.uuid(), Map.of("area", new BigDecimal("0"))));
    }

    @Test
    void patch_shouldBeRejected_byTheAreaUnitsCheck() {
        UUID lotId = lotOn("SB28", "1");
        StickBuiltRow saved = stickBuiltRepository.save(lotId, null).orElseThrow();

        assertThrows(DataIntegrityViolationException.class, () ->
                stickBuiltRepository.patch(saved.uuid(), Map.of("area_units", "ACRES")));
    }

    @Test
    void patch_shouldBeRejected_whenYearBuiltIsOutOfRange() {
        UUID lotId = lotOn("SB29", "1");
        StickBuiltRow saved = stickBuiltRepository.save(lotId, null).orElseThrow();

        assertThrows(DataIntegrityViolationException.class, () ->
                stickBuiltRepository.patch(saved.uuid(), Map.of("year_built", 1700)));
    }

    @Test
    void patch_shouldBeRejected_whenBedroomCountIsNegative() {
        UUID lotId = lotOn("SB30", "1");
        StickBuiltRow saved = stickBuiltRepository.save(lotId, null).orElseThrow();

        assertThrows(DataIntegrityViolationException.class, () ->
                stickBuiltRepository.patch(saved.uuid(), Map.of("bedroom_count", -1)));
    }
}
