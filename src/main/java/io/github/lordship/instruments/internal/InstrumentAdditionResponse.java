package io.github.lordship.instruments.internal;

import io.github.lordship.instruments.InstrumentAddition;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A clause typed onto one agreement. */
public record InstrumentAdditionResponse(
        UUID uuid,
        UUID instrumentId,
        UUID sectionId,
        BigDecimal ordinal,
        String title,
        String body,
        String note,
        OffsetDateTime createdAt
) {

    public static InstrumentAdditionResponse from(InstrumentAddition addition) {
        return new InstrumentAdditionResponse(
                addition.uuid(),
                addition.instrument(),
                addition.section(),
                addition.ordinal(),
                addition.title(),
                addition.body(),
                addition.note(),
                addition.createdAt());
    }
}
