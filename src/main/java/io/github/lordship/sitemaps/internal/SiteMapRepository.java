package io.github.lordship.sitemaps.internal;

import io.github.lordship.sitemaps.ParkMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The three reads a park map needs.
 *
 * <p>Geometry leaves here as GeoJSON text. ST_AsGeoJSON does the work, so the
 * application never needs a PostGIS type or a geometry library on the classpath.
 * Seven decimal places is about a centimetre, which is finer than the plat lines
 * the outlines were traced from.
 */
@Repository
public class SiteMapRepository {

    private final JdbcClient db;

    public SiteMapRepository(JdbcClient db) {
        this.db = db;
    }

    public Optional<Placed> findSiteMap(UUID propertyId) {
        return db.sql("""
                        SELECT postgis.ST_AsGeoJSON(boundary, 7) AS boundary,
                               credits,
                               placed_at
                          FROM site_map
                         WHERE property_id = :property
                           AND deleted_at IS NULL
                        """)
                .param("property", propertyId)
                .query((rs, row) -> new Placed(
                        rs.getString("boundary"),
                        credits(rs.getArray("credits")),
                        rs.getObject("placed_at", java.time.OffsetDateTime.class).toLocalDate()))
                .optional();
    }

    public List<ParkMap.Outline> findOutlines(UUID propertyId) {
        return db.sql("""
                        SELECT o.lot_id,
                               l.lot_number,
                               postgis.ST_AsGeoJSON(o.outline, 7) AS outline
                          FROM lot_outline o
                          JOIN lot l ON l.uuid = o.lot_id
                         WHERE o.property_id = :property
                           AND l.deleted_at IS NULL
                         ORDER BY l.sort_order
                        """)
                .param("property", propertyId)
                .query((rs, row) -> new ParkMap.Outline(
                        rs.getObject("lot_id", UUID.class),
                        rs.getString("lot_number"),
                        rs.getString("outline")))
                .list();
    }

    /**
     * What the lease may draw: every kind flagged for printing, plus the
     * tenant's own home.
     *
     * <p>The home is fetched by rule rather than by the kind's flag, because
     * 'building' is off for everyone else's homes and on for this one.
     *
     * <p>Only the building marked role = 'home' counts. Sheds and a hand-drawn
     * Office can sit on the same lot, and the page draws the first one it gets.
     */
    public List<ParkMap.Feature> findPrintable(UUID propertyId, UUID tenantLotId) {
        return db.sql("""
                        SELECT f.kind,
                               f.name,
                               postgis.ST_AsGeoJSON(f.geom, 7) AS geom,
                               COALESCE((f.details ->> 'width_m')::numeric, 0) AS width_m,
                               f.lot_id
                          FROM map_feature f
                          JOIN map_feature_kind k ON k.kind = f.kind
                         WHERE f.property_id = :property
                           AND f.deleted_at IS NULL
                           AND (k.print_on_lease
                                OR (f.kind = 'building' AND f.lot_id = :lot AND f.details ->> 'role' = 'home'))
                        """)
                .param("property", propertyId)
                .param("lot", tenantLotId)
                .query((rs, row) -> new ParkMap.Feature(
                        rs.getString("kind"),
                        rs.getString("name"),
                        rs.getString("geom"),
                        rs.getDouble("width_m"),
                        rs.getObject("lot_id", UUID.class)))
                .list();
    }

    /** site_map's own columns, before the lots and features are added. */
    public record Placed(String boundary, List<String> credits, LocalDate placedOn) {}

    private static List<String> credits(Array array) throws java.sql.SQLException {
        if (array == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (Object line : (Object[]) array.getArray()) {
            lines.add(String.valueOf(line));
        }
        return lines;
    }
}
