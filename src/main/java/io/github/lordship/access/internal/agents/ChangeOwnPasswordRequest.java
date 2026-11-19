package io.github.lordship.access.internal.agents;

/**
 * The body of {@code PUT /api/agents/self/password}: the agent's current password and the new one.
 * Neither value is checked here. AgentService.changeOwnPassword refuses a bad value by code and field
 * (password.current_missing, password.length...), so the agent reads the reason in their own language,
 * under the box at fault.
 */
public record ChangeOwnPasswordRequest(String currentPassword, String newPassword) {

    // Keeps both passwords out of any log line that prints the request
    @Override
    public String toString() {
        return "ChangeOwnPasswordRequest";
    }
}
