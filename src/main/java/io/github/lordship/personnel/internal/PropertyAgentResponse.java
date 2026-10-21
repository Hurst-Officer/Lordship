package io.github.lordship.personnel.internal;

import java.util.List;
import java.util.UUID;

// One agent assigned to a property (GET /api/personnel/property/{propertyId})
public record PropertyAgentResponse(
        UUID agentId,
        String nameFull,
        String workEmail,
        String workPhone,
        List<String> roleNames
) { }
