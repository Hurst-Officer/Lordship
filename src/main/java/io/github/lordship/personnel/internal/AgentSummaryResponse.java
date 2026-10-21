package io.github.lordship.personnel.internal;

import java.util.List;
import java.util.UUID;

// One line of the name search (GET /api/personnel/search)
public record AgentSummaryResponse(
        UUID agentId,
        String nameFull,
        List<String> roleNames
) { }
