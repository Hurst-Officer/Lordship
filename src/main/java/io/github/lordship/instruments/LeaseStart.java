package io.github.lordship.instruments;

import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.RuleConflict;
import io.github.lordship.tenancy.Tenancy;
import io.github.lordship.tenancyterms.RentStep;
import io.github.lordship.tenants.Household;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Starting a lease as one step, for Glade's Tenancies screens: the draft, the draft's period and the rent
 * schedule are made together, so a draft never sits without the rent steps a preview needs.
 *
 * <p>onboard also makes the tenancy and puts the household on the tenancy first (Household.open), all in one
 * transaction: a refusal anywhere leaves no tenancy, nobody on a tenancy and no draft.
 *
 * <p>Everything after the draft (editing the period, the rent steps, preview, generate) stays with
 * InstrumentService and TenancyChargeTermService.
 */
@Service
public class LeaseStart {

    // A new tenancy and the lease drafted for the tenancy
    public record Onboarded(Tenancy tenancy, Instrument lease) { }

    private final Household household;
    private final InstrumentService instrumentService;

    public LeaseStart(Household household, InstrumentService instrumentService) {
        this.household = household;
        this.instrumentService = instrumentService;
    }

    /**
     * A new tenancy with the household, and the tenancy's lease as a draft that starts on the tenancy's start
     * date. termMonths is how long the lease runs; onExpiry what the lease says happens after (may be null).
     *
     * <p>Refusals are Household.open's and start's. Any refusal undoes the whole step.
     */
    @Transactional
    public Onboarded onboard(UUID lotId, AgreementType agreementType, LocalDate startDate,
                             List<UUID> tenantIds, List<UUID> occupantIds,
                             Integer termMonths, OnExpiry onExpiry) {
        requireMonths(termMonths);
        Tenancy tenancy = household.open(lotId, agreementType, startDate, tenantIds, occupantIds);
        Instrument lease = start(tenancy.uuid(), agreementType, null, termMonths, onExpiry)
                .orElseThrow(() -> new IllegalStateException("The new tenancy was not found: " + tenancy.uuid()));
        return new Onboarded(tenancy, lease);
    }

    /**
     * A lease draft for a tenancy, with the lease's period and the standard rent schedule written: the lot's
     * asking rate (or the property's deal's), stepped by the deal's escalation over termMonths.
     * termStart null leaves the start to InstrumentService.createDraft: the day after the tenancy's latest lease
     * ends, else the tenancy's start date.
     *
     * <p>Empty for an unknown tenancy.
     * 400 instrument.term_months_needed (termMonths missing or under 1), instrument.term_start_needed (the
     * tenancy has no start date and none was given).
     * 409 instrument.draft_open (the tenancy already has a lease draft: finish or abandon that draft first),
     * lot.does_not_permit, property.no_template (the property has no deal for the kind).
     */
    @Transactional
    public Optional<Instrument> start(UUID tenancyId, AgreementType agreementType, LocalDate termStart,
                                      Integer termMonths, OnExpiry onExpiry) {
        requireMonths(termMonths);
        for (Instrument paper : instrumentService.findByTenancy(tenancyId)) {
            if (paper.type() == InstrumentType.LEASE && paper.status() == InstrumentStatus.DRAFT) {
                throw RuleConflict.of("instrument.draft_open");
            }
        }

        Optional<Instrument> draft = instrumentService.createDraft(tenancyId, InstrumentType.LEASE, agreementType);
        if (draft.isEmpty()) {
            return Optional.empty();
        }
        UUID lease = draft.get().uuid();

        // column names, the way InstrumentController hands a PATCH to patchDraft
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("term_months", termMonths);
        if (termStart != null) {
            period.put("term_start", termStart);
        }
        if (onExpiry != null) {
            period.put("on_expiry", onExpiry.name());
        }
        Instrument dated = instrumentService.patchDraft(lease, period)
                .orElseThrow(() -> new IllegalStateException("The new draft was not found: " + lease));
        if (dated.termStart() == null) {
            throw InvalidRequest.onField("termStart", "instrument.term_start_needed");
        }

        List<RentStep> steps = instrumentService.previewSchedule(lease)
                .orElseThrow(() -> new IllegalStateException("The new draft was not found: " + lease));
        instrumentService.writeSchedule(lease, steps);
        return Optional.of(dated);
    }

    private static void requireMonths(Integer termMonths) {
        if (termMonths == null || termMonths < 1) {
            throw InvalidRequest.onField("termMonths", "instrument.term_months_needed");
        }
    }
}
