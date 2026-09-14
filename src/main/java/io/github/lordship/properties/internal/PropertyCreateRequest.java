package io.github.lordship.properties.internal;

import jakarta.validation.constraints.NotBlank;

public record PropertyCreateRequest(
        @NotBlank
        String propertyName,

        @NotBlank
        String propertyAddress
) {
}
