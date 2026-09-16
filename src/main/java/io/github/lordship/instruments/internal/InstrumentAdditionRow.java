package io.github.lordship.instruments.internal;

import io.github.lordship.instruments.InstrumentAddition;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of {@code instrument_addition}. All simple types, so JdbcClient maps
 * this one without a RowMapper.
 */
public record InstrumentAdditionRow(
        UUID uuid,
        UUID instrument,
        UUID section,
        BigDecimal ordinal,
        String title,
        String body,
        String note,
        OffsetDateTime createdAt,
        UUID createdBy,
        OffsetDateTime deletedAt
) {

    public InstrumentAddition toInstrumentAddition() {
        return new InstrumentAddition(
                this.uuid,
                this.instrument,
                this.section,
                this.ordinal,
                this.title,
                this.body,
                this.note,
                this.createdAt,
                this.deletedAt
        );
    }
}
