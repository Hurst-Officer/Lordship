package io.github.lordship.properties.internal;

import io.github.lordship.audit.History;
import io.github.lordship.properties.Property;

import java.time.OffsetDateTime;
import java.util.UUID;

// One row of the deleted properties (GET /api/properties/deleted).
// deletedBy is the agent's full name from the audit log, or null without audit:view.
public record DeletedPropertyResponse(
        UUID id,
        String propertyCode,
        String propertyName,
        String propertyStreet,
        String propertyCity,
        String propertyState,
        String propertyZip,
        OffsetDateTime deletedAt,
        String deletedBy
) {

    public static DeletedPropertyResponse from(Property property, History.Mark deletion) {
        return new DeletedPropertyResponse(
                property.uuid(),
                property.propertyCode(),
                property.propertyName(),
                property.propertyStreet(),
                property.propertyCity(),
                property.propertyState(),
                property.propertyZip(),
                property.deletedAt(),
                deletion == null ? null : deletion.name()
        );
    }
}
