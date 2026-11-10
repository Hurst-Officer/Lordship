package io.github.lordship.meters;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * How to read one meter's dial, from a day on. Exactly one of mainId, submeterId.
 *
 * <p>Every meter has one setting with no start day: it holds from the first
 * read. A correction or a change adds a dated setting, so reads before that day
 * keep the usage they always had. A read uses the setting in force on its day.
 *
 * @param effectiveFrom null for the meter's first setting
 * @param multiplier    dial difference x multiplier = usage in unit (a dial that counts tens of gallons: 10)
 * @param dialMax       the largest number the dial shows before it starts over at 0
 */
public record MeterSetting(
        UUID uuid,
        UUID mainId,
        UUID submeterId,
        UUID propertyId,
        MeterType utilityType,
        LocalDate effectiveFrom,
        MeterUnit unit,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal multiplier,
        Long dialMax,
        String notes,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {

    /** The setting in force on a day, from one meter's live settings (one of them has no start day) */
    public static MeterSetting inForce(List<MeterSetting> settings, LocalDate day) {
        MeterSetting found = null;
        for (MeterSetting s : settings) {
            boolean started = s.effectiveFrom() == null || !s.effectiveFrom().isAfter(day);
            if (!started) continue;
            if (found == null || later(s, found)) found = s;
        }
        if (found == null) throw new IllegalStateException("A meter has no setting in force on " + day);
        return found;
    }

    private static boolean later(MeterSetting a, MeterSetting b) {
        if (a.effectiveFrom() == null) return false;
        return b.effectiveFrom() == null || a.effectiveFrom().isAfter(b.effectiveFrom());
    }
}
