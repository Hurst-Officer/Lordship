package io.github.lordship.globalsettings.internal;

import io.github.lordship.globalsettings.GlobalSettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * The company's own details. No path variable and no POST: there is one row,
 * and the only thing anyone does to it is read it or change a field.
 */
@RestController
@RequestMapping("/api/global-settings")
public class GlobalSettingsController {

    private static final Map<String, String> PATCHABLE_COLUMNS =
            Map.ofEntries(Map.entry("complianceEmail", "compliance_email"));

    private final GlobalSettingsService settingsService;

    public GlobalSettingsController(GlobalSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @PreAuthorize("hasAuthority('global_settings:view')")
    @GetMapping
    public ResponseEntity<GlobalSettingsResponse> get() {
        return settingsService.find()
                .map(GlobalSettingsResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('global_settings:edit')")
    @PatchMapping
    public ResponseEntity<GlobalSettingsResponse> patch(@RequestBody Map<String, Object> request) {
        Map<String, Object> changes = new HashMap<>();
        PATCHABLE_COLUMNS.forEach((jsonField, column) -> {
            if (request.containsKey(jsonField)) {
                changes.put(column, request.get(jsonField));
            }
        });

        return settingsService.patch(changes)
                .map(GlobalSettingsResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
