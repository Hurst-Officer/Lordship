package io.github.lordship.lots.internal;

import io.github.lordship.lots.Lot;
import io.github.lordship.lots.LotService;
import io.github.lordship.shared.AgreementType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
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

@RestController
@RequestMapping("/api/lots")
public class LotController {

    private final LotService lotService;

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

    public LotController(LotService lotService) {
        this.lotService = lotService;
    }

    @PreAuthorize("hasAuthority('lots:create')")
    @PostMapping
    public ResponseEntity<LotResponse> createLot(@Valid @RequestBody LotCreationRequest request) {
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
            @RequestParam(value = "agreementType", required = false) AgreementType agreementType) {

        List<Lot> found = (agreementType == null)
                ? lotService.findByProperty(propertyCode)
                : lotService.findByPropertyPermitting(propertyCode, agreementType);

        return ResponseEntity.ok(found.stream().map(LotResponse::from).toList());
    }

    @PreAuthorize("hasAuthority('lots:view')")
    @GetMapping("/{uuid}")
    public ResponseEntity<LotResponse> getLot(@PathVariable UUID uuid) {
        return lotService.findById(uuid)
                .map(LotResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('lots:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<LotResponse> patchLot(
            @PathVariable UUID uuid,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> changes = new HashMap<>();

        if (request.containsKey("lotNumber"))         changes.put("lot_number", request.get("lotNumber"));
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
            @Valid @RequestBody(required = false) PermitAgreementTypeRequest request) {

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
            @PathVariable AgreementType agreementType) {

        return lotService.revokeAgreementType(uuid, agreementType)
                .map(LotResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // The owner's pricing pass. Separate from lots:edit because repricing a
    // box-selection of a park is a different act from renaming a lot.
    @PreAuthorize("hasAuthority('lots:set_rates')")
    @PutMapping("/rates")
    public ResponseEntity<SetRatesResult> setRates(@Valid @RequestBody SetRatesRequest request) {

        int updated = lotService.setRates(request.lotIds(), request.agreementType(),
                request.targetRate(), request.askingRate());

        return ResponseEntity.ok(new SetRatesResult(updated));
    }

    @PreAuthorize("hasAuthority('lots:delete')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> deleteLot(@PathVariable UUID uuid) {
        return lotService.deleteLot(uuid)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // Carries the message: "this property has no terms template for STORAGE" is
    // the whole content of the refusal, and an empty 409 throws it away.
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", String.valueOf(e.getMessage())));
    }
}