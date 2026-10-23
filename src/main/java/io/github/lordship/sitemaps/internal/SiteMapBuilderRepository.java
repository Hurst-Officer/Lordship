package io.github.lordship.sitemaps.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The reads and writes of the map builder.
 *
 * <p>Outlines arrive and leave as GeoJSON text, so no geometry library is needed here.
 * Seven decimals is about a centimetre.
 */
@Repository
public class SiteMapBuilderRepository {

    private final JdbcClient db;

    public SiteMapBuilderRepository(JdbcClient db) {
        this.db = db;
    }

    /** sql: site_map, the columns the builder needs to reopen a park */
    public record PlacementRow(
            Integer platWidth,
            Integer platHeight,
            String platImageFileName,
            UUID platFileId,
            String imageToWorld,
            OffsetDateTime placedAt
    ) { }

    /** sql: lot, with its outline from lot_outline when it has one */
    public record BuilderLotRow(UUID lotId, String lotNumber, String shapeData, String outline) { }

    // Two saves of the same park wait for each other here, so one cannot undo half of the other.
    public void lockProperty(UUID propertyId) {
        db.sql("SELECT uuid FROM property WHERE uuid = :property FOR UPDATE")
                .param("property", propertyId)
                .query(UUID.class)
                .optional();
    }

    public Optional<PlacementRow> findPlacement(UUID propertyId) {
        return db.sql("""
                        SELECT plat_width,
                               plat_height,
                               plat_image_file_name,
                               plat_file_id,
                               image_to_world::text AS image_to_world,
                               placed_at
                          FROM site_map
                         WHERE property_id = :property
                           AND deleted_at IS NULL
                        """)
                .param("property", propertyId)
                .query(PlacementRow.class)
                .optional();
    }

    // Every live lot of the park, in the park's own order
    public List<BuilderLotRow> findLots(UUID propertyId) {
        return db.sql("""
                        SELECT l.uuid AS lot_id,
                               l.lot_number,
                               l.shape_data::text AS shape_data,
                               postgis.ST_AsGeoJSON(o.outline, 7) AS outline
                          FROM lot l
                          LEFT JOIN lot_outline o ON o.lot_id = l.uuid
                         WHERE l.property_id = :property
                           AND l.deleted_at IS NULL
                         ORDER BY l.sort_order, l.lot_number
                        """)
                .param("property", propertyId)
                .query(BuilderLotRow.class)
                .list();
    }

    // A ring that crosses itself has no inside. lot_outline refuses those, so they are found first.
    public boolean isValidOutline(String geoJson) {
        return db.sql("SELECT postgis.ST_IsValid(postgis.ST_SetSRID(postgis.ST_GeomFromGeoJSON(:shape), 4326))")
                .param("shape", geoJson)
                .query(Boolean.class)
                .single();
    }

    // Saves where the drawing sits. A park placed before keeps its boundary and credits.
    public void savePlacement(UUID propertyId, int platWidth, int platHeight, String platFileName,
                              UUID platFileId, String imageToWorld, String imagery, UUID placedBy) {
        db.sql("""
                        INSERT INTO site_map (property_id, plat_image_file_name, plat_file_id, plat_width, plat_height,
                                              image_to_world, control_points, imagery, placed_at, placed_by)
                        VALUES (:property, :fileName, :fileId, :width, :height,
                                :imageToWorld::jsonb, '[]'::jsonb, :imagery, now(), :placedBy)
                        ON CONFLICT (property_id) DO UPDATE SET
                            plat_image_file_name = EXCLUDED.plat_image_file_name,
                            plat_file_id         = EXCLUDED.plat_file_id,
                            plat_width           = EXCLUDED.plat_width,
                            plat_height          = EXCLUDED.plat_height,
                            image_to_world       = EXCLUDED.image_to_world,
                            control_points       = EXCLUDED.control_points,
                            imagery              = EXCLUDED.imagery,
                            placed_at            = EXCLUDED.placed_at,
                            placed_by            = EXCLUDED.placed_by,
                            deleted_at           = NULL
                        """)
                .param("property", propertyId)
                .param("fileName", platFileName)
                .param("fileId", platFileId)
                .param("width", platWidth)
                .param("height", platHeight)
                .param("imageToWorld", imageToWorld)
                .param("imagery", imagery)
                .param("placedBy", placedBy)
                .update();
    }

    public int countOutlines(UUID propertyId) {
        return db.sql("SELECT count(*) FROM lot_outline WHERE property_id = :property")
                .param("property", propertyId)
                .query(Integer.class)
                .single();
    }

    public void deleteOutlines(UUID propertyId) {
        db.sql("DELETE FROM lot_outline WHERE property_id = :property")
                .param("property", propertyId)
                .update();
    }

    public void insertOutline(UUID lotId, UUID propertyId, String geoJson) {
        db.sql("""
                        INSERT INTO lot_outline (lot_id, property_id, outline, source)
                        VALUES (:lot, :property, postgis.ST_SetSRID(postgis.ST_GeomFromGeoJSON(:shape), 4326), 'MAP_BUILDER')
                        """)
                .param("lot", lotId)
                .param("property", propertyId)
                .param("shape", geoJson)
                .update();
    }

    // Square feet of all the park's lot outlines together
    public long totalSquareFeet(UUID propertyId) {
        return db.sql("""
                        SELECT COALESCE(round(sum(postgis.ST_Area(outline::postgis.geography)) * 10.7639), 0)::bigint
                          FROM lot_outline
                         WHERE property_id = :property
                        """)
                .param("property", propertyId)
                .query(Long.class)
                .single();
    }
}
