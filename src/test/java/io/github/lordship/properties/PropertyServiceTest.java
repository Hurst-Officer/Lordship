package io.github.lordship.properties;

import io.github.lordship.audit.AuditService;
import io.github.lordship.properties.internal.PropertyCreateRequest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Nested
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class PropertyServiceTest {

    @Autowired
    private PropertyService propertyService;

    @MockitoBean
    AuditService auditService;

    private PropertyCreateRequest buildRequest() {
        return new PropertyCreateRequest(
                "Test Mobile Park",
                "999 Test Ave",
                "Testville",
                "WA",
                "98000"
        );
    }

    @Test
    void createPropertyReturnsPropertyWithGeneratedFields() {
        Property created = propertyService.createProperty("Test Mobile Park", "999 Test Ave", "Testville", "WA", "98000");

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
void findByPropertyCodeReturnsCreatedProperty() {
    Property created = propertyService.createProperty("Test Mobile Park", "999 Test Ave", "Testville", "WA", "98000");

    Optional<Property> found = propertyService.findByPropertyId(created.uuid());

    assertTrue(found.isPresent());
    assertEquals(created.uuid(), found.get().uuid());
}


@Test
void findAllIncludesCreatedProperty() {
    Property created = propertyService.createProperty("Test Mobile Park", "999 Test Ave", "Testville", "WA", "98000");

    List<Property> all = propertyService.findAll();

    assertTrue(all.stream().anyMatch(p -> p.uuid().equals(created.uuid())));
}

    // ---- audit: a patch logs only what changed, and nothing when nothing changed ----

    private Property createForAudit() {
        return propertyService.createProperty("Test Mobile Park", "999 Test Ave", "Testville", "WA", "98000");
    }

    @Test
    void patchPropertyRecordsNothing_whenTheValueIsTheSame() {
        Property created = createForAudit();

        propertyService.patchProperty(created.uuid(), new HashMap<>(Map.of("property_name", "Test Mobile Park")));

        verify(auditService, never()).recordUpdate(any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void patchPropertyRecordsOnlyTheFieldThatChanged() {
        Property created = createForAudit();
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
        Property created = createForAudit();
        Map<String, Object> fields = Map.of("Gate code", "4412");
        propertyService.patchProperty(created.uuid(), new HashMap<>(Map.of("custom_fields", fields)));

        propertyService.patchProperty(created.uuid(), new HashMap<>(Map.of("custom_fields", fields)));

        // only the first patch changed anything
        verify(auditService, times(1)).recordUpdate(any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void patchPropertyRecordsOnlyTheCustomFieldEntriesThatChanged() {
        Property created = createForAudit();
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
}
