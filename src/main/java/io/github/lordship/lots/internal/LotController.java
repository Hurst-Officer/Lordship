package io.github.lordship.lots.internal;

import io.github.lordship.audit.AuditService;
import io.github.lordship.audit.History;
import io.github.lordship.identity.AgentPrincipal;
import io.github.lordship.lots.Lot;
import io.github.lordship.lots.LotService;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.PropertyScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// A lot in a property outside the agent's scope (see PropertyScope) is treated as if it did not exist:
// 404 for that lot, left out of lists, and lot.unknown (400) inside a selection.
@RestController
@RequestMapping("/api/lots")
public class LotController {

    private final LotService lotService;
    private final AuditService auditService;

    public record LotCreationRequest(
            @NotNull
            UUID propertyId,

            @NotBlank
            String lotNumber
    ) { }

    // Either rate left out takes the property's template figure for this type.
    public record PermitAgreementTypeRequest(
            @PositiveOrZero
            BigDecimal targetRate,

            @PositiveOrZero
            BigDecimal askingRate
    ) { }

    // One or both figures across a selection, for one kind of deal. The
    // selection comes from a box drag on the map or a column of checkboxes --
    // the API cannot tell, and does not need to. A rate left out is left alone.
    public record SetRatesRequest(
            @NotEmpty
            List<UUID> lotIds,

            @NotNull
            AgreementType agreementType,

            @PositiveOrZero
            BigDecimal targetRate,

            @PositiveOrZero
            BigDecimal askingRate
    ) { }

    public record SetRatesResult(int updated) { }

    // Empties one or both rates across a selection, for one kind of deal. A lot with an empty rate has no
    // rate of the lot's own, and the property's standard rate applies. A rate left out or false is left alone.
    public record ClearRatesRequest(
            @NotEmpty
            @Size(max = 2000)
            List<UUID> lotIds,

            @NotNull
            AgreementType agreementType,

            Boolean targetRate,

            Boolean askingRate
    ) { }

    // Offers one kind of deal on a selection of lots (permitted true), or withdraws the kind from them.
    // The rates are for the lots that gain the kind; either rate left out takes the property's template figure.
    public record SetAgreementTypeRequest(
            @NotEmpty
            @Size(max = 2000)
            List<UUID> lotIds,

            @NotNull
            AgreementType agreementType,

            @NotNull
            Boolean permitted,

            @PositiveOrZero
            BigDecimal targetRate,

            @PositiveOrZero
            BigDecimal askingRate
    ) { }

    // Rentable or not across a selection, from the map or the lots list.
    // notRentableReason is needed when isRentable is false, and replaces each lot's old reason.
    public record SetRentableRequest(
            @NotEmpty
            @Size(max = 2000)
            List<UUID> lotIds,

            @NotNull
            Boolean isRentable,

            String notRentableReason
    ) { }

    public record SetRentableResult(int updated) { }

    // A selection of lots to delete or restore
    public record LotIdsRequest(
            @NotEmpty
            @Size(max = 2000)
            List<UUID> lotIds
    ) { }

    public record LotCountResult(int updated) { }

    public LotController(LotService lotService, AuditService auditService) {
        this.lotService = lotService;
        this.auditService = auditService;
    }

    @PreAuthorize("hasAuthority('lots:create')")
    @PostMapping
    public ResponseEntity<LotResponse> createLot(@Valid @RequestBody LotCreationRequest request,
                                                 Authentication authentication) {
        if (!AgentPrincipal.scopeOf(authentication).allows(request.propertyId)) {
            return ResponseEntity.notFound().build();
        }
        Lot lot = lotService.createLot(request.propertyId, request.lotNumber);
        return ResponseEntity.status(HttpStatus.CREATED).body(LotResponse.from(lot));
    }

    // The map / list feed for a property, ordered for display. Pass
    // agreementType to narrow it to the lots that can host that kind of deal --
    // which is the first step of setting rates.
    @PreAuthorize("hasAuthority('lots:view')")
    @GetMapping
    public ResponseEntity<List<LotResponse>> listByProperty(
            @RequestParam("propertyCode") String propertyCode,
            @RequestParam(value = "agreementType", required = false) AgreementType agreementType,
            Authentication authentication) {

        List<Lot> found = (agreementType == null)
                ? lotService.findByProperty(propertyCode)
                : lotService.findByPropertyPermitting(propertyCode, agreementType);

        PropertyScope scope = AgentPrincipal.scopeOf(authentication);
        return ResponseEntity.ok(found.stream()
                .filter(lot -> scope.allows(lot.propertyId()))
                .map(LotResponse::from)
                .toList());
    }

