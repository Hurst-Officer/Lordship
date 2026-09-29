package io.github.lordship.properties.internal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional

public class PropertyRepositoryTest {
    @Autowired
    PropertyRepository propertyRepository;


    @Test
    void save_persistsRow_andReturnsGeneratedFields() {
        // Arrange and Act
        PropertyRow saved = propertyRepository.save("Test Mobile Park", "999 Test Ave", "Testville", "WA", "98000", "TP");

        // Assert
        assertNotNull(saved.uuid());
        assertNotNull(saved.createdAt());
        assertNull(saved.deletedAt());
        assertEquals("Test Mobile Park", saved.propertyName());
        assertEquals("999 Test Ave", saved.propertyStreet());
        assertEquals("Testville", saved.propertyCity());
        assertEquals("WA", saved.propertyState());
        assertEquals("98000", saved.propertyZip());
        assertTrue(saved.customFields().isEmpty());
    }

    @Test
    void findByPropertyCodeReturnsSavedProperty() {
        // Arrange
        PropertyRow saved = propertyRepository.save("Test Mobile Park", "999 Test Ave", "Testville", "WA", "98000", "TP");

        // Act
        Optional<PropertyRow> found = propertyRepository.findById(saved.uuid());

        // Assert
        assertTrue(found.isPresent());
        assertEquals(saved.uuid(), found.get().uuid());
        assertEquals(saved.propertyCode(), found.get().propertyCode());
    }

    @Test
    void findByCode_returnsEmpty_whenNotFound() {
        Optional<PropertyRow> found = propertyRepository.findByCode("NOPE99");

        assertTrue(found.isEmpty());
    }

    @Test
    void findAll_returnsAllSavedProperties() {
        // Arrange
        propertyRepository.save("Test Mobile Park", "999 Test Ave", "Testville", "WA", "98000", "TP");
        propertyRepository.save("Test Mobile Park2", "1001 Test Ave", "Testville", "WA", "98000", "TP2");

        // Act
        List<PropertyRow> all = propertyRepository.findAll();

        // Assert
        assertTrue(all.size() >= 2);
        assertTrue(all.stream().allMatch(p -> p.deletedAt() == null));
    }

    @Test
    void findById_returnsNull_onSoftDeletedProperties() {
        // Arrange
        PropertyRow saved = propertyRepository.save("Test Mobile Park", "999 Test Ave", "Testville", "WA", "98000", "TP");
        boolean deleteSuccess = propertyRepository.softDelete(saved.uuid());

        // Act
        PropertyRow found = propertyRepository.findById(saved.uuid()).orElse(null);

        // Assert
        assertTrue(deleteSuccess);
        assertNull(found);
    }

    @Test
    void save_rejectsTheSameCode_ignoringCase() {
        // Arrange
        propertyRepository.save("Test Mobile Park", "999 Test Ave", "Testville", "WA", "98000", "TP");

        // Act and Assert
        assertThrows(DataIntegrityViolationException.class, () ->
                propertyRepository.save("Other Park", "1 Other St", "Testville", "WA", "98000", "tp"));
    }

    @Test
    void save_letsANewPropertyReuseTheCodeOfADeletedOne() {
        // Arrange
        PropertyRow first = propertyRepository.save("Test Mobile Park", "999 Test Ave", "Testville", "WA", "98000", "TP");
        propertyRepository.softDelete(first.uuid());

        // Act
        PropertyRow second = propertyRepository.save("Other Park", "1 Other St", "Testville", "WA", "98000", "TP");

        // Assert
        assertEquals("TP", second.propertyCode());
    }

    @Test
    void save_rejectsAStateThatIsNotTwoCapitalLetters() {
        assertThrows(DataIntegrityViolationException.class, () ->
                propertyRepository.save("Test Mobile Park", "999 Test Ave", "Testville", "wa", "98000", "TP"));
    }

    @Test
    void save_rejectsAZipThatIsNotFiveDigitsOrZipPlusFour() {
        assertThrows(DataIntegrityViolationException.class, () ->
                propertyRepository.save("Test Mobile Park", "999 Test Ave", "Testville", "WA", "9800", "TP"));
    }

    @Test
    void patch_replacesCustomFields() {
        // Arrange
        PropertyRow saved = propertyRepository.save("Test Mobile Park", "999 Test Ave", "Testville", "WA", "98000", "TP");

        // Act
        PropertyRow patched = propertyRepository
                .patch(saved.uuid(), Map.of("custom_fields", Map.of("Gate code", "4412")))
                .orElseThrow();

        // Assert
        assertEquals(Map.of("Gate code", "4412"), patched.customFields());
    }

    @Test
    void patch_rejectsCustomFieldsThatAreNotAnObject() {
        // Arrange
        PropertyRow saved = propertyRepository.save("Test Mobile Park", "999 Test Ave", "Testville", "WA", "98000", "TP");

        // Act and Assert
        assertThrows(DataIntegrityViolationException.class, () ->
                propertyRepository.patch(saved.uuid(), Map.of("custom_fields", List.of("Gate code"))));
    }
}
