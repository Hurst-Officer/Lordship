package io.github.lordship.meters.internal;

import io.github.lordship.meters.MeterType;
import io.github.lordship.meters.SubmeterLotLink;
import io.github.lordship.meters.SubmeterMainLink;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// The tree's dated links: submeter → main, submeter → lot.
// effective_to is the first day a link no longer holds; null means it still does.
@Repository
public class MeterLinkRepository {

    public static final String MAIN_LINK = "submeter_main";
    public static final String LOT_LINK = "submeter_lot";

    private final JdbcClient jdbc;

    public MeterLinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public SubmeterMainLink insertMainLink(UUID submeterId, UUID mainId, UUID propertyId, MeterType type, LocalDate from) {
        return jdbc.sql("""
                        INSERT INTO submeter_main (submeter_id, main_id, property_id, utility_type, effective_from)
                        VALUES (:submeterId, :mainId, :propertyId, :type::meter_type, :from)
                        RETURNING *
                        """)
                .param("submeterId", submeterId).param("mainId", mainId).param("propertyId", propertyId)
                .param("type", type.name()).param("from", from)
                .query(SubmeterMainLink.class).single();
    }

    public SubmeterLotLink insertLotLink(UUID submeterId, UUID lotId, UUID propertyId, MeterType type, LocalDate from) {
        return jdbc.sql("""
                        INSERT INTO submeter_lot (submeter_id, lot_id, property_id, utility_type, effective_from)
                        VALUES (:submeterId, :lotId, :propertyId, CAST(:type AS meter_type), :from)
                        RETURNING *
                        """)
                .param("submeterId", submeterId).param("lotId", lotId).param("propertyId", propertyId)
                .param("type", type.name()).param("from", from)
                .query(SubmeterLotLink.class).single();
    }

    public Optional<SubmeterMainLink> findMainLink(UUID uuid) {
        return jdbc.sql("SELECT * FROM submeter_main WHERE uuid = :uuid AND deleted_at IS NULL FOR UPDATE")
                .param("uuid", uuid).query(SubmeterMainLink.class).optional();
    }

    public Optional<SubmeterLotLink> findLotLink(UUID uuid) {
        return jdbc.sql("SELECT * FROM submeter_lot WHERE uuid = :uuid AND deleted_at IS NULL FOR UPDATE")
                .param("uuid", uuid).query(SubmeterLotLink.class).optional();
    }

    public Optional<SubmeterMainLink> findAnyMainLink(UUID uuid) {
        return jdbc.sql("SELECT * FROM submeter_main WHERE uuid = :uuid")
                .param("uuid", uuid).query(SubmeterMainLink.class).optional();
    }

    public Optional<SubmeterLotLink> findAnyLotLink(UUID uuid) {
        return jdbc.sql("SELECT * FROM submeter_lot WHERE uuid = :uuid")
                .param("uuid", uuid).query(SubmeterLotLink.class).optional();
    }

    // Every live link of a submeter to a main, earliest first, locked while the caller decides
    public List<SubmeterMainLink> mainLinksOf(UUID submeterId) {
        return jdbc.sql("SELECT * FROM submeter_main WHERE submeter_id = :id AND deleted_at IS NULL ORDER BY effective_from FOR UPDATE")
                .param("id", submeterId).query(SubmeterMainLink.class).list();
    }

    // Every live link of one lot to a submeter of one utility, earliest first, locked
    public List<SubmeterLotLink> lotLinksOfLot(UUID lotId, MeterType type) {
        return jdbc.sql("""
                        SELECT * FROM submeter_lot WHERE lot_id = :lotId AND utility_type = CAST(:type AS meter_type)
                           AND deleted_at IS NULL ORDER BY effective_from FOR UPDATE
                        """)
                .param("lotId", lotId).param("type", type.name()).query(SubmeterLotLink.class).list();
    }

    public List<SubmeterMainLink> mainLinksOfProperty(UUID propertyId, boolean deleted) {
        return jdbc.sql("SELECT * FROM submeter_main WHERE property_id = :propertyId AND (deleted_at IS NOT NULL) = :deleted ORDER BY effective_from")
                .param("propertyId", propertyId).param("deleted", deleted).query(SubmeterMainLink.class).list();
    }

    public List<SubmeterLotLink> lotLinksOfProperty(UUID propertyId, boolean deleted) {
        return jdbc.sql("SELECT * FROM submeter_lot WHERE property_id = :propertyId AND (deleted_at IS NOT NULL) = :deleted ORDER BY effective_from")
                .param("propertyId", propertyId).param("deleted", deleted).query(SubmeterLotLink.class).list();
    }

    public Optional<SubmeterMainLink> setMainLinkDates(UUID uuid, LocalDate from, LocalDate to) {
        return jdbc.sql("UPDATE submeter_main SET effective_from = :from, effective_to = :to WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *")
                .param("uuid", uuid).param("from", from).param("to", to)
                .query(SubmeterMainLink.class).optional();
    }

    public Optional<SubmeterLotLink> setLotLinkDates(UUID uuid, LocalDate from, LocalDate to) {
        return jdbc.sql("UPDATE submeter_lot SET effective_from = :from, effective_to = :to WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *")
                .param("uuid", uuid).param("from", from).param("to", to)
                .query(SubmeterLotLink.class).optional();
    }

    public boolean softDelete(String table, UUID uuid) {
        requireTable(table);
        return jdbc.sql("UPDATE " + table + " SET deleted_at = now() WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid).update() > 0;
    }

    public boolean restore(String table, UUID uuid) {
        requireTable(table);
        return jdbc.sql("UPDATE " + table + " SET deleted_at = NULL WHERE uuid = :uuid AND deleted_at IS NOT NULL")
                .param("uuid", uuid).update() > 0;
    }

    private static void requireTable(String table) {
        if (!Set.of(MAIN_LINK, LOT_LINK).contains(table)) {
            throw new IllegalArgumentException("Not a link table: " + table);
        }
    }
}
