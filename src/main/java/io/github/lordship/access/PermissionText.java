package io.github.lordship.access;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * The words shown for a permission and its group, in the language of the request.
 * The text lives in messages.properties and messages_es.properties.
 */
@Component
public class PermissionText {

    private final MessageSource messages;

    public PermissionText(MessageSource messages) {
        this.messages = messages;
    }

    public String label(String permissionName) {
        return resolve(permissionKey(permissionName) + ".label", permissionName);
    }

    public String description(String permissionName) {
        return resolve(permissionKey(permissionName) + ".description", "");
    }

    public String groupLabel(String groupKey) {
        return resolve(groupKey(groupKey) + ".label", groupKey);
    }

    // "agents:view" becomes "permission.agents.view". A colon would end a properties key.
    public static String permissionKey(String permissionName) {
        return "permission." + permissionName.replace(':', '.');
    }

    public static String groupKey(String groupKey) {
        return "permission_group." + groupKey;
    }

    // Missing text shows the fallback instead of failing the request.
    // PermissionTextIT fails the build when any text is missing.
    private String resolve(String key, String fallback) {
        return messages.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }
}
