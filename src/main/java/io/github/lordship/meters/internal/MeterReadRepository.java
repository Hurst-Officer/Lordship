package io.github.lordship.meters.internal;

import io.github.lordship.meters.MeterRead;
import io.github.lordship.meters.MeterReadKind;
import io.github.lordship.meters.MeterReadSource;
import io.github.lordship.shared.Patches;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Reads of mains and submeters. Exactly one of main_id, submeter_id is set on each.
// Every query names the one column it filters on, so the (main_id, read_on) and
// (submeter_id, read_on) indexes are used.
@Repository
public class MeterReadRepository {

    public static final String TABLE = "meter_read";
    public static final Set<String> COLUMNS = Set.of("read_on", "reading", "kind", "is_estimated", "notes");
    private static final Map<String, String> CASTS = Map.of("kind", "meter_read_kind");

    private final JdbcClient jdbc;

    public MeterReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // "main_id" or "submeter_id", whichever this meter is
    private static String column(UUID mainId) {
        return mainId != null ? "main_id" : "submeter_id";
    }

    private static UUID id(UUID mainId, UUID submeterId) {
        return mainId != null ? mainId : submeterId;
    }

    public MeterRead insert(UUID mainId, UUID submeterId, LocalDate readOn, long reading, MeterReadKind kind,
                            MeterReadSource source, boolean isEstimated, String notes) {
        return jdbc.sql("""
                        INSERT INTO meter_read (main_id, submeter_id, read_on, reading, kind, source, is_estimated, notes)
                        VALUES (:mainId, :submeterId, :readOn, :reading, :kind::meter_read_kind,
                                :source::meter_read_source, :isEstimated, :notes)
                        RETURNING *
                        """)
                .param("mainId", mainId).param("submeterId", submeterId)
                .param("readOn", readOn).param("reading", reading)
                .param("kind", kind.name()).param("source", source.name())
                .param("isEstimated", isEstimated).param("notes", notes)
                .query(MeterRead.class).single();
    }

