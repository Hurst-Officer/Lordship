package io.github.lordship.access.internal.agents;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class AgentRepository {

    private final JdbcClient jdbc;

    public AgentRepository(JdbcClient jdbcClient) {
        this.jdbc = jdbcClient;
    }

    public AgentRow save(AgentRow row) {
        return jdbc.sql("""
                INSERT INTO agent (
                        person_id, work_phone, work_email, agent_password
                    ) VALUES (
                        :personId, :workPhone, :workEmail, :agentPassword
                    ) RETURNING *
                """)
                .paramSource(row)
                .query(AgentRow.class)
                .single();
    }

    public int updatePassword(UUID agentId, String hashedPassword) {
        return jdbc.sql("""
            UPDATE agent SET agent_password = :hashedPassword
            WHERE uuid = :agentId AND deleted_at IS NULL
            """)
                .param("hashedPassword", hashedPassword)
                .param("agentId", agentId)
                .update();
    }

    // logs the agent out
    public boolean revokeTokens(UUID agentId) {
        // note we're using clock_timestamp(), not now(): now() is the transaction's start time.
        return jdbc.sql("""
            UPDATE agent SET tokens_valid_from = date_trunc('second', clock_timestamp()) 
            WHERE uuid = :agentId AND deleted_at IS NULL
            """)
                .param("agentId", agentId)
                .update() > 0;
    }

    public Optional<AgentRow> findById(UUID uuid) {
        return jdbc.sql("SELECT * FROM agent WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .query(AgentRow.class)
                .optional();
    }

    public Optional<AgentRow> findByWorkEmail(String workEmail) {
        // lower() on both sides matches the uq_agent_email_active index in V3
        return jdbc.sql("SELECT * FROM agent WHERE lower(work_email) = lower(:workEmail) AND deleted_at IS NULL")
                .param("workEmail", workEmail)
                .query(AgentRow.class)
                .optional();
    }

}