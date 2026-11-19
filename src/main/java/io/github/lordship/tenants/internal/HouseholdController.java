package io.github.lordship.tenants.internal;

import io.github.lordship.shared.AgreementType;
import io.github.lordship.tenancy.Tenancy;
import io.github.lordship.tenants.Household;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The household steps of Glade's Tenancies screens: a new tenancy with the people moving in, and the restores
 * behind the undo of a deleted stay.
 *
 * <p>Refusals are answered by config ApiExceptionHandler as { message, code } in the worker's language.
 */
@Validated
@RestController
public class HouseholdController {

    /**
     * agreementType is the kind of agreement the lot must be open for (LAND, RESIDENTIAL...).
     * startDate is optional (yyyy-MM-dd); left out, the server picks the billing period the office is working in.
     * tenantIds needs at least one person; occupantIds may be left out.
     */
    public record OpenTenancyRequest(
            @NotNull UUID lotId,
            @NotNull AgreementType agreementType,
            LocalDate startDate,
            List<UUID> tenantIds,
            List<UUID> occupantIds
    ) { }

    // What a new tenancy answers: the tenancy's id, lot and start date as saved
    public record OpenedTenancy(UUID id, UUID lotId, LocalDate startDate) {
        static OpenedTenancy from(Tenancy tenancy) {
            return new OpenedTenancy(tenancy.uuid(), tenancy.lotId(), tenancy.startDate());
        }
    }

    private final Household household;

    public HouseholdController(Household household) {
        this.household = household;
    }

    /**
     * A new tenancy on an open lot, with the household, all or nothing. Also creates the tenancy's account.
     * 201 with { id, lotId, startDate }.
     * 400 lot.unknown, tenancy.kind_needed, tenancy.tenant_needed, tenancy.person_twice.
     * 409 tenancy.lot_not_rentable, lot.does_not_permit, tenancy.lot_taken, tenancy.lot_full_in_month.
     * Needs tenancy:create and tenants:create.
     */
    @PreAuthorize("hasAuthority('tenancy:create') and hasAuthority('tenants:create')")
    @PostMapping("/api/tenancy/open")
    public ResponseEntity<OpenedTenancy> open(@RequestBody @Valid OpenTenancyRequest request) {
        Tenancy tenancy = household.open(request.lotId(), request.agreementType(), request.startDate(),
                request.tenantIds(), request.occupantIds());
        return ResponseEntity.status(HttpStatus.CREATED).body(OpenedTenancy.from(tenancy));
    }

    // Brings a deleted tenant stay back. A stay that is not deleted is answered as the stay is.
    // 404 for an unknown stay. 409 tenant.already_on.
    @PreAuthorize("hasAuthority('tenants:delete')")
    @PostMapping("/api/tenants/{uuid}/restore")
    public ResponseEntity<TenantResponse> restoreTenant(@PathVariable UUID uuid) {
        return household.restoreTenant(uuid)
                .map(tenant -> ResponseEntity.ok(TenantResponse.from(tenant)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('tenants:delete')")
    @PostMapping("/api/occupants/{uuid}/restore")
    public ResponseEntity<OccupantResponse> restoreOccupant(@PathVariable UUID uuid) {
        return household.restoreOccupant(uuid)
                .map(occupant -> ResponseEntity.ok(OccupantResponse.from(occupant)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('tenants:delete')")
    @PostMapping("/api/interested-party/{uuid}/restore")
    public ResponseEntity<InterestedPartyResponse> restoreInterestedParty(@PathVariable UUID uuid) {
        return household.restoreInterestedParty(uuid)
                .map(party -> ResponseEntity.ok(InterestedPartyResponse.from(party)))
                .orElse(ResponseEntity.notFound().build());
    }
}
