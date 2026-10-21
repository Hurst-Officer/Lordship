package io.github.lordship.access;

import java.util.UUID;

// A permission with the key of the group it is shown under in the role editor.
public record GroupedPermission(
        UUID uuid,
        String permissionName,
        String groupKey
) { }
