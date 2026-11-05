package io.github.lordship.audit.internal;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

// Rows read by AuditRepository for the screens' history
public final class HistoryRows {

    private HistoryRows() { }

    // One field of one record on one day: how many changes, and who made them
    public record ActiveDayRow(String recordId, String field, LocalDate day, int changes, String names) { }

    // Who did something to a record, and when
    public record MarkRow(String recordId, OffsetDateTime changedAt, String name) { }

    // One audit row with its author's name, and how many other records of the same table its save changed
    public record EntryRow(UUID uuid, OffsetDateTime changedAt, String name, String operation,
                           String valueBefore, String valueAfter, int together) { }
}
