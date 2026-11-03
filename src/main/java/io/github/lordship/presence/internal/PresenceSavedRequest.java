package io.github.lordship.presence.internal;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Body of POST /api/presence/saved, sent by a window after a save went through.
 * place is the screen it was saved on. field is the box, or a word for a list change ("lots").
 */
public record PresenceSavedRequest(
        @NotNull UUID client,
        String place,
        String field
) { }
