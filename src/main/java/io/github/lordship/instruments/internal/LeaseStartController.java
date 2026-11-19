package io.github.lordship.instruments.internal;

import io.github.lordship.instruments.LeaseStart;
import io.github.lordship.instruments.OnExpiry;
import io.github.lordship.shared.AgreementType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Starting a lease in one call, for Glade's Tenancies screens (see LeaseStart).
 *
 * <p>Refusals are answered by config ApiExceptionHandler as { message, code, field } in the worker's language.
 */
@Validated
@RestController
public class LeaseStartController {

    /**
     * A new tenancy, the household and the lease draft. agreementType is the kind of agreement the lot must be
     * open for and the lease is written for. startDate is optional (yyyy-MM-dd); left out, the server picks the
     * billing period the office is working in. tenantIds needs at least one person.
     * termMonths is how long the lease runs. onExpiry is MONTH_TO_MONTH, AUTO_RENEW or TERMINATE, or left out.
     */
    public record OnboardRequest(
            @NotNull UUID lotId,
            @NotNull AgreementType agreementType,
            LocalDate startDate,
            List<UUID> tenantIds,
            List<UUID> occupantIds,
            Integer termMonths,
            OnExpiry onExpiry
    ) { }

    // What onboarding answers: the tenancy's id, lot and start date as saved, and the lease draft's id
    public record Onboarded(UUID id, UUID lotId, LocalDate startDate, UUID leaseId) { }

    // A lease draft for a tenancy that is already there. termStart is optional (see LeaseStart.start).
    public record StartLeaseRequest(
            @NotNull UUID tenancyId,
            @NotNull AgreementType agreementType,
            LocalDate termStart,
            Integer termMonths,
            OnExpiry onExpiry
    ) { }

    private final LeaseStart leaseStart;

    public LeaseStartController(LeaseStart leaseStart) {
        this.leaseStart = leaseStart;
    }

    /**
     * A new tenancy with the household and the lease draft (period and rent schedule written), all or nothing.
     * 201 with { id, lotId, startDate, leaseId }.
     * 400 and 409: everything POST /api/tenancy/open refuses, and instrument.term_months_needed,
     * property.no_template.
     * Needs tenancy:create, tenants:create, instrument:create and tenancy_term:create.
     */
    @PreAuthorize("hasAuthority('tenancy:create') and hasAuthority('tenants:create') "
            + "and hasAuthority('instrument:create') and hasAuthority('tenancy_term:create')")
    @PostMapping("/api/tenancy/onboard")
    public ResponseEntity<Onboarded> onboard(@RequestBody @Valid OnboardRequest request) {
        LeaseStart.Onboarded made = leaseStart.onboard(request.lotId(), request.agreementType(), request.startDate(),
                request.tenantIds(), request.occupantIds(), request.termMonths(), request.onExpiry());
        return ResponseEntity.status(HttpStatus.CREATED).body(new Onboarded(
                made.tenancy().uuid(), made.tenancy().lotId(), made.tenancy().startDate(), made.lease().uuid()));
    }

    /**
     * A lease draft for a tenancy, with the period and the standard rent schedule written.
     * 201 with the draft. 404 for an unknown tenancy.
     * 400 instrument.term_months_needed, instrument.term_start_needed.
     * 409 instrument.draft_open, lot.does_not_permit, property.no_template.
     * Needs instrument:create and tenancy_term:create.
     */
    @PreAuthorize("hasAuthority('instrument:create') and hasAuthority('tenancy_term:create')")
    @PostMapping("/api/instruments/lease")
    public ResponseEntity<InstrumentResponse> startLease(@RequestBody @Valid StartLeaseRequest request) {
        return leaseStart.start(request.tenancyId(), request.agreementType(), request.termStart(),
                        request.termMonths(), request.onExpiry())
                .map(InstrumentResponse::from)
                .map(draft -> ResponseEntity.status(HttpStatus.CREATED).body(draft))
                .orElse(ResponseEntity.notFound().build());
    }
}
