package io.github.lordship.instruments.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * move_in_file rows: a move-in's uploads (today the signed scan of the lease). Writes go through SignedScans, which
 * records them in the audit log. The bytes are a document_file (DocumentFileService).
 */
@Repository
public class MoveInFileRepository {

    // sql: an upload with the stored file's name and size, and the uploader's name (agent and person read by name,
    // the way the audit log reads them). page_check is read as text and parsed by SignedScans.
    private static final String SELECT = """
            SELECT m.uuid, m.tenancy_id, m.instrument, m.file, m.kind, m.restricted, m.pages,
                   m.page_check::text AS page_check, m.created_at, m.created_by, m.deleted_at,
                   d.file_name, d.byte_size, pe.name_full AS created_by_name
            FROM move_in_file m
            JOIN document_file d ON d.uuid = m.file
            LEFT JOIN agent a ON a.uuid = m.created_by
            LEFT JOIN person pe ON pe.uuid = a.person_id
            """;

    private final JdbcClient jdbc;

    public MoveInFileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // One row of move_in_file, the names the audit log keeps the row under. pageCheck is the JSON text.
    public record MoveInFileRow(UUID uuid, UUID tenancyId, UUID instrument, UUID file, String kind,
                                boolean restricted, Integer pages, String pageCheck, OffsetDateTime createdAt,
                                UUID createdBy, OffsetDateTime deletedAt) { }

    // An upload as the screens show the upload: the row, the stored file's name and size, and who uploaded
    public record MoveInFileLine(UUID uuid, UUID tenancyId, UUID instrument, UUID file, String kind,
                                 boolean restricted, Integer pages, String pageCheck, OffsetDateTime createdAt,
                                 UUID createdBy, OffsetDateTime deletedAt, String fileName, long byteSize,
                                 String createdByName) { }

    public MoveInFileRow insert(UUID tenancyId, UUID instrument, UUID file, String kind, Integer pages,
                                String pageCheckJson, UUID createdBy) {
        return jdbc.sql("""
                INSERT INTO move_in_file (tenancy_id, instrument, file, kind, pages, page_check, created_by)
                VALUES (:tenancyId, :instrument, :file, :kind, :pages, CAST(:pageCheck AS jsonb), :createdBy)
                RETURNING uuid, tenancy_id, instrument, file, kind, restricted, pages, page_check::text AS page_check,
                          created_at, created_by, deleted_at""")
                .param("tenancyId", tenancyId)
                .param("instrument", instrument)
                .param("file", file)
                .param("kind", kind)
                .param("pages", pages)
                .param("pageCheck", pageCheckJson)
                .param("createdBy", createdBy)
                .query(MoveInFileRow.class)
                .single();
    }

    // A tenancy's live uploads of one kind, the newest first
    public List<MoveInFileLine> findByTenancy(UUID tenancyId, String kind) {
        return jdbc.sql(SELECT + """
                WHERE m.tenancy_id = :tenancyId AND m.kind = :kind AND m.deleted_at IS NULL
                ORDER BY m.created_at DESC, m.uuid DESC""")
                .param("tenancyId", tenancyId)
                .param("kind", kind)
                .query(MoveInFileLine.class)
                .list();
    }

    // One upload, deleted or not
    public Optional<MoveInFileLine> findById(UUID uuid) {
        return jdbc.sql(SELECT + "WHERE m.uuid = :uuid")
                .param("uuid", uuid)
                .query(MoveInFileLine.class)
                .optional();
    }
}
