package io.github.lordship.documentfiles;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One stored file: a generated lease PDF, a signed scan, a proof of service.
 *
 * <p>The bytes live in file storage. This is what the database knows about them.
 * sha256 is the file's fingerprint, written as hex. Two rows with the same
 * fingerprint are the same file uploaded twice.
 */
public record DocumentFile(
        UUID uuid,
        String fileName,
        String contentType,
        long byteSize,
        String sha256,
        String storageKey,
        OffsetDateTime uploadedAt,
        UUID uploadedBy
) {}
