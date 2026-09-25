package io.github.lordship.documentfiles.internal;

import io.github.lordship.documentfiles.DocumentFile;

import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

/** One row of document_file. Component order matches the column order in V9. */
public record DocumentFileRow(
        UUID uuid,
        String fileName,
        String contentType,
        long byteSize,
        byte[] sha256,
        String storageKey,
        OffsetDateTime uploadedAt,
        UUID uploadedBy
) {

    public DocumentFile toDocumentFile() {
        return new DocumentFile(
                uuid,
                fileName,
                contentType,
                byteSize,
                HexFormat.of().formatHex(sha256),
                storageKey,
                uploadedAt,
                uploadedBy);
    }
}
