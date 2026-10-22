package io.github.lordship.access.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class AgentSessionRepository {

    private final JdbcClient jdbc;

    public AgentSessionRepository(JdbcClient jdbcClient) {
        this.jdbc = jdbcClient;
    }

    // The database clock sets both created_at and expires_at, so the two always agree.
    public AgentSessionRow start(UUID agentId, String tokenHash, String ipAddress,
                                 String browserClient, String browserOs, int maxDays) {
        return jdbc.sql("""
                INSERT INTO agent_session (
                        agent_id, token_hash, ip_address, browser_client, browser_os, expires_at
                    ) VALUES (
                        :agentId, :tokenHash, :ipAddress, :browserClient, :browserOs,
                        now() + :maxDays * interval '1 day'
                    ) RETURNING *
                """)
                .param("agentId", agentId)
                .param("tokenHash", tokenHash)
                .param("ipAddress", ipAddress)
                .param("browserClient", browserClient)
                .param("browserOs", browserOs)
                .param("maxDays", maxDays)
                .query(AgentSessionRow.class)
                .single();
    }

    // Ended sessions are returned too. The caller decides what an ended one means.
    public Optional<AgentSessionRow> findByTokenHash(String tokenHash) {
        return jdbc.sql("SELECT * FROM agent_session WHERE token_hash = :tokenHash")
                .param("tokenHash", tokenHash)
                .query(AgentSessionRow.class)
                .optional();
    }

    // The session that once used this token, however many swaps ago.
    public Optional<AgentSessionRow> findBySpentTokenHash(String tokenHash) {
        return jdbc.sql("""
                SELECT s.* FROM agent_session s
                JOIN agent_session_spent_token t ON t.session_id = s.uuid
                WHERE t.token_hash = :tokenHash
                """)
                .param("tokenHash", tokenHash)
                .query(AgentSessionRow.class)
                .optional();
    }

    // Kept until the session row goes, so an old copy is still caught months later.
    public void markSpent(UUID sessionId, String tokenHash) {
        jdbc.sql("INSERT INTO agent_session_spent_token (token_hash, session_id) VALUES (:tokenHash, :sessionId)")
                .param("tokenHash", tokenHash)
                .param("sessionId", sessionId)
                .update();
    }

    // False when another request swapped the token first, or the session has ended.
    public boolean rotate(UUID sessionId, String oldHash, String newHash, String ipAddress) {
        return jdbc.sql("""
                UPDATE agent_session
                SET token_hash = :newHash,
                    last_used_at = now(),
                    ip_address = :ipAddress
                WHERE uuid = :sessionId AND token_hash = :oldHash AND ended_at IS NULL
                """)
                .param("sessionId", sessionId)
                .param("oldHash", oldHash)
                .param("newHash", newHash)
                .param("ipAddress", ipAddress)
                .update() > 0;
    }

    public boolean end(UUID sessionId) {
        return jdbc.sql("UPDATE agent_session SET ended_at = now() WHERE uuid = :sessionId AND ended_at IS NULL")
                .param("sessionId", sessionId)
                .update() > 0;
    }

    public int endAllForAgent(UUID agentId) {
        return jdbc.sql("UPDATE agent_session SET ended_at = now() WHERE agent_id = :agentId AND ended_at IS NULL")
                .param("agentId", agentId)
                .update();
    }
}
