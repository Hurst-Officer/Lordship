package io.github.lordship.access.internal.agents;

import io.github.lordship.access.Agent;
import org.springframework.modulith.NamedInterface;

import java.util.UUID;

// The agent's work details as they see them on their own page (GET /api/screens/self, PATCH /api/agents/self).
// workPhone, hoursAvailable and color are theirs to change. title is set in Personnel, workEmail is how they sign in.
// color is the color coworkers see them in (one of AgentColors.NAMES), or null until they pick one.
// Shared with the screens module, so the self screen sends the agent in this same shape
@NamedInterface("responses")
public record AgentOwnResponse(
        UUID id,
        String workEmail,
        String workPhone,
        String title,
        String hoursAvailable,
        String color
) {
    public static AgentOwnResponse from(Agent agent) {
        return new AgentOwnResponse(
                agent.uuid(),
                agent.workEmail(),
                agent.workPhone(),
                agent.title(),
                agent.hoursAvailable(),
                agent.color()
        );
    }
}
