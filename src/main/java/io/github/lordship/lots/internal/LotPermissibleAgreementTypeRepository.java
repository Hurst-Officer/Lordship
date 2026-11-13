package io.github.lordship.lots.internal;

import io.github.lordship.shared.AgreementType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public class LotPermissibleAgreementTypeRepository {

    private final JdbcClient jdbc;
    private final LotPermissibleAgreementTypeRowMapper rowMapper = new LotPermissibleAgreementTypeRowMapper();

    public LotPermissibleAgreementTypeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public LotPermissibleAgreementTypeRow save(LotPermissibleAgreementTypeRow row) {
        return jdbc.sql("""
            INSERT INTO lot_permissible_agreement_type (lot_id, agreement_type, target_rate, asking_rate)
            VALUES (:lotId, :agreementType::agreement_type, :targetRate, :askingRate)
            ON CONFLICT (lot_id, agreement_type)
            DO UPDATE SET target_rate = EXCLUDED.target_rate,
                          asking_rate = EXCLUDED.asking_rate
            RETURNING *
            """)
                .param("lotId", row.lotId())
                .param("agreementType", row.agreementType().name())
                .param("targetRate", row.targetRate())
                .param("askingRate", row.askingRate())
                .query(rowMapper)
                .single();
    }

    /**
     * The owner's pricing pass: one or both figures across a selection, for one
     * kind of deal.
     *
     * <p>An UPDATE and not an upsert -- a lot in the selection that does not
     * permit the type means the selection went stale, not that the lot should
     * be enrolled. COALESCE leaves a figure alone when it was not supplied, so
     * setting only the asking rate cannot wipe the target; clearing a rate is
     * clearRates' job.
     */
    public int updateRates(Collection<UUID> lotIds, AgreementType agreementType,
                           BigDecimal targetRate, BigDecimal askingRate) {
        if (lotIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
            UPDATE lot_permissible_agreement_type
            SET target_rate = COALESCE(:targetRate, target_rate),
                asking_rate = COALESCE(:askingRate, asking_rate)
            WHERE lot_id IN (:lotIds)
              AND agreement_type = :agreementType::agreement_type
            """)
                .param("lotIds", lotIds)
                .param("agreementType", agreementType.name())
                .param("targetRate", targetRate)
                .param("askingRate", askingRate)
                .update();
    }

    /**
     * Empties one or both rates across a selection, for one kind of deal. A lot with an empty
     * rate has no rate of the lot's own, and the property's template rate applies.
     * A rate whose flag is false is left alone. Like updateRates, only lots that permit the type are touched.
     */
    public int clearRates(Collection<UUID> lotIds, AgreementType agreementType,
                          boolean clearTargetRate, boolean clearAskingRate) {
        if (lotIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
            UPDATE lot_permissible_agreement_type
            SET target_rate = CASE WHEN :clearTargetRate THEN NULL ELSE target_rate END,
                asking_rate = CASE WHEN :clearAskingRate THEN NULL ELSE asking_rate END
            WHERE lot_id IN (:lotIds)
              AND agreement_type = :agreementType::agreement_type
            """)
                .param("lotIds", lotIds)
                .param("agreementType", agreementType.name())
                .param("clearTargetRate", clearTargetRate)
                .param("clearAskingRate", clearAskingRate)
                .update();
    }

    public List<LotPermissibleAgreementTypeRow> findByLotId(UUID lotId) {
        return jdbc.sql("""
            SELECT * FROM lot_permissible_agreement_type
            WHERE lot_id = :lotId
            ORDER BY agreement_type
            """)
                .param("lotId", lotId)
                .query(rowMapper)
                .list();
    }

    public List<LotPermissibleAgreementTypeRow> findByLotIds(Collection<UUID> lotIds) {
        if (lotIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
            SELECT * FROM lot_permissible_agreement_type
            WHERE lot_id IN (:lotIds)
            ORDER BY lot_id, agreement_type
            """)
                .param("lotIds", lotIds)
                .query(rowMapper)
                .list();
    }

    public boolean delete(UUID lotId, AgreementType agreementType) {
        return jdbc.sql("""
            DELETE FROM lot_permissible_agreement_type
            WHERE lot_id = :lotId AND agreement_type = :agreementType::agreement_type
            """)
                .param("lotId", lotId)
                .param("agreementType", agreementType.name())
                .update() > 0;
    }
}