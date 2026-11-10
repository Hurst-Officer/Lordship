package io.github.lordship.meters;

import java.util.List;
import java.util.UUID;

/** One meter's reads in a date range, for the read grid. Exactly one of mainId, submeterId is set. */
public record MeterReadings(
        UUID mainId,
        UUID submeterId,
        List<ReadInterval> reads
) {}
