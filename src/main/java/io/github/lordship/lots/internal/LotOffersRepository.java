package io.github.lordship.lots.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only joins for Glade's Rates and deals screens (LotOffers). Writes go through LotService.
 *
 * <p>Every query leaves out deleted lots and deleted properties. These read the tenancy and terms_template
 * tables by name on purpose: whether a lot is taken, and the rate a lot falls back to, are facts about the lot.
 */
@Repository
public class LotOffersRepository {

    // sql: a lot is taken while a tenancy on the lot has not ended. A tenancy with an end date of today or later
    // still holds the lot; one with no end date holds the lot until someone ends the tenancy.
    private static final String TAKEN = """
            EXISTS (
                SELECT 1 FROM tenancy t
                WHERE t.lot_id = l.uuid
                  AND t.deleted_at IS NULL
                  AND (t.end_date IS NULL OR t.end_date >= CURRENT_DATE)
            )""";

    private final JdbcClient jdbc;

    public LotOffersRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // One property's standing for one kind of agreement
    public record ParkRow(UUID propertyId, String agreementType, boolean hasDeal, int offered, int open, int taken,
                          BigDecimal askingAverage, BigDecimal targetAverage,
                          BigDecimal openAskingLow, BigDecimal openAskingHigh) { }

    // One live lot. titleLink: the link the office set for the lot's name, or null.
    // taken: a tenancy on the lot has not ended.
    public record LotLineRow(UUID id, UUID propertyId, String lotNumber, String lotAddress, String lotParcel,
                             String titleLink, boolean isRentable, String notRentableReason, boolean taken) { }

    // One kind of agreement a lot offers, with the lot's own rates (null when the lot has none of the lot's own)
    public record OfferRow(UUID id, UUID lotId, String agreementType, BigDecimal targetRate, BigDecimal askingRate) { }

    /**
     * One row for each kind of agreement a live property has a deal for, or one of the property's lots offers.
     * A property with a deal and no lot offering the kind yet still gets a row, with every count at zero.
     */
    public List<ParkRow> findParkLines() {
        return jdbc.sql("""
                WITH offers AS (
                    SELECT l.property_id,
                           a.agreement_type,
                           COALESCE(a.asking_rate, d.asking_rate) AS asking_rate,
                           COALESCE(a.target_rate, d.target_rate) AS target_rate,
                           l.is_rentable,
                           %s AS taken
                    FROM lot_permissible_agreement_type a
                    JOIN lot l ON l.uuid = a.lot_id AND l.deleted_at IS NULL
                    LEFT JOIN terms_template d ON d.property = l.property_id
                                              AND d.agreement_type = a.agreement_type
                                              AND d.deleted_at IS NULL
                ),
                offered AS (
                    SELECT property_id,
                           agreement_type,
                           count(*) AS offered,
                           count(*) FILTER (WHERE NOT taken AND is_rentable) AS open,
                           count(*) FILTER (WHERE taken) AS taken,
                           avg(asking_rate) AS asking_average,
                           avg(target_rate) AS target_average,
                           min(asking_rate) FILTER (WHERE NOT taken AND is_rentable) AS open_asking_low,
                           max(asking_rate) FILTER (WHERE NOT taken AND is_rentable) AS open_asking_high
                    FROM offers
                    GROUP BY property_id, agreement_type
                ),
                deals AS (
                    SELECT DISTINCT property AS property_id, agreement_type
                    FROM terms_template
                    WHERE property IS NOT NULL AND deleted_at IS NULL
                )
                SELECT p.uuid AS property_id,
                       COALESCE(o.agreement_type, d.agreement_type)::text AS agreement_type,
                       (d.property_id IS NOT NULL) AS has_deal,
                       COALESCE(o.offered, 0)::int AS offered,
                       COALESCE(o.open, 0)::int AS open,
                       COALESCE(o.taken, 0)::int AS taken,
                       round(o.asking_average, 2) AS asking_average,
                       round(o.target_average, 2) AS target_average,
                       o.open_asking_low,
                       o.open_asking_high
                FROM offered o
                FULL JOIN deals d ON d.property_id = o.property_id AND d.agreement_type = o.agreement_type
                JOIN property p ON p.uuid = COALESCE(o.property_id, d.property_id) AND p.deleted_at IS NULL
                ORDER BY p.uuid, COALESCE(o.agreement_type, d.agreement_type)
                """.formatted(TAKEN))
                .query(ParkRow.class)
                .list();
    }

    // A property's live lots, in display order
    public List<LotLineRow> findLotsByProperty(UUID propertyId) {
        return jdbc.sql("""
                SELECT l.uuid AS id, l.property_id, l.lot_number, l.lot_address, l.lot_parcel,
                       l.title_link, l.is_rentable, l.not_rentable_reason,
                       %s AS taken
                FROM lot l
                WHERE l.property_id = :propertyId
                  AND l.deleted_at IS NULL
                ORDER BY l.sort_order NULLS LAST, l.lot_number
                """.formatted(TAKEN))
                .param("propertyId", propertyId)
                .query(LotLineRow.class)
                .list();
    }

    public Optional<LotLineRow> findLot(UUID lotId) {
        return jdbc.sql("""
                SELECT l.uuid AS id, l.property_id, l.lot_number, l.lot_address, l.lot_parcel,
                       l.title_link, l.is_rentable, l.not_rentable_reason,
                       %s AS taken
                FROM lot l
                WHERE l.uuid = :lotId
                  AND l.deleted_at IS NULL
                """.formatted(TAKEN))
                .param("lotId", lotId)
                .query(LotLineRow.class)
                .optional();
    }

    // What every live lot of a property offers, in enum declaration order
    public List<OfferRow> findOffersByProperty(UUID propertyId) {
        return jdbc.sql("""
                SELECT a.uuid AS id, a.lot_id, a.agreement_type::text AS agreement_type, a.target_rate, a.asking_rate
                FROM lot_permissible_agreement_type a
                JOIN lot l ON l.uuid = a.lot_id
                WHERE l.property_id = :propertyId
                  AND l.deleted_at IS NULL
                ORDER BY a.lot_id, a.agreement_type
                """)
                .param("propertyId", propertyId)
                .query(OfferRow.class)
                .list();
    }

    public List<OfferRow> findOffersByLot(UUID lotId) {
        return jdbc.sql("""
                SELECT a.uuid AS id, a.lot_id, a.agreement_type::text AS agreement_type, a.target_rate, a.asking_rate
                FROM lot_permissible_agreement_type a
                WHERE a.lot_id = :lotId
                ORDER BY a.agreement_type
                """)
                .param("lotId", lotId)
                .query(OfferRow.class)
                .list();
    }

    // The property of the lot an offer row belongs to. The lot may be deleted: the row's history is still the property's.
    public Optional<UUID> findPropertyOfOffer(UUID offerId) {
        return jdbc.sql("""
                SELECT l.property_id
                FROM lot_permissible_agreement_type a
                JOIN lot l ON l.uuid = a.lot_id
                WHERE a.uuid = :offerId
                """)
                .param("offerId", offerId)
                .query(UUID.class)
                .optional();
    }
}
