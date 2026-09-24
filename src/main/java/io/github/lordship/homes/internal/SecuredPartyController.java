package io.github.lordship.homes.internal;

import io.github.lordship.homes.SecuredParty;
import io.github.lordship.homes.SecuredPartyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/secured-parties")
public class SecuredPartyController {

    public record SecuredPartyCreateRequest(
            @NotNull
            UUID mobileHomeId,

            @NotNull
            UUID personId,

            LocalDate startDate
    ) { }

    private final SecuredPartyService securedPartyService;

    public SecuredPartyController(SecuredPartyService securedPartyService) {
        this.securedPartyService = securedPartyService;
    }

    @PreAuthorize("hasAuthority('homes:create')")
    @PostMapping
    public ResponseEntity<SecuredPartyResponse> createSecuredParty(@Valid @RequestBody SecuredPartyCreateRequest request) {
        SecuredParty securedParty = securedPartyService.createSecuredParty(
                request.mobileHomeId(), request.personId(), request.startDate());
        return ResponseEntity.status(HttpStatus.CREATED).body(SecuredPartyResponse.from(securedParty));
    }

    @PreAuthorize("hasAuthority('homes:view')")
    @GetMapping("/{uuid}")
    public ResponseEntity<SecuredPartyResponse> getSecuredParty(@PathVariable UUID uuid) {
        return securedPartyService.findById(uuid)
                .map(SecuredPartyResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Who has a claim on this home. activeOnly=false adds claims that have already ended.
    @PreAuthorize("hasAuthority('homes:view')")
    @GetMapping("/home/{mobileHomeId}")
    public ResponseEntity<List<SecuredPartyResponse>> getByHome(
            @PathVariable UUID mobileHomeId,
            @RequestParam(defaultValue = "true") boolean activeOnly) {

        List<SecuredParty> securedParties = activeOnly
                ? securedPartyService.findActiveByHome(mobileHomeId)
                : securedPartyService.findByHome(mobileHomeId);

        return ResponseEntity.ok(securedParties.stream().map(SecuredPartyResponse::from).toList());
    }

    @PreAuthorize("hasAuthority('homes:view')")
    @GetMapping("/person/{personId}")
    public ResponseEntity<List<SecuredPartyResponse>> getByPerson(@PathVariable UUID personId) {
        return ResponseEntity.ok(
                securedPartyService.findByPerson(personId).stream()
                        .map(SecuredPartyResponse::from)
                        .toList());
    }

    // Releasing a claim is setting endDate, so there is no separate release endpoint.
    @PreAuthorize("hasAuthority('homes:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<SecuredPartyResponse> patchSecuredParty(
            @PathVariable UUID uuid,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> changes = new HashMap<>();

        if (request.containsKey("startDate"))      changes.put("start_date", request.get("startDate"));
        if (request.containsKey("endDate"))        changes.put("end_date", request.get("endDate"));
        if (request.containsKey("acceptPayments")) changes.put("accept_payments", request.get("acceptPayments"));

        return securedPartyService.patchSecuredParty(uuid, changes)
                .map(SecuredPartyResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('homes:delete')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> deleteSecuredParty(@PathVariable UUID uuid) {
        return securedPartyService.deleteSecuredParty(uuid)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // IllegalArgumentException -> 400 is handled globally by ApiExceptionHandler
    // (see StickBuiltController). IllegalStateException isn't, so this one stays
    // local -- matches InterestedPartyController's conflict() handler.
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", String.valueOf(e.getMessage())));
    }
}
