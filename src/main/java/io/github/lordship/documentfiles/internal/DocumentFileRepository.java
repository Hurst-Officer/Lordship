package io.github.lordship.documentfiles.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** document_file rows. No patch and no delete: a stored file is a record of what was sent or received. */
@Repository
public class DocumentFileRepository {

    private final JdbcClient jdbc;

    public DocumentFileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** uuid and uploaded_at are set by the database. */
    public DocumentFileRow save(DocumentFileRow row) {
        return jdbc.sql("""
                        INSERT INTO document_file (
                            file_name, content_type, byte_size, sha256, storage_key, uploaded_by
                        ) VALUES (
                            :fileName, :contentType, :byteSize, :sha256, :storageKey, :uploadedBy
                        )
                        RETURNING *
                        """)
                .param("fileName", row.fileName())
                .param("contentType", row.contentType())
                .param("byteSize", row.byteSize())
                .param("sha256", row.sha256())
                .param("storageKey", row.storageKey())
                .param("uploadedBy", row.uploadedBy())
                .query(DocumentFileRow.class)
                .single();
    }

    public Optional<DocumentFileRow> findById(UUID uuid) {
        return jdbc.sql("SELECT * FROM document_file WHERE uuid = :uuid")
                .param("uuid", uuid)
                .query(DocumentFileRow.class)
                .optional();
    }
}
