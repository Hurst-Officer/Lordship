package io.github.lordship.propertyassignments.internal;

import io.github.lordship.propertyassignments.PropertyAssignment;

import java.time.LocalDateTime;
import java.util.UUID;

public record PropertyAssignmentResponse(
        UUID id,
        UUID agentId,
        UUID propertyId,
        UUID assignedBy,
        LocalDateTime assignedAt
) {

    public static PropertyAssignmentResponse from(PropertyAssignment assignment) {
        return new PropertyAssignmentResponse(
                assignment.uuid(),
                assignment.agentId(),
                assignment.propertyId(),
                assignment.assignedBy(),
                assignment.assignedAt()
        );
    }
}
