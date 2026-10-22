package io.github.lordship.personnel.internal;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

// GET /api/personnel/agents/{agentId} -- one person.
// Never carries personal phone, personal email, birthday or SSN.
public record AgentDetailResponse(
        UUID agentId,
        String nameFull,
        String title,
        String workEmail,
        String workPhone,
        List<RoleRef> roles,
        List<PropertyRef> properties,

        // null when the caller lacks role_permissions:view, and then left out of the JSON
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<String> effectivePermissions
) {
    public record RoleRef(
            UUID roleId,
            String roleName
    ) { }

    public record PropertyRef(
            UUID assignmentId,
            UUID propertyId,
            String propertyCode,
            String propertyName
    ) { }
}
