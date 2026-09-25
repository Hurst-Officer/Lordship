package io.github.lordship.instruments.internal;

import io.github.lordship.instruments.Instrument;
import io.github.lordship.instruments.InstrumentStatus;
import io.github.lordship.instruments.OnExpiry;
import io.github.lordship.instruments.ServiceMethod;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of {@code instrument}. Component order matches the column order in
 * V9__documents_and_deals.sql.
 *
 * <p>No {@code deletedAt}: an instrument is never soft-deleted. Paper that
 * never went out is ABANDONED, which is a fact about the document rather than
 * a way of hiding it.
 */
public record InstrumentRow(
        UUID uuid,
        UUID tenancy,
        InstrumentType type,
        AgreementType agreementType,
        InstrumentStatus status,

        String serial,
        UUID amends,

        LocalDate termStart,
        Integer termMonths,
        OnExpiry onExpiry,

        UUID template,
        Integer templateVersion,
        UUID documentAssignment,

        OffsetDateTime generatedAt,
        UUID generatedFile,

        OffsetDateTime sentAt,
        UUID sentBy,

        LocalDate servedOn,
        ServiceMethod serviceMethod,
        UUID servedBy,
        UUID proofFile,

        LocalDate returnedOn,
        UUID returnedFile,

        String note,
        OffsetDateTime createdAt,
        UUID createdBy
) {

    public Instrument toInstrument() {
        return new Instrument(
                uuid,
                tenancy,
                type,
                agreementType,
                status,
                serial,
                amends,
                termStart,
                termMonths,
                onExpiry,
                template,
                templateVersion,
                documentAssignment,
                generatedAt,
                generatedFile,
                sentAt,
                sentBy,
                servedOn,
                serviceMethod,
                servedBy,
                proofFile,
                returnedOn,
                returnedFile,
                note,
                createdAt,
                createdBy
        );
    }
}
