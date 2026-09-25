package io.github.lordship.documentfiles;

import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.documentfiles.internal.DocumentFileRepository;
import io.github.lordship.documentfiles.internal.DocumentFileRow;
import io.github.lordship.documentfiles.internal.FileStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.UUID;

/**
 * Saves files and hands them back.
 *
 * <p>Where the bytes go is decided by {@link FileStorage}. Today that is a
 * folder on this machine. Later it will be S3, and nothing outside this
 * package needs to change when it is.
 */
@Service
public class DocumentFileService {

    private final DocumentFileRepository documentFileRepository;
    private final FileStorage fileStorage;
    private final AuditService auditService;

    public DocumentFileService(DocumentFileRepository documentFileRepository,
                               FileStorage fileStorage,
                               AuditService auditService) {
        this.documentFileRepository = documentFileRepository;
        this.fileStorage = fileStorage;
        this.auditService = auditService;
    }

    /**
     * Records the file, then writes its bytes.
     *
     * <p>The row is written first. If writing the bytes fails, the exception
     * rolls the row back too, so there is never a row pointing at nothing.
     *
     * <p>storageKey must be new. Storage never overwrites an existing file.
     */
    @Transactional
    public DocumentFile store(String storageKey,
                              String fileName,
                              String contentType,
                              byte[] content,
                              UUID uploadedBy) {
        DocumentFileRow saved = documentFileRepository.save(new DocumentFileRow(
                null,
                fileName,
                contentType,
                content.length,
                sha256(content),
                storageKey,
                null,
                uploadedBy));

        fileStorage.write(storageKey, content);

        auditService.recordInsert("document_file", saved.uuid(), AuditMapper.toMap(saved));
        return saved.toDocumentFile();
    }

    public Optional<DocumentFile> findById(UUID uuid) {
        return documentFileRepository.findById(uuid).map(DocumentFileRow::toDocumentFile);
    }

    /** The file's record and its bytes. Empty if there is no such file. */
    public Optional<StoredFile> read(UUID uuid) {
        return findById(uuid)
                .map(file -> new StoredFile(file, fileStorage.read(file.storageKey())));
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            // Every Java runtime is required to have SHA-256.
            throw new IllegalStateException(e);
        }
    }
}
