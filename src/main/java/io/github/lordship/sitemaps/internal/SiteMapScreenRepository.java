package io.github.lordship.sitemaps.internal;

import io.github.lordship.sitemaps.internal.SiteMapScreenRows.FeatureShapeRow;
import io.github.lordship.sitemaps.internal.SiteMapScreenRows.FootprintRow;
import io.github.lordship.sitemaps.internal.SiteMapScreenRows.LotShapeRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * The reads Glade's map screens need. The lease map has its own reads in SiteMapRepository.
 * Shapes are wrapped in ST_MakeValid first: one self-crossing lot must not fail the whole read.
 */
@Repository
public class SiteMapScreenRepository {

    private final JdbcClient db;

    public SiteMapScreenRepository(JdbcClient db) {
        this.db = db;
    }

    // One row per live property that has lot outlines. Six decimals is about ten centimetres.
    public List<FootprintRow> findFootprints() {
        return db.sql("""
                        SELECT f.property_id,
                               postgis.ST_AsGeoJSON(f.outline, 6) AS outline,
                               f.lot_count
                          FROM property_footprint f
                          JOIN property p ON p.uuid = f.property_id
                         WHERE p.deleted_at IS NULL
                        """)
                .query(FootprintRow.class)
                .list();
    }

    public boolean hasSiteMap(UUID propertyId) {
        return db.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM site_map WHERE property_id = :property AND deleted_at IS NULL
                        )
                        """)
                .param("property", propertyId)
                .query(Boolean.class)
                .single();
    }

    // The lot number goes at the middle of the biggest circle that fits inside the lot,
    // measured in metres. It stays inside L-shaped lots, unlike a plain centroid.
    public List<LotShapeRow> findLotShapes(UUID propertyId) {
        return db.sql("""
                        SELECT o.lot_id,
                               l.lot_number,
                               postgis.ST_AsGeoJSON(o.outline, 7) AS outline,
                               postgis.ST_X(c.center) AS label_longitude,
                               postgis.ST_Y(c.center) AS label_latitude
                          FROM lot_outline o
                          JOIN lot l ON l.uuid = o.lot_id
                          CROSS JOIN LATERAL (
                              SELECT postgis.ST_Transform(
                                         (postgis.ST_MaximumInscribedCircle(
                                             postgis.ST_MakeValid(postgis.ST_Transform(o.outline, 3857)))).center,
                                         4326) AS center
                          ) c
                         WHERE o.property_id = :property
                           AND l.deleted_at IS NULL
                         ORDER BY l.sort_order
                        """)
                .param("property", propertyId)
                .query(LotShapeRow.class)
                .list();
    }

    // Everything drawn around the lots: roads, water, buildings, contours, neighbours.
    // context_area is left out. It only marks the frame of the last pull.
    public List<FeatureShapeRow> findFeatureShapes(UUID propertyId) {
        return db.sql("""
                        SELECT f.kind,
                               f.name,
                               postgis.ST_AsGeoJSON(f.geom, 7) AS geometry,
                               COALESCE((f.details ->> 'width_m')::float8, 0) AS width_metres
                          FROM map_feature f
                         WHERE f.property_id = :property
                           AND f.deleted_at IS NULL
                           AND f.kind <> 'context_area'
                        """)
                .param("property", propertyId)
                .query(FeatureShapeRow.class)
                .list();
    }
}
