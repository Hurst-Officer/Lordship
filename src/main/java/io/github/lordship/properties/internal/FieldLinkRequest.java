package io.github.lordship.properties.internal;

// field is a column name like "property_zoning", or a custom field label.
// A null or blank url clears the link.
public record FieldLinkRequest(
        String field,
        String url
) {}
