package io.github.lordship.globalsettings.internal;

import io.github.lordship.globalsettings.GlobalSettings;

import java.time.OffsetDateTime;

public record GlobalSettingsResponse(
        String complianceEmail,
        OffsetDateTime updatedAt
) {

    public static GlobalSettingsResponse from(GlobalSettings settings) {
        return new GlobalSettingsResponse(settings.complianceEmail(), settings.updatedAt());
    }
}
