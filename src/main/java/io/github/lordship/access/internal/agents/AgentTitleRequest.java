package io.github.lordship.access.internal.agents;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Body of PUT /api/agents/{uuid}/title. A blank title is refused by AgentService.
public record AgentTitleRequest(
        @NotNull
        @Size(max = 80)
        String title
) {
}
