package io.github.lordship.instruments.internal;

import io.github.lordship.instruments.Instrument;
import io.github.lordship.instruments.InstrumentStatus;
import io.github.lordship.instruments.OnExpiry;
import io.github.lordship.instruments.ServiceMethod;
import io.github.lordship.shared.InstrumentType;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One document, as a screen needs it.
 *
 * <p>{@code lastCoveredDay} is computed rather than stored: a lease that runs
 * 60 months from November 1 2026 ends October 31 2031, and the table holds the
 * start and the count. Sending it saves every caller from doing that arithmetic,
 * and doing it wrong gives the tenant a free day.
 */
public record InstrumentResponse(
        UUID uuid,
        UUID tenancyId,
        InstrumentType type,
        InstrumentStatus status,
        String serial,
        UUID amends,

        LocalDate termStart,
        Integer termMonths,
        LocalDate lastCoveredDay,
        OnExpiry onExpiry,

        UUID templateId,
        Integer templateVersion,
        UUID documentAssignmentId,

        OffsetDateTime generatedAt,
        UUID generatedFileId,
        OffsetDateTime sentAt,
        LocalDate servedOn,
        ServiceMethod serviceMethod,
        LocalDate returnedOn,

        String note,
        OffsetDateTime createdAt
) {

    public static InstrumentResponse from(Instrument instrument) {
        return new InstrumentResponse(
                instrument.uuid(),
                instrument.tenancy(),
                instrument.type(),
                instrument.status(),
                instrument.serial(),
                instrument.amends(),
                instrument.termStart(),
                instrument.termMonths(),
                instrument.lastCoveredDay().orElse(null),
                instrument.onExpiry(),
                instrument.template(),
                instrument.templateVersion(),
                instrument.documentAssignment(),
                instrument.generatedAt(),
                instrument.generatedFile(),
                instrument.sentAt(),
                instrument.servedOn(),
                instrument.serviceMethod(),
                instrument.returnedOn(),
                instrument.note(),
                instrument.createdAt());
    }
}
