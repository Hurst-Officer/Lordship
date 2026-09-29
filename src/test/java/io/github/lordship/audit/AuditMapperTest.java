package io.github.lordship.audit;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class AuditMapperTest {

    private record Sample(String name, Integer year, Map<String, Object> fields) {}

    @Test
    void diff_isEmpty_whenNothingChanged() {
        // Arrange
        Sample before = new Sample("Park", 1978, Map.of("Gate code", "4412"));
        Sample after = new Sample("Park", 1978, Map.of("Gate code", "4412"));

        // Act
        AuditMapper.Diff diff = AuditMapper.diff(before, after);

        // Assert
        assertTrue(diff.before().isEmpty());
        assertTrue(diff.after().isEmpty());
    }

    @Test
    void diff_keepsOnlyTheFieldsThatChanged() {
        // Arrange
        Sample before = new Sample("Park", 1978, Map.of());
        Sample after = new Sample("Renamed Park", 1978, Map.of());

        // Act
        AuditMapper.Diff diff = AuditMapper.diff(before, after);

        // Assert
        assertEquals(Map.of("name", "Park"), diff.before());
        assertEquals(Map.of("name", "Renamed Park"), diff.after());
    }

    @Test
    void diff_ofAMapField_keepsOnlyTheEntriesThatChanged() {
        // Arrange
        Sample before = new Sample("Park", 1978, Map.of("Gate code", "4412", "Pool guy", "Bob"));
        Sample after = new Sample("Park", 1978, Map.of("Gate code", "4412", "Pool guy", "Rob"));

        // Act
        AuditMapper.Diff diff = AuditMapper.diff(before, after);

        // Assert
        assertEquals(Map.of("fields", Map.of("Pool guy", "Bob")), diff.before());
        assertEquals(Map.of("fields", Map.of("Pool guy", "Rob")), diff.after());
    }

    @Test
    void diff_ofAMapField_showsAnAddedEntryAsNullBefore() {
        // Arrange
        Sample before = new Sample("Park", 1978, Map.of("Gate code", "4412"));
        Sample after = new Sample("Park", 1978, Map.of("Gate code", "4412", "Pool guy", "Bob"));

        // Act
        AuditMapper.Diff diff = AuditMapper.diff(before, after);

        // Assert
        assertEquals(Map.of("fields", Collections.singletonMap("Pool guy", null)), diff.before());
        assertEquals(Map.of("fields", Map.of("Pool guy", "Bob")), diff.after());
    }

    @Test
    void diff_ofAMapField_showsARemovedEntryAsNullAfter() {
        // Arrange
        Sample before = new Sample("Park", 1978, Map.of("Gate code", "4412", "Pool guy", "Bob"));
        Sample after = new Sample("Park", 1978, Map.of("Gate code", "4412"));

        // Act
        AuditMapper.Diff diff = AuditMapper.diff(before, after);

        // Assert
        assertEquals(Map.of("fields", Map.of("Pool guy", "Bob")), diff.before());
        assertEquals(Map.of("fields", Collections.singletonMap("Pool guy", null)), diff.after());
    }

    @Test
    void diff_ofAMapField_isEmpty_whenTheSameEntriesComeInAnotherOrder() {
        // Arrange
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("Gate code", "4412");
        first.put("Pool guy", "Bob");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("Pool guy", "Bob");
        second.put("Gate code", "4412");

        // Act
        AuditMapper.Diff diff = AuditMapper.diff(new Sample("Park", 1978, first), new Sample("Park", 1978, second));

        // Assert
        assertTrue(diff.before().isEmpty());
        assertTrue(diff.after().isEmpty());
    }
}
