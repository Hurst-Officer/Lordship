package io.github.lordship.properties.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PropertyCreateRequest(
        @NotBlank
        String propertyName,

        @NotBlank
        String propertyStreet,

        @NotBlank
        String propertyCity,

        @NotBlank
        @Pattern(regexp = "[A-Z]{2}", message = "{property.state_invalid}")
        String propertyState,

        @NotBlank
        @Pattern(regexp = "[0-9]{5}(-[0-9]{4})?", message = "{property.zip_invalid}")
        String propertyZip
) {
}
