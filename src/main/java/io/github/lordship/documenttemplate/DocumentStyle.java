package io.github.lordship.documenttemplate;

import io.github.lordship.shared.StyleTarget;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * CSS declarations an admin wrote for one document.
 *
 * <p>With a {@code target} it restyles that part of every page -- all section
 * titles, the page margins. Without one it is a named style that a clause or a
 * section picks by uuid, so renaming it never strips it off anything.
 */
public record DocumentStyle(
        UUID uuid,
        String name,
        String css,
        StyleTarget target,
        String note,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {
    public boolean isSoftDeleted() {
        return deletedAt != null;
    }

    /** A style something picks, as opposed to one that restyles a part of every page. */
    public boolean isNamed() {
        return target == null;
    }
}
