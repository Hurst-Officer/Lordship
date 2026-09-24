package io.github.lordship.tenants.internal;

import io.github.lordship.tenants.InterestedParty;
import io.github.lordship.tenants.InterestedPartyService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;


// note: permissions to edit tenants cross over here to this domain
@Validated
@RestController
@RequestMapping("/api/interested-party")
public class InterestedPartyController {

    public record InterestedPartyCreateRequest (
            @NotNull
            UUID tenancyId,

            @NotNull
            UUID personId,

            LocalDate startDate
    ) { }

    private final InterestedPartyService interestedPartyService;

    public InterestedPartyController(InterestedPartyService interestedPartyService) {
        this.interestedPartyService = interestedPartyService;
    }

    @PreAuthorize("hasAuthority('tenants:create')")
    @PostMapping("/create")
    public ResponseEntity<InterestedPartyResponse> createInterestedParty (@RequestBody @Valid  InterestedPartyCreateRequest request) {
        InterestedParty interestedParty = interestedPartyService.create(request.tenancyId(), request.personId(), request.startDate());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(InterestedPartyResponse.from(interestedParty));
    }

    @PreAuthorize("hasAuthority('tenants:view')")
    @GetMapping("/{uuid}")
    public ResponseEntity<InterestedPartyResponse> getById(@PathVariable UUID uuid) {
        return interestedPartyService.findById(uuid)
                .map(i -> ResponseEntity.ok(InterestedPartyResponse.from(i)))
                .orElse(ResponseEntity.notFound().build());
    }

    // Who are the interested parties of the tenancy. activeOnly=false adds the ones that have already concluded
    @PreAuthorize("hasAuthority('tenants:view')")
    @GetMapping("/tenancy/{tenancyId}")
    public ResponseEntity<List<InterestedPartyResponse>> getByTenancy(
            @PathVariable UUID tenancyId,
            @RequestParam(defaultValue = "true") boolean activeOnly) {

        List<InterestedParty> interestedParties = activeOnly
                ? interestedPartyService.findActiveByTenancy(tenancyId)
                : interestedPartyService.findByTenancy(tenancyId);

        return ResponseEntity.ok(interestedParties.stream().map(InterestedPartyResponse::from).toList());
    }

    @PreAuthorize("hasAuthority('tenants:view')")
    @GetMapping("/person/{personId}")
    public ResponseEntity<List<InterestedPartyResponse>> getByPerson(@PathVariable UUID personId) {
        return ResponseEntity.ok(
                interestedPartyService.findByPerson(personId).stream()
                        .map(InterestedPartyResponse::from)
                        .toList());
    }

    // Moving out is setting endDate, so there is no separate move-out endpoint.
    @PreAuthorize("hasAuthority('tenants:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<InterestedPartyResponse> patchInterestedParty(
            @PathVariable UUID uuid,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> changes = new HashMap<>();

        if (request.containsKey("notificationReason")) {
            changes.put("notification_reason", request.get("notificationReason"));
        }

        if (request.containsKey("startDate")) {
            changes.put("start_date", request.get("startDate"));
        }

        if (request.containsKey("endDate")) {
            changes.put("end_date", request.get("endDate"));
        }

        if (request.containsKey("notes")) {
            changes.put("notes", request.get("notes"));
        }

        if (request.containsKey("acceptPayments")) {
            changes.put("accept_payments", request.get("acceptPayments"));
        }

        return interestedPartyService.patchInterestedParty(uuid, changes)
                .map(i -> ResponseEntity.ok(InterestedPartyResponse.from(i)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('tenants:delete')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> deleteInterestedParty(@PathVariable UUID uuid) {
        return interestedPartyService.softDelete(uuid)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(EntityNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", String.valueOf(e.getMessage())));
    }

}
