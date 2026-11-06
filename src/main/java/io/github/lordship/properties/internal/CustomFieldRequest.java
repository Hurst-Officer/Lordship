package io.github.lordship.properties.internal;

// One custom field, like label "Septic" and value "Lagoon".
// The value is usually text, and can be empty.
public record CustomFieldRequest(
        String label,
        Object value
) {}
