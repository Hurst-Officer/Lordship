package io.github.lordship.access;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Body of POST /api/agents/session/refresh and /api/agents/session/logout
public record RefreshTokenRequest(

        @NotBlank
        @Size(max = 100)
        String refreshToken

) {

    // Keeps the token out of logs.
    @Override
    public String toString() {
        return "RefreshTokenRequest";
    }
}
