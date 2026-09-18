package io.github.lordship.globalsettings.internal;

import io.github.lordship.globalsettings.GlobalSettings;

import java.time.OffsetDateTime;
import java.util.UUID;

/** The one row of {@code global_settings}. All simple types, so no RowMapper. */
public record GlobalSettingsRow(
        int id,
        UUID uuid,
        String complianceEmail,
        OffsetDateTime updatedAt
) {

    public GlobalSettings toGlobalSettings() {
        return new GlobalSettings(this.uuid, this.complianceEmail, this.updatedAt);
    }
}
