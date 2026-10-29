package io.github.lordship.properties.internal;

import io.github.lordship.properties.BusinessNumber;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
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

    private PropertyRow saveTestProperty() {
        return propertyRepository.save("Test Mobile Park", "999 Test Ave", "Testville", "WA", "98000", "TP");
    }

    @Test
    void save_persistsRow_andReturnsGeneratedFields() {
        // Arrange and Act
        PropertyRow saved = saveTestProperty();

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
    void save_startsWithEmptyListsAndNoLinks() {
        PropertyRow saved = saveTestProperty();

        assertEquals(List.of(), saved.propertyParcels());
        assertEquals(List.of(), saved.businessNumbers());
        assertEquals(Map.of(), saved.fieldLinks());
        assertEquals(Map.of(), saved.fieldNotes());
        assertNull(saved.titleLink());
        assertNull(saved.acres());
        assertNull(saved.propertyCounty());
    }

    @Test
    void findByIdReturnsSavedProperty() {
        // Arrange
        PropertyRow saved = saveTestProperty();

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
        saveTestProperty();
        propertyRepository.save("Test Mobile Park2", "1001 Test Ave", "Testville", "WA", "98000", "TP2");

        // Act
        List<PropertyRow> all = propertyRepository.findAll();

        // Assert
        assertTrue(all.size() >= 2);
        assertTrue(all.stream().allMatch(p -> p.deletedAt() == null));
    }

    @Test
    void findById_returnsEmpty_onSoftDeletedProperties() {
        // Arrange
        PropertyRow saved = saveTestProperty();
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
        saveTestProperty();

        // Act and Assert
        assertThrows(DataIntegrityViolationException.class, () ->
                propertyRepository.save("Other Park", "1 Other St", "Testville", "WA", "98000", "tp"));
    }

    @Test
    void save_letsANewPropertyReuseTheCodeOfADeletedOne() {
        // Arrange
        PropertyRow first = saveTestProperty();
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
        PropertyRow saved = saveTestProperty();

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
        PropertyRow saved = saveTestProperty();

        // Act and Assert
        assertThrows(DataIntegrityViolationException.class, () ->
                propertyRepository.patch(saved.uuid(), Map.of("custom_fields", List.of("Gate code"))));
    }

    @Test
    void patch_rejectsAColumnThatIsNotPatchable() {
        PropertyRow saved = saveTestProperty();

        // the old single-parcel column is gone
        assertThrows(InvalidDataAccessApiUsageException.class, () ->
                propertyRepository.patch(saved.uuid(), Map.of("property_parcel", "10-48-0002")));
    }

    // ---- county, acres, parcels ----

    @Test
    void patch_savesCountyAndAcres() {
        PropertyRow saved = saveTestProperty();

        PropertyRow patched = propertyRepository.patch(saved.uuid(), Map.of(
                "property_county", "Spokane County",
                "acres", new BigDecimal("26.79"))).orElseThrow();

        assertEquals("Spokane County", patched.propertyCounty());
        assertEquals(0, new BigDecimal("26.79").compareTo(patched.acres()));
    }

    @Test
    void patch_savesParcelsInTheOrderSent() {
        PropertyRow saved = saveTestProperty();
        List<String> parcels = List.of("12336.9028", "11041.9013", "11041.9020");

        PropertyRow patched = propertyRepository.patch(saved.uuid(), Map.of("property_parcels", parcels)).orElseThrow();

        assertEquals(parcels, patched.propertyParcels());
        assertEquals(parcels, propertyRepository.findById(saved.uuid()).orElseThrow().propertyParcels());
    }

    @Test
    void patch_keepsTrailingZerosInParcels() {
        PropertyRow saved = saveTestProperty();

        PropertyRow patched = propertyRepository.patch(saved.uuid(), Map.of(
                "property_parcels", List.of("11076.9020"))).orElseThrow();

        assertEquals(List.of("11076.9020"), patched.propertyParcels());
    }

    @Test
    void patch_clearsParcels_whenSentNull() {
        PropertyRow saved = saveTestProperty();
        propertyRepository.patch(saved.uuid(), Map.of("property_parcels", List.of("12336.9028")));
        Map<String, Object> clear = new HashMap<>();
        clear.put("property_parcels", null);

        PropertyRow patched = propertyRepository.patch(saved.uuid(), clear).orElseThrow();

        assertEquals(List.of(), patched.propertyParcels());
    }

    // ---- business numbers ----

    @Test
    void patch_savesBusinessNumbers_andReadsThemBackAsRecords() {
        PropertyRow saved = saveTestProperty();

        PropertyRow patched = propertyRepository.patch(saved.uuid(), Map.of("business_numbers", List.of(
                Map.of("state", "WA", "type", "UBI", "number", "604 959 374", "link", "https://ccfs.sos.wa.gov/"),
                Map.of("state", "ID", "type", "SOS", "number", "4871883", "note", "foreign registration")
        ))).orElseThrow();

        assertEquals(List.of(
                new BusinessNumber("WA", "UBI", "604 959 374", "https://ccfs.sos.wa.gov/", null),
                new BusinessNumber("ID", "SOS", "4871883", null, "foreign registration")
        ), patched.businessNumbers());
    }

    @Test
    void patch_clearsBusinessNumbers_whenSentNull() {
        PropertyRow saved = saveTestProperty();
        propertyRepository.patch(saved.uuid(), Map.of("business_numbers", List.of(
                Map.of("state", "WA", "type", "UBI", "number", "604 959 374"))));
        Map<String, Object> clear = new HashMap<>();
        clear.put("business_numbers", null);

        PropertyRow patched = propertyRepository.patch(saved.uuid(), clear).orElseThrow();

        assertEquals(List.of(), patched.businessNumbers());
    }

    // ---- title link ----

    @Test
    void patch_savesTheTitleLink_andNullClearsIt() {
        PropertyRow saved = saveTestProperty();

        PropertyRow linked = propertyRepository
                .patch(saved.uuid(), Map.of("title_link", "https://onedrive.example.com/Com. Test Mobile Park"))
                .orElseThrow();
        assertEquals("https://onedrive.example.com/Com. Test Mobile Park", linked.titleLink());

        Map<String, Object> clear = new HashMap<>();
        clear.put("title_link", null);
        PropertyRow cleared = propertyRepository.patch(saved.uuid(), clear).orElseThrow();
        assertNull(cleared.titleLink());
    }

    @Test
    void patch_rejectsATitleLinkThatIsNotAWebAddress() {
        PropertyRow saved = saveTestProperty();

        // the database check is the backstop when a caller skips the service
        assertThrows(DataIntegrityViolationException.class, () ->
                propertyRepository.patch(saved.uuid(), Map.of("title_link", "C:\\Users\\folder")));
    }

    @Test
    void setFieldLink_leavesTheTitleLinkAlone() {
        PropertyRow saved = saveTestProperty();
        propertyRepository.patch(saved.uuid(), Map.of("title_link", "https://onedrive.example.com/TP"));

        PropertyRow linked = propertyRepository.setFieldLink(saved.uuid(), "acres", "https://gis.example.gov")
                .orElseThrow();

        assertEquals("https://onedrive.example.com/TP", linked.titleLink());
    }

    // ---- field links ----

    @Test
    void setFieldLink_addsALink() {
        PropertyRow saved = saveTestProperty();

        PropertyRow linked = propertyRepository.setFieldLink(saved.uuid(), "property_zoning", "https://zoning.example.gov")
                .orElseThrow();

        assertEquals(Map.of("property_zoning", "https://zoning.example.gov"), linked.fieldLinks());
    }

    @Test
    void setFieldLink_keepsOtherLinks() {
        PropertyRow saved = saveTestProperty();
        propertyRepository.setFieldLink(saved.uuid(), "acres", "https://gis.example.gov");

        PropertyRow linked = propertyRepository.setFieldLink(saved.uuid(), "PWS ID", "https://doh.example.gov")
                .orElseThrow();

        assertEquals(Map.of(
                "acres", "https://gis.example.gov",
                "PWS ID", "https://doh.example.gov"), linked.fieldLinks());
    }

    @Test
    void setFieldLink_removesTheLink_whenUrlIsNull() {
        PropertyRow saved = saveTestProperty();
        propertyRepository.setFieldLink(saved.uuid(), "acres", "https://gis.example.gov");
        propertyRepository.setFieldLink(saved.uuid(), "property_zoning", "https://zoning.example.gov");

        PropertyRow cleared = propertyRepository.setFieldLink(saved.uuid(), "acres", null).orElseThrow();

        assertEquals(Map.of("property_zoning", "https://zoning.example.gov"), cleared.fieldLinks());
    }

    @Test
    void setFieldLink_returnsEmpty_onASoftDeletedProperty() {
        PropertyRow saved = saveTestProperty();
        propertyRepository.softDelete(saved.uuid());

        assertTrue(propertyRepository.setFieldLink(saved.uuid(), "acres", "https://gis.example.gov").isEmpty());
    }

    // ---- field notes ----

    @Test
    void setFieldNote_addsANote_andLeavesLinksAlone() {
        PropertyRow saved = saveTestProperty();
        propertyRepository.setFieldLink(saved.uuid(), "acres", "https://gis.example.gov");

        PropertyRow noted = propertyRepository.setFieldNote(saved.uuid(), "acres", "Two parcels added").orElseThrow();

        assertEquals(Map.of("acres", "Two parcels added"), noted.fieldNotes());
        assertEquals(Map.of("acres", "https://gis.example.gov"), noted.fieldLinks());
    }

    @Test
    void setFieldNote_removesTheNote_whenNoteIsNull() {
        PropertyRow saved = saveTestProperty();
        propertyRepository.setFieldNote(saved.uuid(), "acres", "Two parcels added");
        propertyRepository.setFieldNote(saved.uuid(), "PWS ID", "Well tag AGG468");

        PropertyRow cleared = propertyRepository.setFieldNote(saved.uuid(), "acres", null).orElseThrow();

        assertEquals(Map.of("PWS ID", "Well tag AGG468"), cleared.fieldNotes());
    }

    @Test
    void setFieldNote_returnsEmpty_onASoftDeletedProperty() {
        PropertyRow saved = saveTestProperty();
        propertyRepository.softDelete(saved.uuid());

        assertTrue(propertyRepository.setFieldNote(saved.uuid(), "acres", "note").isEmpty());
    }

    @Test
    void patch_replacesFieldLinks_andNullEmptiesThem() {
        PropertyRow saved = saveTestProperty();
        propertyRepository.setFieldLink(saved.uuid(), "acres", "https://gis.example.gov");
        Map<String, Object> clear = new HashMap<>();
        clear.put("field_links", null);

        PropertyRow patched = propertyRepository.patch(saved.uuid(), clear).orElseThrow();

        assertEquals(Map.of(), patched.fieldLinks());
    }
}
