package io.github.lordship.sitemaps.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The reads and writes of Features mode: buildings, lights and roads changed by hand.
 *
 * <p>Two kinds of row, two rules:
 * <ul>
 *   <li>source 'manual': made by hand. Changed and deleted directly. A pull never touches these.</li>
 *   <li>anything else: pulled. The next pull rewrites these rows, so a change to one is kept in
 *       map_feature_edit by its source_ref, and applied again after every pull.</li>
 * </ul>
 */
@Repository
public class FeatureEditRepository {

    /** The kinds Features mode can change. */
    static final List<String> EDITABLE = List.of("building", "light", "road");

    private final JdbcClient db;

    public FeatureEditRepository(JdbcClient db) {
        this.db = db;
    }

    /** sql: map_feature, one building, light or road. details is JSON text, geometry GeoJSON. */
    public record FeatureRow(UUID uuid, UUID propertyId, String kind, String name, String source, String sourceRef,
                             String details, String geometry) { }

    // A park can have features once its map has been saved
    public boolean isPlaced(UUID propertyId) {
        return db.sql("SELECT EXISTS (SELECT 1 FROM site_map WHERE property_id = :property AND deleted_at IS NULL)")
                .param("property", propertyId)
                .query(Boolean.class)
                .single();
    }

    public List<FeatureRow> findEditable(UUID propertyId) {
        return db.sql("""
                        SELECT uuid, property_id, kind, name, source, source_ref, details::text AS details,
                               postgis.ST_AsGeoJSON(geom, 8) AS geometry
                          FROM map_feature
                         WHERE property_id = :property
                           AND kind IN (:kinds)
                           AND deleted_at IS NULL
                         ORDER BY kind, name NULLS LAST, uuid
                        """)
                .param("property", propertyId)
                .param("kinds", EDITABLE)
                .query(FeatureRow.class)
                .list();
    }

    // Empty when there is no such feature in this park
    public Optional<FeatureRow> find(UUID propertyId, UUID featureId) {
        return db.sql("""
                        SELECT uuid, property_id, kind, name, source, source_ref, details::text AS details,
                               postgis.ST_AsGeoJSON(geom, 9) AS geometry
                          FROM map_feature
                         WHERE uuid = :feature AND property_id = :property AND deleted_at IS NULL
                        """)
                .param("feature", featureId)
                .param("property", propertyId)
                .query(FeatureRow.class)
                .optional();
    }

    // Pulled features the office hid, not counting originals replaced by a hand-corrected copy.
    // Those come back by deleting the copy.
    public int countHidden(UUID propertyId) {
        return db.sql("""
                        SELECT count(*)
                          FROM map_feature_edit e
                         WHERE e.property_id = :property
                           AND e.hidden
                           AND NOT EXISTS (SELECT 1 FROM map_feature m
                                            WHERE m.property_id = e.property_id
                                              AND m.source = 'manual'
                                              AND m.source_ref = 'replaces:' || e.source_ref
                                              AND m.deleted_at IS NULL)
                        """)
                .param("property", propertyId)
                .query(Integer.class)
                .single();
    }

    // A building outline that crosses itself has no inside. PostGIS cannot measure or overlap it.
    public boolean isValidShape(String geoJson) {
        return db.sql("SELECT postgis.ST_IsValid(postgis.ST_SetSRID(postgis.ST_GeomFromGeoJSON(:shape), 4326))")
                .param("shape", geoJson)
                .query(Boolean.class)
                .single();
    }

    public UUID insertManual(UUID propertyId, String kind, String name, String geoJson, String sourceRef,
                             String detailsJson) {
        return db.sql("""
                        INSERT INTO map_feature (property_id, kind, name, geom, source, source_ref, details, fetched_at)
                        VALUES (:property, :kind, :name, postgis.ST_SetSRID(postgis.ST_GeomFromGeoJSON(:shape), 4326),
                                'manual', :ref, :details::jsonb, now())
                        RETURNING uuid
                        """)
                .param("property", propertyId)
                .param("kind", kind)
                .param("name", name)
                .param("shape", geoJson)
                .param("ref", sourceRef)
                .param("details", detailsJson)
                .query(UUID.class)
                .single();
    }

    public void updateManual(UUID featureId, String name, String geoJson, String detailsJson) {
        db.sql("""
                        UPDATE map_feature
                           SET name = :name,
                               geom = postgis.ST_SetSRID(postgis.ST_GeomFromGeoJSON(:shape), 4326),
                               details = :details::jsonb
                         WHERE uuid = :feature
                        """)
                .param("feature", featureId)
                .param("name", name)
                .param("shape", geoJson)
                .param("details", detailsJson)
                .update();
    }

    public void rename(UUID featureId, String name) {
        db.sql("UPDATE map_feature SET name = :name WHERE uuid = :feature")
                .param("feature", featureId)
                .param("name", name)
                .update();
    }

    // The other pieces of a road with this name
    public List<FeatureRow> findSameName(UUID propertyId, String kind, String name, UUID except) {
        return db.sql("""
                        SELECT uuid, property_id, kind, name, source, source_ref, details::text AS details,
                               NULL AS geometry
                          FROM map_feature
                         WHERE property_id = :property AND kind = :kind AND name = :name
                           AND uuid <> :except AND deleted_at IS NULL
                        """)
                .param("property", propertyId)
                .param("kind", kind)
                .param("name", name)
                .param("except", except)
                .query(FeatureRow.class)
                .list();
    }

    public void delete(UUID featureId) {
        db.sql("DELETE FROM map_feature WHERE uuid = :feature")
                .param("feature", featureId)
                .update();
    }

    /**
     * Adds or changes the park's edit of one pulled feature.
     * name: null leaves the name as it was, '' means "no name". hidden: null leaves it as it was.
     */
    public void recordEdit(UUID propertyId, String sourceRef, String name, Boolean hidden) {
        db.sql("""
                        INSERT INTO map_feature_edit (property_id, source_ref, name, hidden, edited_at)
                        VALUES (:property, :ref, :name, :hidden, now())
                        ON CONFLICT (property_id, source_ref) DO UPDATE SET
                            name      = COALESCE(EXCLUDED.name, map_feature_edit.name),
                            hidden    = COALESCE(EXCLUDED.hidden, map_feature_edit.hidden),
                            edited_at = now()
                        """)
                .param("property", propertyId)
                .param("ref", sourceRef)
                .param("name", name)
                .param("hidden", hidden)
                .update();
    }

    // The original of a deleted hand-corrected copy comes back at the next pull, with the copy's name
    public void bringBack(UUID propertyId, String sourceRef, String name) {
        db.sql("""
                        UPDATE map_feature_edit
                           SET hidden = NULL, name = :name, edited_at = now()
                         WHERE property_id = :property AND source_ref = :ref
                        """)
                .param("property", propertyId)
                .param("ref", sourceRef)
                .param("name", name)
                .update();
    }

    // Everything hidden comes back at the next pull, except originals a hand-corrected copy still replaces
    public int unhideAll(UUID propertyId) {
        return db.sql("""
                        UPDATE map_feature_edit e
                           SET hidden = NULL, edited_at = now()
                         WHERE e.property_id = :property
                           AND e.hidden
                           AND NOT EXISTS (SELECT 1 FROM map_feature m
                                            WHERE m.property_id = e.property_id
                                              AND m.source = 'manual'
                                              AND m.source_ref = 'replaces:' || e.source_ref
                                              AND m.deleted_at IS NULL)
                        """)
                .param("property", propertyId)
                .update();
    }
}