    @PreAuthorize("hasAuthority('lots:view')")
    @GetMapping("/{uuid}")
    public ResponseEntity<LotResponse> getLot(@PathVariable UUID uuid, Authentication authentication) {
        if (!lotService.inScope(uuid, AgentPrincipal.scopeOf(authentication))) {
            return ResponseEntity.notFound().build();
        }
        return lotService.findById(uuid)
                .map(LotResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('lots:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<LotResponse> patchLot(
            @PathVariable UUID uuid,
            @RequestBody Map<String, Object> request,
            Authentication authentication) {

        if (!lotService.inScope(uuid, AgentPrincipal.scopeOf(authentication))) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> changes = new HashMap<>();

        if (request.containsKey("lotNumber"))         changes.put("lot_number", request.get("lotNumber"));
        if (request.containsKey("titleLink"))         changes.put("title_link", request.get("titleLink"));
        if (request.containsKey("lotAddress"))        changes.put("lot_address", request.get("lotAddress"));
        if (request.containsKey("lotParcel"))         changes.put("lot_parcel", request.get("lotParcel"));
        if (request.containsKey("description"))       changes.put("description", request.get("description"));
        if (request.containsKey("notes"))             changes.put("notes", request.get("notes"));
        if (request.containsKey("isRentable"))        changes.put("is_rentable", request.get("isRentable"));
        if (request.containsKey("notRentableReason")) changes.put("not_rentable_reason", request.get("notRentableReason"));

        return lotService.patchLot(uuid, changes)
                .map(LotResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Lets this lot host a kind of agreement, at a rate. Idempotent: sending it
    // twice sets the rate twice and nothing else. 409 when the property never
    // took that agreement type on -- a space cannot offer what the park does not.
    @PreAuthorize("hasAuthority('lots:edit')")
    @PutMapping("/{uuid}/agreement-types/{agreementType}")
    public ResponseEntity<LotResponse> permitAgreementType(
            @PathVariable UUID uuid,
            @PathVariable AgreementType agreementType,
            @Valid @RequestBody(required = false) PermitAgreementTypeRequest request,
            Authentication authentication) {

        if (!lotService.inScope(uuid, AgentPrincipal.scopeOf(authentication))) {
            return ResponseEntity.notFound().build();
        }

        BigDecimal targetRate = (request == null) ? null : request.targetRate();
        BigDecimal askingRate = (request == null) ? null : request.askingRate();

        return lotService.permitAgreementType(uuid, agreementType, targetRate, askingRate)
                .map(LotResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Governs new agreements only -- a charge term already signed and served is
    // not invalidated by this.
    @PreAuthorize("hasAuthority('lots:edit')")
    @DeleteMapping("/{uuid}/agreement-types/{agreementType}")
    public ResponseEntity<LotResponse> revokeAgreementType(
            @PathVariable UUID uuid,
            @PathVariable AgreementType agreementType,
            Authentication authentication) {

        if (!lotService.inScope(uuid, AgentPrincipal.scopeOf(authentication))) {
            return ResponseEntity.notFound().build();
        }

        return lotService.revokeAgreementType(uuid, agreementType)
                .map(LotResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // The owner's pricing pass. Separate from lots:edit because repricing a
    // box-selection of a park is a different act from renaming a lot.
    @PreAuthorize("hasAuthority('lots:set_rates')")
    @PutMapping("/rates")
    public ResponseEntity<SetRatesResult> setRates(@Valid @RequestBody SetRatesRequest request,
                                                   Authentication authentication) {
        lotService.requireInScope(request.lotIds(), AgentPrincipal.scopeOf(authentication));

        int updated = lotService.setRates(request.lotIds(), request.agreementType(),
                request.targetRate(), request.askingRate());

        return ResponseEntity.ok(new SetRatesResult(updated));
    }

    // The pricing pass's other half: empties a rate across a selection, so the property's standard rate applies.
    // Glade's undo uses this to put a lot back to "no rate of the lot's own". updated counts the lots that changed.
    @PreAuthorize("hasAuthority('lots:set_rates')")
    @PutMapping("/rates/clear")
    public ResponseEntity<SetRatesResult> clearRates(@Valid @RequestBody ClearRatesRequest request,
                                                     Authentication authentication) {
        lotService.requireInScope(request.lotIds(), AgentPrincipal.scopeOf(authentication));

        int updated = lotService.clearRates(request.lotIds(), request.agreementType(),
                Boolean.TRUE.equals(request.targetRate()), Boolean.TRUE.equals(request.askingRate()));

        return ResponseEntity.ok(new SetRatesResult(updated));
    }

    // Offers a kind of deal on a selection of lots, or withdraws the kind from them. All or nothing.
    // A lot that already offers the kind keeps the lot's rates; a lot that does not offer the kind is skipped
    // by a withdrawal. 400 for an unknown lot, 409 when the property has no terms template for the kind.
    // updated counts the lots that actually changed.
    @PreAuthorize("hasAuthority('lots:edit')")
    @PutMapping("/agreement-types")
    public ResponseEntity<LotCountResult> setAgreementType(@Valid @RequestBody SetAgreementTypeRequest request,
                                                           Authentication authentication) {
        lotService.requireInScope(request.lotIds(), AgentPrincipal.scopeOf(authentication));

        int updated = lotService.setAgreementType(request.lotIds(), request.agreementType(),
                request.permitted(), request.targetRate(), request.askingRate());

        return ResponseEntity.ok(new LotCountResult(updated));
    }

    // All or nothing. 400 when a lot is unknown or the reason is missing, and then nothing is saved.
    // updated counts the lots that actually changed.
    @PreAuthorize("hasAuthority('lots:edit')")
    @PutMapping("/rentable")
    public ResponseEntity<SetRentableResult> setRentable(@Valid @RequestBody SetRentableRequest request,
                                                         Authentication authentication) {
        lotService.requireInScope(request.lotIds(), AgentPrincipal.scopeOf(authentication));
        int updated = lotService.setRentable(request.lotIds(), request.isRentable(), request.notRentableReason());
        return ResponseEntity.ok(new SetRentableResult(updated));
    }

    // 409 while the lot has an active tenancy
    @PreAuthorize("hasAuthority('lots:delete')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> deleteLot(@PathVariable UUID uuid, Authentication authentication) {
        if (!lotService.inScope(uuid, AgentPrincipal.scopeOf(authentication))) {
            return ResponseEntity.notFound().build();
        }
        return lotService.deleteLot(uuid)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // All or nothing. 400 for an unknown lot, 409 for a lot with an active tenancy.
    // updated counts the lots that were deleted. One a coworker already deleted is skipped.
    @PreAuthorize("hasAuthority('lots:delete')")
    @PostMapping("/delete")
    public ResponseEntity<LotCountResult> deleteLots(@Valid @RequestBody LotIdsRequest request,
                                                     Authentication authentication) {
        lotService.requireInScope(request.lotIds(), AgentPrincipal.scopeOf(authentication));
        return ResponseEntity.ok(new LotCountResult(lotService.deleteLots(request.lotIds())));
    }

    // All or nothing. 400 for an unknown lot, 409 when the lot's property is deleted.
    // updated counts the lots that came back. One that is not deleted is skipped.
    @PreAuthorize("hasAuthority('lots:delete')")
    @PostMapping("/restore")
    public ResponseEntity<LotCountResult> restoreLots(@Valid @RequestBody LotIdsRequest request,
                                                      Authentication authentication) {
        lotService.requireInScope(request.lotIds(), AgentPrincipal.scopeOf(authentication));
        return ResponseEntity.ok(new LotCountResult(lotService.restoreLots(request.lotIds())));
    }

    // A property's deleted lots, the most recently deleted first.
    // deletedBy is null without audit:view.
    @PreAuthorize("hasAuthority('lots:delete')")
    @GetMapping("/deleted")
    public ResponseEntity<List<DeletedLotResponse>> deletedLots(@RequestParam("propertyId") UUID propertyId,
                                                                Authentication authentication) {
        if (!AgentPrincipal.scopeOf(authentication).allows(propertyId)) {
            return ResponseEntity.notFound().build();
        }
        List<Lot> lots = lotService.findDeletedByProperty(propertyId);
        Map<UUID, History.Mark> who = canViewAudits(authentication)
                ? auditService.deletions("lot", lots.stream().map(Lot::uuid).toList())
                : Map.of();
        return ResponseEntity.ok(lots.stream().map(lot -> DeletedLotResponse.from(lot, who.get(lot.uuid()))).toList());
    }

    private static boolean canViewAudits(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "audit:view".equals(authority.getAuthority()));
    }

    // No @ExceptionHandler here. A local one would win over ApiExceptionHandler and
    // answer with the diagnostic code instead of the sentence in the worker's language.
}