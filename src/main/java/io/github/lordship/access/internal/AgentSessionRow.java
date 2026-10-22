package io.github.lordship.access.internal;

import java.time.OffsetDateTime;
import java.util.UUID;

// sql: agent_session in V3
public record AgentSessionRow(
        UUID uuid,
        UUID agentId,
        String tokenHash,
        String previousHash,
        String ipAddress,
        String browserClient,
        String browserOs,
        OffsetDateTime createdAt,
        OffsetDateTime lastUsedAt,
        OffsetDateTime expiresAt,
        OffsetDateTime endedAt
) {
}
