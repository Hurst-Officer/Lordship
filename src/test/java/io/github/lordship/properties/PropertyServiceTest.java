package io.github.lordship.properties;

import io.github.lordship.audit.AuditService;
import io.github.lordship.shared.InvalidRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class PropertyServiceTest {

    @Autowired
    private PropertyService propertyService;

    @MockitoBean
    AuditService auditService;

    private Property createProperty() {
        return propertyService.createProperty("Test Mobile Park", "999 Test Ave", "Testville", "WA", "98000");
    }

    @Test
    void createPropertyReturnsPropertyWithGeneratedFields() {
        Property created = createProperty();

        assertNotNull(created.uuid());
        assertNotNull(created.createdAt());
        assertEquals("Test Mobile Park", created.propertyName());
        assertEquals("999 Test Ave", created.propertyStreet());
        assertEquals("Testville", created.propertyCity());
        assertEquals("WA", created.propertyState());
        assertEquals("98000", created.propertyZip());
        assertTrue(created.customFields().isEmpty());
    }

    @Test
    void createPropertyStartsWithEmptyListsAndNoLinks() {
        Property created = createProperty();

        assertEquals(List.of(), created.propertyParcels());
        assertEquals(List.of(), created.businessNumbers());
        assertEquals(Map.of(), created.fieldLinks());
        assertEquals(Map.of(), created.fieldNotes());
        assertNull(created.acres());
        assertNull(created.propertyCounty());
    }

    @Test
    void findByPropertyIdReturnsCreatedProperty() {
        Property created = createProperty();

        Optional<Property> found = propertyService.findByPropertyId(created.uuid());

        assertTrue(found.isPresent());
        assertEquals(created.uuid(), found.get().uuid());
    }

    @Test
    void findAllIncludesCreatedProperty() {
        Property created = createProperty();

        List<Property> all = propertyService.findAll();

        assertTrue(all.stream().anyMatch(p -> p.uuid().equals(created.uuid())));
    }

    // ---- new fields: county, acres, parcels, business numbers ----

    @Test
    void patchPropertySavesCountyAcresParcelsAndBusinessNumbers() {
        Property created = createProperty();

        Property patched = propertyService.patchProperty(created.uuid(), new HashMap<>(Map.of(
                "property_county", "Spokane County",
                "acres", new BigDecimal("26.79"),
                "property_parcels", List.of("12336.9028", "11041.9013"),
                "business_numbers", List.of(Map.of(
                        "state", "WA", "type", "UBI", "number", "604 280 651",
                        "link", "https://ccfs.sos.wa.gov/"))
        ))).orElseThrow();

        assertEquals("Spokane County", patched.propertyCounty());
        assertEquals(0, new BigDecimal("26.79").compareTo(patched.acres()));
        assertEquals(List.of("12336.9028", "11041.9013"), patched.propertyParcels());
        assertEquals(List.of(new BusinessNumber("WA", "UBI", "604 280 651", "https://ccfs.sos.wa.gov/", null)),
                patched.businessNumbers());
    }

    @Test
    void patchPropertyKeepsBusinessNumbersExactlyAsTyped() {
        Property created = createProperty();

        // leading zeros, a letter prefix and a dash are all part of real numbers
        Property patched = propertyService.patchProperty(created.uuid(), new HashMap<>(Map.of(
                "business_numbers", List.of(
                        Map.of("state", "ND", "type", "SOS", "number", "0006280712"),
                        Map.of("state", "MT", "type", "SOS", "number", "E1481043"),
                        Map.of("state", "OR", "type", "SOS", "number", "1849326-98"))
        ))).orElseThrow();

        assertEquals(List.of("0006280712", "E1481043", "1849326-98"),
                patched.businessNumbers().stream().map(BusinessNumber::number).toList());
    }

    // ---- audit: a patch logs only what changed, and nothing when nothing changed ----

    @Test
    void patchPropertyRecordsNothing_whenTheValueIsTheSame() {
        Property created = createProperty();

        propertyService.patchProperty(created.uuid(), new HashMap<>(Map.of("property_name", "Test Mobile Park")));

        verify(auditService, never()).recordUpdate(any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void patchPropertyRecordsOnlyTheFieldThatChanged() {
        Property created = createProperty();
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass((Class) Map.class);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass((Class) Map.class);

        propertyService.patchProperty(created.uuid(), new HashMap<>(Map.of(
                "property_name", "Renamed Park",
                "property_city", "Testville")));

        verify(auditService).recordUpdate(eq("property"), eq(created.uuid()), before.capture(), after.capture());
        assertEquals(Map.of("propertyName", "Test Mobile Park"), before.getValue());
        assertEquals(Map.of("propertyName", "Renamed Park"), after.getValue());
    }

    @Test
    void patchPropertyRecordsNothing_whenCustomFieldsAreSentAgainUnchanged() {
        Property created = createProperty();
        Map<String, Object> fields = Map.of("Gate code", "4412");
        propertyService.patchProperty(created.uuid(), new HashMap<>(Map.of("custom_fields", fields)));

        propertyService.patchProperty(created.uuid(), new HashMap<>(Map.of("custom_fields", fields)));

        // only the first patch changed anything
        verify(auditService, times(1)).recordUpdate(any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void patchPropertyRecordsOnlyTheCustomFieldEntriesThatChanged() {
        Property created = createProperty();
        propertyService.patchProperty(created.uuid(), new HashMap<>(Map.of(
                "custom_fields", Map.of("Gate code", "4412", "Pool guy", "Bob"))));
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass((Class) Map.class);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass((Class) Map.class);

        propertyService.patchProperty(created.uuid(), new HashMap<>(Map.of(
                "custom_fields", Map.of("Gate code", "4412", "Pool guy", "Rob"))));

        // the second call is the one that matters: only "Pool guy" changed
        verify(auditService, times(2)).recordUpdate(eq("property"), eq(created.uuid()), before.capture(), after.capture());
        assertEquals(Map.of("customFields", Map.of("Pool guy", "Bob")), before.getAllValues().get(1));
        assertEquals(Map.of("customFields", Map.of("Pool guy", "Rob")), after.getAllValues().get(1));
    }

    // ---- field links ----

    @Test
    void setFieldLinkSavesALinkForAColumn() {
        Property created = createProperty();

        Property linked = propertyService.setFieldLink(created.uuid(), "property_zoning", "https://zoning.example.gov")
                .orElseThrow();

        assertEquals(Map.of("property_zoning", "https://zoning.example.gov"), linked.fieldLinks());
    }

    @Test
    void setFieldLinkSavesALinkForACustomField() {
        Property created = createProperty();
        propertyService.patchProperty(created.uuid(), new HashMap<>(Map.of("custom_fields", Map.of("PWS ID", "46985 A"))));

        Property linked = propertyService.setFieldLink(created.uuid(), "PWS ID", "https://doh.example.gov/46985")
                .orElseThrow();

        assertEquals("https://doh.example.gov/46985", linked.fieldLinks().get("PWS ID"));
    }

    @Test
    void setFieldLinkTrimsTheUrl() {
        Property created = createProperty();

        Property linked = propertyService.setFieldLink(created.uuid(), "acres", "  https://gis.example.gov  ")
                .orElseThrow();

        assertEquals("https://gis.example.gov", linked.fieldLinks().get("acres"));
    }

    @Test
    void setFieldLinkReplacesAnExistingLink() {
        Property created = createProperty();
        propertyService.setFieldLink(created.uuid(), "property_zoning", "https://old.example.gov");

        Property linked = propertyService.setFieldLink(created.uuid(), "property_zoning", "https://new.example.gov")
                .orElseThrow();

        assertEquals(Map.of("property_zoning", "https://new.example.gov"), linked.fieldLinks());
    }

    @Test
    void setFieldLinkClearsTheLink_whenUrlIsNullOrBlank() {
        Property created = createProperty();
        propertyService.setFieldLink(created.uuid(), "property_zoning", "https://zoning.example.gov");
        propertyService.setFieldLink(created.uuid(), "acres", "https://gis.example.gov");

        propertyService.setFieldLink(created.uuid(), "property_zoning", null);
        Property cleared = propertyService.setFieldLink(created.uuid(), "acres", "   ").orElseThrow();

        assertEquals(Map.of(), cleared.fieldLinks());
    }

    @Test
    void setFieldLinkRejectsAFieldThatDoesNotExist() {
        Property created = createProperty();

        InvalidRequest e = assertThrows(InvalidRequest.class, () ->
                propertyService.setFieldLink(created.uuid(), "Gate code", "https://example.gov"));

        assertEquals("property.field_unknown", e.problem().code());
        assertEquals("field", e.problem().field());
    }

    @Test
    void setFieldLinkRejectsAColumnThatCannotHoldALink() {
        Property created = createProperty();

        // business numbers carry their own links, and custom_fields is a container, not a field
        for (String field : List.of("business_numbers", "custom_fields", "field_links", "field_notes", "property_manager")) {
            InvalidRequest e = assertThrows(InvalidRequest.class, () ->
                    propertyService.setFieldLink(created.uuid(), field, "https://example.gov"));
            assertEquals("property.field_unknown", e.problem().code());
        }
    }

    @Test
    void setFieldLinkRejectsAUrlThatIsNotHttp() {
        Property created = createProperty();

        for (String url : List.of("ftp://example.gov", "example.gov", "javascript:alert(1)")) {
            InvalidRequest e = assertThrows(InvalidRequest.class, () ->
                    propertyService.setFieldLink(created.uuid(), "property_zoning", url));
            assertEquals("property.link_not_a_url", e.problem().code());
            assertEquals("url", e.problem().field());
        }
    }

    @Test
    void setFieldLinkAcceptsUpperCaseHttps() {
        Property created = createProperty();

        Property linked = propertyService.setFieldLink(created.uuid(), "property_zoning", "HTTPS://ZONING.EXAMPLE.GOV")
                .orElseThrow();

        assertEquals("HTTPS://ZONING.EXAMPLE.GOV", linked.fieldLinks().get("property_zoning"));
    }

    @Test
    void setFieldLinkReturnsEmpty_whenThePropertyDoesNotExist() {
        assertTrue(propertyService.setFieldLink(UUID.randomUUID(), "property_zoning", "https://example.gov").isEmpty());
    }

    @Test
    void removingACustomFieldRemovesItsLink_butKeepsColumnLinks() {
        Property created = createProperty();
        propertyService.patchProperty(created.uuid(), new HashMap<>(Map.of(
                "custom_fields", Map.of("PWS ID", "46985 A", "Septic", "Lagoon"))));
        propertyService.setFieldLink(created.uuid(), "PWS ID", "https://doh.example.gov/46985");
        propertyService.setFieldLink(created.uuid(), "Septic", "https://ecy.example.gov");
        propertyService.setFieldLink(created.uuid(), "property_zoning", "https://zoning.example.gov");

        Property patched = propertyService.patchProperty(created.uuid(), new HashMap<>(Map.of(
                "custom_fields", Map.of("Septic", "Lagoon")))).orElseThrow();

        assertEquals(Map.of(
                "Septic", "https://ecy.example.gov",
                "property_zoning", "https://zoning.example.gov"), patched.fieldLinks());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setFieldLinkRecordsOnlyTheLinkThatChanged() {
        Property created = createProperty();
        propertyService.setFieldLink(created.uuid(), "acres", "https://gis.example.gov");
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass((Class) Map.class);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass((Class) Map.class);

        propertyService.setFieldLink(created.uuid(), "property_zoning", "https://zoning.example.gov");

        verify(auditService, times(2)).recordUpdate(eq("property"), eq(created.uuid()), before.capture(), after.capture());
        Map<String, Object> zoningOnly = new HashMap<>();
        zoningOnly.put("property_zoning", null);
        assertEquals(Map.of("fieldLinks", zoningOnly), before.getAllValues().get(1));
        assertEquals(Map.of("fieldLinks", Map.of("property_zoning", "https://zoning.example.gov")),
                after.getAllValues().get(1));
    }

    @Test
    void setFieldLinkRecordsNothing_whenTheLinkIsTheSame() {
        Property created = createProperty();
        propertyService.setFieldLink(created.uuid(), "acres", "https://gis.example.gov");

        propertyService.setFieldLink(created.uuid(), "acres", "https://gis.example.gov");

        verify(auditService, times(1)).recordUpdate(any(), any(), any(), any());
    }

    // ---- field notes ----

    @Test
    void setFieldNoteSavesANoteForAColumn() {
        Property created = createProperty();

        Property noted = propertyService.setFieldNote(created.uuid(), "property_zoning", "Can not expand here")
                .orElseThrow();

        assertEquals(Map.of("property_zoning", "Can not expand here"), noted.fieldNotes());
    }

    @Test
    void setFieldNoteSavesANoteForACustomField() {
        Property created = createProperty();
        propertyService.patchProperty(created.uuid(), new HashMap<>(Map.of("custom_fields", Map.of("PWS ID", "46985 A"))));

        Property noted = propertyService.setFieldNote(created.uuid(), "PWS ID", "Well tag AGG468")
                .orElseThrow();

        assertEquals("Well tag AGG468", noted.fieldNotes().get("PWS ID"));
    }

    @Test
    void setFieldNoteKeepsLineBreaks_andTrimsTheEnds() {
        Property created = createProperty();

        Property noted = propertyService.setFieldNote(created.uuid(), "property_parcels",
                "  Additional parcels\n11041.9034\n11041.9028  ").orElseThrow();

        assertEquals("Additional parcels\n11041.9034\n11041.9028", noted.fieldNotes().get("property_parcels"));
    }

    @Test
    void setFieldNoteAcceptsTextThatLooksLikeALink() {
        Property created = createProperty();

        // notes are plain text, so the link rule does not apply
        Property noted = propertyService.setFieldNote(created.uuid(), "acres", "see ftp://old-server/plats")
                .orElseThrow();

        assertEquals("see ftp://old-server/plats", noted.fieldNotes().get("acres"));
    }

    @Test
    void setFieldNoteClearsTheNote_whenNullOrBlank() {
        Property created = createProperty();
        propertyService.setFieldNote(created.uuid(), "acres", "Two parcels added");
        propertyService.setFieldNote(created.uuid(), "property_zoning", "Can not expand");

        propertyService.setFieldNote(created.uuid(), "acres", null);
        Property cleared = propertyService.setFieldNote(created.uuid(), "property_zoning", "  ").orElseThrow();

        assertEquals(Map.of(), cleared.fieldNotes());
    }

    @Test
    void setFieldNoteRejectsAFieldThatDoesNotExist() {
        Property created = createProperty();

        InvalidRequest e = assertThrows(InvalidRequest.class, () ->
                propertyService.setFieldNote(created.uuid(), "Gate code", "4412"));

        assertEquals("property.field_unknown", e.problem().code());
        assertEquals("field", e.problem().field());
    }

    @Test
    void setFieldNoteReturnsEmpty_whenThePropertyDoesNotExist() {
        assertTrue(propertyService.setFieldNote(UUID.randomUUID(), "acres", "note").isEmpty());
    }

    @Test
    void linksAndNotesAreKeptApart() {
        Property created = createProperty();
        propertyService.setFieldLink(created.uuid(), "property_zoning", "https://zoning.example.gov");

        Property both = propertyService.setFieldNote(created.uuid(), "property_zoning", "Can not expand").orElseThrow();
        Property linkCleared = propertyService.setFieldLink(created.uuid(), "property_zoning", null).orElseThrow();

        assertEquals(Map.of("property_zoning", "https://zoning.example.gov"), both.fieldLinks());
        assertEquals(Map.of("property_zoning", "Can not expand"), both.fieldNotes());
        assertEquals(Map.of(), linkCleared.fieldLinks());
        assertEquals(Map.of("property_zoning", "Can not expand"), linkCleared.fieldNotes());
    }

    @Test
    void removingACustomFieldRemovesItsNote_butKeepsColumnNotes() {
        Property created = createProperty();
        propertyService.patchProperty(created.uuid(), new HashMap<>(Map.of(
                "custom_fields", Map.of("PWS ID", "46985 A", "Septic", "Lagoon"))));
        propertyService.setFieldNote(created.uuid(), "PWS ID", "Well tag AGG468");
        propertyService.setFieldNote(created.uuid(), "acres", "Two parcels added");

        Property patched = propertyService.patchProperty(created.uuid(), new HashMap<>(Map.of(
                "custom_fields", Map.of("Septic", "Lagoon")))).orElseThrow();

        assertEquals(Map.of("acres", "Two parcels added"), patched.fieldNotes());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setFieldNoteRecordsOnlyTheNoteThatChanged() {
        Property created = createProperty();
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass((Class) Map.class);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass((Class) Map.class);

        propertyService.setFieldNote(created.uuid(), "acres", "Two parcels added");

        verify(auditService).recordUpdate(eq("property"), eq(created.uuid()), before.capture(), after.capture());
        Map<String, Object> acresOnly = new HashMap<>();
        acresOnly.put("acres", null);
        assertEquals(Map.of("fieldNotes", acresOnly), before.getValue());
        assertEquals(Map.of("fieldNotes", Map.of("acres", "Two parcels added")), after.getValue());
    }

    @Test
    void clearingALinkThatDoesNotExistRecordsNothing() {
        Property created = createProperty();

        propertyService.setFieldLink(created.uuid(), "acres", null);

        verify(auditService, never()).recordUpdate(any(), any(), any(), any());
    }
}
