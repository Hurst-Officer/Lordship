package io.github.lordship.access.internal.agents;

import io.github.lordship.access.internal.AgentSessionRow;

import java.time.OffsetDateTime;
import java.util.UUID;

// One signed-in computer, for GET /api/agents/{uuid}/sessions. The token hash never leaves the server.
public record AgentSessionResponse(
        UUID id,
        String browserOs,
        String browserClient,
        String ipAddress,
        OffsetDateTime createdAt,
        OffsetDateTime lastUsedAt,
        OffsetDateTime expiresAt,
        boolean thisComputer
) {
    // thisComputer is true for the session the caller is using right now.
    public static AgentSessionResponse from(AgentSessionRow row, UUID callerSessionId) {
        return new AgentSessionResponse(
                row.uuid(),
                row.browserOs(),
                row.browserClient(),
                row.ipAddress(),
                row.createdAt(),
                row.lastUsedAt(),
                row.expiresAt(),
                row.uuid().equals(callerSessionId)
        );
    }
}
