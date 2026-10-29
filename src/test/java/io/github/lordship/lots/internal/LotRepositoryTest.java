package io.github.lordship.lots.internal;

import io.github.lordship.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
public class LotRepositoryTest extends IntegrationTest {

    @Autowired
    LotRepository lotRepository;

    @Autowired
    JdbcClient jdbc;


    private LotRow minimalRow(UUID propertyId, String lotNumber) {
        return new LotRow(propertyId, lotNumber);
    }

    private LotRow richRow(UUID propertyId, String lotNumber) {
        return new LotRow(
                null, propertyId, true, null,
                lotNumber, null, "123 Main St", null, "Rental lot", "Front row", 1,
                null, null, null
        );
    }

    @Test
    void save_shouldPersistMinimalRow_andApplyDefaults() {
        // Arrange
        UUID propertyId = testData.insertProperty("I001").uuid();

        // Act
        LotRow saved = lotRepository.save(propertyId, "12");

        // Assert
        assertNotNull(saved.uuid());
        assertEquals(propertyId, saved.propertyId());
        assertEquals("12", saved.lotNumber());
        assertTrue(saved.isRentable());
        assertNull(saved.notRentableReason());
        assertNull(saved.lotAddress());
        assertNull(saved.description());
        assertNull(saved.notes());
        assertNull(saved.titleLink());
        assertNotNull(saved.createdAt());
        assertNull(saved.deletedAt());

        // shape_data wasn't supplied, so the DB's default rectangle applies.
        assertNotNull(saved.shapeData());
        assertEquals(4, saved.shapeData().vertices().size());
    }


    @Test
    void patch_shouldPersistNotRentable_withReason() {
        // Arrange
        UUID propertyId = testData.insertProperty("I003").uuid();
        LotRow saved = lotRepository.save(propertyId, "13");
        Map<String, Object> changes = new HashMap<>();
        changes.put("is_rentable", false);
        changes.put("not_rentable_reason", "Under construction");

        // Act
        Optional<LotRow> updated = lotRepository.patch(saved.uuid(), changes);

        // Assert
        assertTrue(updated.isPresent());
        assertFalse(updated.get().isRentable());
        assertEquals("Under construction", updated.get().notRentableReason());
    }

    @Test
    void findById_shouldReturnRow_whenExists() {
        // Arrange
        UUID propertyId = testData.insertProperty("I004").uuid();
        LotRow saved = lotRepository.save(propertyId, "12");

        // Act
        Optional<LotRow> found = lotRepository.findById(saved.uuid());

        // Assert
        assertTrue(found.isPresent());
        assertEquals(saved.uuid(), found.get().uuid());
    }

    @Test
    void findById_doesNotFindLot_afterSoftDelete() {
        // Arrange
        UUID propertyId = testData.insertProperty("I005").uuid();
        LotRow saved = lotRepository.save(propertyId, "12");

        // Act
        lotRepository.softDelete(saved.uuid());

        // Assert
        Optional<LotRow> found = lotRepository.findById(saved.uuid());
        assertTrue(found.isEmpty());
    }

    @Test
    void patch_shouldUpdateFields_andReturnUpdatedRow() {
        // Arrange
        UUID propertyId = testData.insertProperty("I006").uuid();
        LotRow saved = lotRepository.save(propertyId, "12");
        Map<String, Object> changes = Map.of(
                "lot_number", "14",
                "lot_address", "456 Side St",
                "description", "Vacant lot",
                "notes", "Ready for assignment",
                "is_rentable", false,
                "not_rentable_reason", "Needs inspection"
        );

        // Act
        Optional<LotRow> patched = lotRepository.patch(saved.uuid(), changes);

        // Assert
        assertTrue(patched.isPresent());
        LotRow row = patched.get();
        assertEquals("14", row.lotNumber());
        assertEquals("456 Side St", row.lotAddress());
        assertEquals("Vacant lot", row.description());
        assertEquals("Ready for assignment", row.notes());
        assertFalse(row.isRentable());
        assertEquals("Needs inspection", row.notRentableReason());
    }

