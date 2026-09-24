package io.github.lordship.access;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentLoginRequest (

        @Email
        @NotBlank
        @Size(max = 120)
        String workEmail,

        @NotBlank
        String password

) {

    @Override
    public String toString() {
        return "AgentLoginRequest";
    }
}
