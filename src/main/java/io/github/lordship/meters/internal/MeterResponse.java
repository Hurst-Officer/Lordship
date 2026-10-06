package io.github.lordship.meters.internal;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.github.lordship.meters.MeterMeasurement;
import io.github.lordship.meters.MeterType;
import io.github.lordship.meters.Meters;

import java.time.LocalDate;
import java.util.UUID;

@JsonFormat
public record MeterResponse(
        UUID id,
        UUID meterId,
        String title,
        String description,
        String serialNumber,
        Double pointX,
        Double pointY,
        MeterType utilityType,
        MeterMeasurement measurement,
        Boolean isMasterMeter,
        LocalDate installedAt,
        Integer rolloverMax
) {

    public static io.github.lordship.meters.internal.MeterResponse from(Meters m) {
        return new io.github.lordship.meters.internal.MeterResponse(
                m.uuid(),
                m.meterId(),
                m.title(),
                m.description(),
                m.serialNumber(),
                m.pointX(),
                m.pointY(),
                m.utilityType(),
                m.measurement(),
                m.isMasterMeter(),
                m.installedAt(),
                m.rolloverMax()
        );
    }
}
