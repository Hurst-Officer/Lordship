package io.github.lordship.access.internal.agents;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentRegistrationRequest (

    @NotBlank
    String nameFull,

    @Email
    String personalEmail,

    @Email
    @NotBlank
    @Size(max = 120, message = "Work email must be at most 120 characters")
    String workEmail,

    @Size(min = 7, max = 20, message = "Phone number must be between 7 and 20 characters")
    String workPhone,

    @NotBlank
    @Size(min = 12, max = 64, message = "Password must be at least 12 characters")
    String password

) {

    @Override
    public String toString() {
        return "AgentRegistrationRequest";
    }
}
