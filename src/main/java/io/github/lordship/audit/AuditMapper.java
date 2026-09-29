package io.github.lordship.audit;

import java.lang.reflect.RecordComponent;
import java.util.*;

public class AuditMapper {

    private AuditMapper() {}

    public record Diff(Map<String, Object> before, Map<String, Object> after) {}

    public static Diff diff(Record before, Record after){
        Map<String, Object> beforeMap = toMap(before);
        Map<String, Object> afterMap = toMap(after);

        Map<String, Object> changedBefore = new LinkedHashMap<>();
        Map<String, Object> changedAfter = new LinkedHashMap<>();

        for (String key : afterMap.keySet()) {
            Object oldVal = beforeMap.get(key);
            Object newVal = afterMap.get(key);
            if (Objects.equals(oldVal, newVal)) {
                continue;
            }
            if (oldVal instanceof Map<?, ?> oldMap && newVal instanceof Map<?, ?> newMap) {
                // A map field logs only the entries that changed, not the whole map.
                // An entry that was added shows null before. One that was removed shows null after.
                Map<Object, Object> oldEntries = new LinkedHashMap<>();
                Map<Object, Object> newEntries = new LinkedHashMap<>();
                Set<Object> entryKeys = new LinkedHashSet<>(oldMap.keySet());
                entryKeys.addAll(newMap.keySet());
                for (Object entryKey : entryKeys) {
                    Object oldEntry = oldMap.get(entryKey);
                    Object newEntry = newMap.get(entryKey);
                    if (!Objects.equals(oldEntry, newEntry)) {
                        oldEntries.put(entryKey, oldEntry);
                        newEntries.put(entryKey, newEntry);
                    }
                }
                changedBefore.put(key, oldEntries);
                changedAfter.put(key, newEntries);
            } else {
                changedBefore.put(key, oldVal);
                changedAfter.put(key, newVal);
            }
        }
        return new Diff(changedBefore, changedAfter);
    }

    public static Map<String, Object> toMap(Record record) {
        Map<String, Object> map = new LinkedHashMap<>();

        for (RecordComponent component : record.getClass().getRecordComponents()) {
            try {
                String name = component.getName();
                Object value = component.getAccessor().invoke(record);
                map.put(name, value);
            } catch (Exception e) {
                throw new RuntimeException("Failed to map record for audit", e);
            }
        }
        return map;
    }
}
