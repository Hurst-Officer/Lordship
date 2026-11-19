package io.github.lordship.tenants;

import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.RuleConflict;
import io.github.lordship.tenancy.Tenancy;
import io.github.lordship.tenancy.TenancyService;
import io.github.lordship.tenants.internal.InterestedPartyRepository;
import io.github.lordship.tenants.internal.InterestedPartyRow;
import io.github.lordship.tenants.internal.OccupantRepository;
import io.github.lordship.tenants.internal.OccupantRow;
import io.github.lordship.tenants.internal.TenantRepository;
import io.github.lordship.tenants.internal.TenantRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What Glade's Tenancies screens do to a household as one step: start a tenancy together with the people moving
 * in, and bring back a stay that was deleted (the undo of a delete).
 *
 * <p>Adding, ending and deleting one person's stay stay with TenantService, OccupantService and
 * InterestedPartyService.
 */
@Service
public class Household {

    private final TenancyService tenancyService;
    private final TenantService tenantService;
    private final OccupantService occupantService;
    private final TenantRepository tenantRepository;
    private final OccupantRepository occupantRepository;
    private final InterestedPartyRepository interestedPartyRepository;
    private final AuditService auditService;

    public Household(TenancyService tenancyService,
                     TenantService tenantService,
                     OccupantService occupantService,
                     TenantRepository tenantRepository,
                     OccupantRepository occupantRepository,
                     InterestedPartyRepository interestedPartyRepository,
                     AuditService auditService) {
        this.tenancyService = tenancyService;
        this.tenantService = tenantService;
        this.occupantService = occupantService;
        this.tenantRepository = tenantRepository;
        this.occupantRepository = occupantRepository;
        this.interestedPartyRepository = interestedPartyRepository;
        this.auditService = auditService;
    }

    /**
     * Starts a tenancy on a lot that is open for the kind of agreement picked (TenancyService.openOn), and puts
     * the household on the tenancy: each tenant and each occupant starts on the tenancy's start date.
     * All in one transaction: a refusal for any person leaves no tenancy behind.
     *
     * <p>A tenancy needs at least one tenant (400 tenancy.tenant_needed, on tenantIds). A person named twice,
     * or as both a tenant and an occupant, is a 400 (tenancy.person_twice): one person is on a lease or lives
     * under one, not both.
     */
    @Transactional
    public Tenancy open(UUID lotId, AgreementType agreementType, LocalDate startDate,
                        List<UUID> tenantIds, List<UUID> occupantIds) {
        List<UUID> tenants = tenantIds == null ? List.of() : tenantIds;
        List<UUID> occupants = occupantIds == null ? List.of() : occupantIds;
        if (tenants.isEmpty()) {
            throw InvalidRequest.onField("tenantIds", "tenancy.tenant_needed");
        }
        Set<UUID> seen = new LinkedHashSet<>();
        for (UUID personId : tenants) {
            if (personId == null || !seen.add(personId)) {
                throw InvalidRequest.onField("tenantIds", "tenancy.person_twice");
            }
        }
        for (UUID personId : occupants) {
            if (personId == null || !seen.add(personId)) {
                throw InvalidRequest.onField("occupantIds", "tenancy.person_twice");
            }
        }

        Tenancy tenancy = tenancyService.openOn(lotId, agreementType, startDate);
        for (UUID personId : tenants) {
            tenantService.create(tenancy.uuid(), personId, tenancy.startDate());
        }
        for (UUID personId : occupants) {
            occupantService.create(tenancy.uuid(), personId, tenancy.startDate());
        }
        return tenancy;
    }

    /**
     * Brings a deleted tenant stay back. A stay that is not deleted is answered as the stay is, so sending the
     * restore twice is safe. Empty for an unknown stay.
     * 409 tenant.already_on when the person was put on the tenancy again meanwhile.
     */
    @Transactional
    public Optional<Tenant> restoreTenant(UUID uuid) {
        Optional<TenantRow> beforeOpt = tenantRepository.findAnyById(uuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        TenantRow before = beforeOpt.get();
        if (before.deletedAt() == null) {
            return Optional.of(before.toTenant());
        }
        if (before.endDate() == null) {
            tenantRepository.findActiveByTenancyAndPerson(before.tenancyId(), before.personId())
                    .ifPresent(other -> {
                        throw RuleConflict.of("tenant.already_on", before.personId(), before.tenancyId(), other.uuid());
                    });
        }
        Optional<TenantRow> afterOpt = tenantRepository.restore(uuid);
        afterOpt.ifPresent(after -> {
            AuditMapper.Diff diff = AuditMapper.diff(before, after);
            auditService.recordUpdate("tenant", uuid, diff.before(), diff.after());
        });
        return afterOpt.map(TenantRow::toTenant);
    }

    // The same for an occupant's stay. 409 occupant.already_on.
    @Transactional
    public Optional<Occupant> restoreOccupant(UUID uuid) {
        Optional<OccupantRow> beforeOpt = occupantRepository.findAnyById(uuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        OccupantRow before = beforeOpt.get();
        if (before.deletedAt() == null) {
            return Optional.of(before.toOccupant());
        }
        if (before.endDate() == null) {
            occupantRepository.findActiveByTenancyAndPerson(before.tenancyId(), before.personId())
                    .ifPresent(other -> {
                        throw RuleConflict.of("occupant.already_on", before.personId(), before.tenancyId(), other.uuid());
                    });
        }
        Optional<OccupantRow> afterOpt = occupantRepository.restore(uuid);
        afterOpt.ifPresent(after -> {
            AuditMapper.Diff diff = AuditMapper.diff(before, after);
            auditService.recordUpdate("occupant", uuid, diff.before(), diff.after());
        });
        return afterOpt.map(OccupantRow::toOccupant);
    }

    // The same for an interested party. 409 interested_party.already_on.
    @Transactional
    public Optional<InterestedParty> restoreInterestedParty(UUID uuid) {
        Optional<InterestedPartyRow> beforeOpt = interestedPartyRepository.findAnyById(uuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        InterestedPartyRow before = beforeOpt.get();
        if (before.deletedAt() == null) {
            return Optional.of(before.toInterestedParty());
        }
        if (before.endDate() == null) {
            interestedPartyRepository.findActiveByTenancyAndPerson(before.tenancyId(), before.personId())
                    .ifPresent(other -> {
                        throw RuleConflict.of("interested_party.already_on",
                                before.personId(), before.tenancyId(), other.uuid());
                    });
        }
        Optional<InterestedPartyRow> afterOpt = interestedPartyRepository.restore(uuid);
        afterOpt.ifPresent(after -> {
            AuditMapper.Diff diff = AuditMapper.diff(before, after);
            auditService.recordUpdate("tenancy_interested_party", uuid, diff.before(), diff.after());
        });
        return afterOpt.map(InterestedPartyRow::toInterestedParty);
    }
}
