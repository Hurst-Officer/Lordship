package io.github.lordship.globalsettings;

import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.globalsettings.internal.GlobalSettingsRepository;
import io.github.lordship.globalsettings.internal.GlobalSettingsRow;
import io.github.lordship.shared.InvalidRequest;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Company-wide facts, read by anything that prints them.
 *
 * <p>Read far more often than written -- every generated document asks for the
 * compliance address -- so {@link #require} exists to say plainly when the row
 * is missing rather than letting a null reach a lease.
 */
@Service
public class GlobalSettingsService {

    private final GlobalSettingsRepository settingsRepository;
    private final AuditService auditService;

    public GlobalSettingsService(GlobalSettingsRepository settingsRepository,
                                 AuditService auditService) {
        this.settingsRepository = settingsRepository;
        this.auditService = auditService;
    }

    public Optional<GlobalSettings> find() {
        return settingsRepository.find().map(GlobalSettingsRow::toGlobalSettings);
    }

    /**
     * The settings, or a failure naming what is wrong. Generation calls this:
     * a document that needs the compliance address should stop with "the
     * settings row is missing" rather than print an empty line where an email
     * belongs.
     */
    public GlobalSettings require() {
        return find().orElseThrow(() -> new EntityNotFoundException(
                "global_settings has no row -- the database did not finish setting itself up"));
    }

    // One email address: something before a single @, a domain with a dot in it after, and no spaces anywhere
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    // The longest an email address can be (RFC 5321)
    private static final int EMAIL_MAX = 254;

    /**
     * Changes some settings. requested is keyed by column. The compliance email is stripped, blank clears it,
     * and anything that is not one email address is refused before the row is touched:
     * InvalidRequest (400) on complianceEmail, global_settings.email_invalid.
     * Empty when the row is missing.
     */
    @Transactional
    public Optional<GlobalSettings> patch(Map<String, Object> requested) {
        Map<String, Object> changes = new HashMap<>(requested);
        if (changes.containsKey("compliance_email")) {
            changes.put("compliance_email", complianceEmail(changes.get("compliance_email")));
        }

        Optional<GlobalSettingsRow> beforeOpt = settingsRepository.find();
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        GlobalSettingsRow before = beforeOpt.get();

        Optional<GlobalSettingsRow> afterOpt = settingsRepository.patch(changes);
        if (afterOpt.isEmpty()) {
            return Optional.empty();
        }
        GlobalSettingsRow after = afterOpt.get();

        // updated_at moves on every write, so it is excluded from the
        // comparison -- otherwise every no-op patch would look like a change.
        AuditMapper.Diff diff = AuditMapper.diff(
                withoutTimestamp(before), withoutTimestamp(after));
        if (!diff.before().isEmpty()) {
            auditService.recordUpdate("global_settings", before.uuid(), diff.before(), diff.after());
        }
        return Optional.of(after.toGlobalSettings());
    }

    // The compliance email as it is stored: stripped, and null for blank text
    private static String complianceEmail(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw InvalidRequest.onField("complianceEmail", "global_settings.email_invalid");
        }
        String email = text.strip();
        if (email.isEmpty()) {
            return null;
        }
        if (email.length() > EMAIL_MAX || !EMAIL.matcher(email).matches()) {
            throw InvalidRequest.onField("complianceEmail", "global_settings.email_invalid");
        }
        return email;
    }

    private static GlobalSettingsRow withoutTimestamp(GlobalSettingsRow row) {
        return new GlobalSettingsRow(row.id(), row.uuid(), row.complianceEmail(), null);
    }
}
