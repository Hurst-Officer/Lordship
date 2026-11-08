package io.github.lordship.audit;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What Glade's screens show about who changed what, built from audit_log by AuditService.
 * The screens draw a bar under each field, with a bubble for each day the field changed.
 */
public final class History {

    private History() { }

    // A record on a screen: its table in audit_log ("property", "lot", "mobile_home") and its id
    public record Ref(String table, UUID id) { }

    // Who did something, and when. name is the agent's full name, or "The server" for the system.
    public record Mark(String name, OffsetDateTime at) { }

    // One day a field changed. names lists who changed it that day, like "Maria Ortiz, Toby Marsh".
    public record ActiveDay(LocalDate day, int changes, String names) { }

    // One record's history for its bars.
    // added is null when nobody recorded adding it, like a park loaded by a seed or an import.
    // fields: each changed field's days in the last three years, oldest first.
    // A changed entry of a map field also has its own key, like "customFields.Septic".
    public record RecordHistory(String table, UUID recordId, Mark added, Map<String, List<ActiveDay>> fields) { }

    // The history a screen loads with its data. lastEdit is the newest change to anything the page shows.
    public record PageHistory(Mark lastEdit, List<RecordHistory> records) { }

    // One field in one saved change. before is null for an insert, after is null for a delete.
    public record Change(String field, Object before, Object after) { }

    // One saved change to a record, with the asked-for fields that it touched.
    // together counts the other records the same save changed, like the other lots of a bulk edit.
    public record Entry(UUID id, OffsetDateTime at, String name, OperationType operation,
                        List<Change> changes, int together) { }

    // A page of entries, newest first. more is true when older ones remain.
    public record EntryPage(List<Entry> entries, boolean more) { }

    /**
     * One save the agent made, for "My activity" on their own page: one request's changes to one kind of record.
     * table is the audit table ("lot"); records counts the records it touched, recordId is the first of them.
     * fields are the fields an update changed (empty for an add or a delete). recordName, propertyId,
     * propertyName and lotId say what the record is, where the server knows the kind; null otherwise.
     * hidden is true when the record is under a property the agent may no longer see: then everything that
     * would say which record it was is left out.
     */
    public record Save(UUID id, OffsetDateTime at, String table, OperationType operation, int records,
                       String recordId, List<String> fields, String recordName,
                       UUID propertyId, String propertyName, UUID lotId, boolean hidden) {

        // The same save with nothing that names its record
        public Save withoutRecord() {
            return new Save(id, at, table, operation, records, null, List.of(), null, null, null, null, true);
        }
    }

    // A page of saves. more is true when more remain after them.
    public record SavePage(List<Save> saves, boolean more) { }
}