    @Test
    void patch_shouldFail_whenSettingNotRentable_withoutReason() {
        // Arrange
        UUID propertyId = testData.insertProperty("I007").uuid();
        LotRow saved = lotRepository.save(propertyId, "12");

        // Act & Assert: the lot_not_rentable_has_reason CHECK constraint rejects this
        // at the DB level -- the same protection LotRow's compact constructor gives on
        // the Java side, verified here end-to-end through the dynamic patch path.
        assertThrows(DataIntegrityViolationException.class, () ->
                lotRepository.patch(saved.uuid(), Map.of("is_rentable", false))
        );
    }

    @Test
    void patch_shouldReturnUnchangedRow_withEmptyChanges() {
        // Arrange
        UUID propertyId = testData.insertProperty("I008").uuid();
        LotRow saved = lotRepository.save(propertyId, "12");

        // Act
        Optional<LotRow> patched = lotRepository.patch(saved.uuid(), Map.of());

        // Assert
        assertTrue(patched.isPresent());
        assertEquals(saved.uuid(), patched.get().uuid());
        assertEquals(saved.lotNumber(), patched.get().lotNumber());
    }

    @Test
    void patch_shouldThrow_whenColumnIsNotAllowed() {
        // Arrange
        UUID propertyId = testData.insertProperty("I008").uuid();
        LotRow saved = lotRepository.save(propertyId, "12");

        // Act & Assert
        // @Repository exception translation wraps the IllegalArgumentException.
        assertThrows(InvalidDataAccessApiUsageException.class, () ->
                lotRepository.patch(saved.uuid(), Map.of("property_id", UUID.randomUUID()))
        );
    }

    @Test
    void patch_shouldThrow_whenTargetRentColumnRequested() {
        // Arrange: target_rent moved to lot_permissible_agreement_type, so it's not a
        // lot column at all anymore -- it must stay off the whitelist even if some
        // caller still sends it out of habit.
        UUID propertyId = testData.insertProperty("I010").uuid();
        LotRow saved = lotRepository.save(propertyId, "12");

        // Act & Assert
        assertThrows(InvalidDataAccessApiUsageException.class, () ->
                lotRepository.patch(saved.uuid(), Map.of("target_rent", 150.0))
        );
    }

    // ---- title link ----

    @Test
    void patch_shouldSaveTheTitleLink_andNullClearsIt() {
        UUID propertyId = testData.insertProperty("I012").uuid();
        LotRow saved = lotRepository.save(propertyId, "12");

        LotRow linked = lotRepository.patch(saved.uuid(), Map.of("title_link", "https://onedrive.example.com/TP Lot 12"))
                .orElseThrow();
        assertEquals("https://onedrive.example.com/TP Lot 12", linked.titleLink());

        Map<String, Object> clear = new HashMap<>();
        clear.put("title_link", null);
        LotRow cleared = lotRepository.patch(saved.uuid(), clear).orElseThrow();
        assertNull(cleared.titleLink());
    }

    @Test
    void patch_shouldRejectATitleLinkThatIsNotAWebAddress() {
        UUID propertyId = testData.insertProperty("I013").uuid();
        LotRow saved = lotRepository.save(propertyId, "12");

        // the database check is the backstop when a caller skips the service
        assertThrows(DataIntegrityViolationException.class, () ->
                lotRepository.patch(saved.uuid(), Map.of("title_link", "C:\\Users\\folder")));
    }

    @Test
    void patch_shouldReturnEmpty_whenRowIsSoftDeleted() {
        // Arrange
        UUID propertyId = testData.insertProperty("I011").uuid();
        LotRow saved = lotRepository.save(propertyId, "12");
        lotRepository.softDelete(saved.uuid());

        // Act
        Optional<LotRow> patched = lotRepository.patch(saved.uuid(), Map.of("lot_number", "gone"));

        // Assert
        assertTrue(patched.isEmpty());
    }
}