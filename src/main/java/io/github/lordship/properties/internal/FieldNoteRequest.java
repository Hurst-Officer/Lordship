package io.github.lordship.properties.internal;

// field is a column name like "property_zoning", or a custom field label.
// A null or blank note clears the note.
public record FieldNoteRequest(
        String field,
        String note
) {}