    public Optional<MeterRead> find(UUID uuid) {
        return jdbc.sql("SELECT * FROM meter_read WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid).query(MeterRead.class).optional();
    }

    public Optional<MeterRead> findForUpdate(UUID uuid) {
        return jdbc.sql("SELECT * FROM meter_read WHERE uuid = :uuid AND deleted_at IS NULL FOR UPDATE")
                .param("uuid", uuid).query(MeterRead.class).optional();
    }

    public Optional<MeterRead> findAny(UUID uuid) {
        return jdbc.sql("SELECT * FROM meter_read WHERE uuid = :uuid")
                .param("uuid", uuid).query(MeterRead.class).optional();
    }

    // The live read of one meter on one day, if there is one
    public Optional<MeterRead> onDay(UUID mainId, UUID submeterId, LocalDate day) {
        return jdbc.sql("SELECT * FROM meter_read WHERE " + column(mainId) + " = :id AND read_on = :day AND deleted_at IS NULL")
                .param("id", id(mainId, submeterId)).param("day", day)
                .query(MeterRead.class).optional();
    }

    /**
     * One meter's live reads, oldest first: those from {@code since} on, and the
     * last one before it, so the first read in range still knows its previous.
     * since may be null for all of them.
     */
    public List<MeterRead> chain(UUID mainId, UUID submeterId, LocalDate since) {
        String col = column(mainId);
        return jdbc.sql("""
                        SELECT * FROM meter_read
                         WHERE %1$s = :id AND deleted_at IS NULL
                           AND (CAST(:since AS date) IS NULL OR read_on >= COALESCE(
                                (SELECT max(read_on) FROM meter_read
                                  WHERE %1$s = :id AND deleted_at IS NULL AND read_on < :since), :since))
                         ORDER BY read_on
                        """.formatted(col))
                .param("id", id(mainId, submeterId)).param("since", since)
                .query(MeterRead.class).list();
    }

    // Live reads of every live meter in a property from a day to a day (and each meter's read before that), oldest first
    public List<MeterRead> ofProperty(UUID propertyId, LocalDate since, LocalDate until) {
        return jdbc.sql("""
                        SELECT r.* FROM meter_main m
                          JOIN meter_read r ON r.main_id = m.uuid AND r.deleted_at IS NULL AND r.read_on <= :until
                               AND r.read_on >= COALESCE((SELECT max(x.read_on) FROM meter_read x
                                                           WHERE x.main_id = m.uuid AND x.deleted_at IS NULL
                                                             AND x.read_on < :since), :since)
                         WHERE m.property_id = :propertyId AND m.deleted_at IS NULL
                        UNION ALL
                        SELECT r.* FROM submeter s
                          JOIN meter_read r ON r.submeter_id = s.uuid AND r.deleted_at IS NULL AND r.read_on <= :until
                               AND r.read_on >= COALESCE((SELECT max(x.read_on) FROM meter_read x
                                                           WHERE x.submeter_id = s.uuid AND x.deleted_at IS NULL
                                                             AND x.read_on < :since), :since)
                         WHERE s.property_id = :propertyId AND s.deleted_at IS NULL
                        ORDER BY read_on
                        """)
                .param("propertyId", propertyId).param("since", since).param("until", until)
                .query(MeterRead.class).list();
    }

    public List<MeterRead> deletedOf(UUID mainId, UUID submeterId) {
        return jdbc.sql("SELECT * FROM meter_read WHERE " + column(mainId) + " = :id AND deleted_at IS NOT NULL ORDER BY deleted_at DESC")
                .param("id", id(mainId, submeterId)).query(MeterRead.class).list();
    }

    // The property a read's meter belongs to, whether or not the read or meter is deleted
    public Optional<UUID> propertyOf(UUID readId) {
        return jdbc.sql("""
                        SELECT COALESCE(m.property_id, s.property_id) FROM meter_read r
                          LEFT JOIN meter_main m ON m.uuid = r.main_id
                          LEFT JOIN submeter   s ON s.uuid = r.submeter_id
                         WHERE r.uuid = :uuid
                        """)
                .param("uuid", readId).query(UUID.class).optional();
    }

    // A read a live bill names as its prior or current read
    public boolean isOnBill(UUID readId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM utility_bill_meter bm
                                         JOIN utility_bill b ON b.uuid = bm.bill_id
                                        WHERE (bm.prior_read_id = :id OR bm.current_read_id = :id)
                                          AND bm.deleted_at IS NULL AND b.deleted_at IS NULL)
                        """)
                .param("id", readId).query(Boolean.class).single();
    }

    /**
     * Whether a live bill counts this main's usage across the day: its old read is
     * before it and its new read after it. A read added, changed or removed on such
     * a day changes that bill's usage.
     */
    public boolean insideBill(UUID mainId, LocalDate day) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM utility_bill_meter bm
                              JOIN utility_bill b ON b.uuid = bm.bill_id AND b.deleted_at IS NULL
                              JOIN meter_read p ON p.uuid = bm.prior_read_id
                              JOIN meter_read c ON c.uuid = bm.current_read_id
                             WHERE bm.main_id = :mainId AND bm.deleted_at IS NULL
                               AND p.read_on < :day AND :day < c.read_on)
                        """)
                .param("mainId", mainId).param("day", day).query(Boolean.class).single();
    }

    public Optional<MeterRead> patch(UUID uuid, Map<String, Object> changes) {
        if (changes.isEmpty()) return find(uuid);
        for (String col : changes.keySet()) {
            if (!COLUMNS.contains(col)) throw new IllegalArgumentException("Invalid column: " + col);
        }
        Map<String, Object> params = new HashMap<>();
        changes.forEach((col, val) -> params.put(col, val instanceof Enum<?> e ? e.name() : val));
        params.put("uuid", uuid);
        return jdbc.sql(Patches.updateSql(TABLE, changes, CASTS, true))
                .params(params).query(MeterRead.class).optional();
    }

    public boolean softDelete(UUID uuid) {
        return jdbc.sql("UPDATE meter_read SET deleted_at = now() WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid).update() > 0;
    }

    public boolean restore(UUID uuid) {
        return jdbc.sql("UPDATE meter_read SET deleted_at = NULL WHERE uuid = :uuid AND deleted_at IS NOT NULL")
                .param("uuid", uuid).update() > 0;
    }
}
