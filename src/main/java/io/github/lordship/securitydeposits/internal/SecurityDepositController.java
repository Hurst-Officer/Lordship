package io.github.lordship.securitydeposits.internal;

import io.github.lordship.securitydeposits.HeldDeposit;
import io.github.lordship.securitydeposits.SecurityDepositService;
import io.github.lordship.securitydeposits.SecurityDepositSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deposits held on behalf of tenancies. Separate from payments on purpose --
 * nothing here touches account.balance_cached, and there is no transaction
 * type for a deposit, because that is the one change that would let an office
 * worker credit rent with money that is not theirs.
 */
@RestController
@RequestMapping("/api/security-deposits")
public class SecurityDepositController {

    // JSON field -> column. tenancy and source are set once at creation.
    private static final Map<String, String> PATCHABLE_COLUMNS = Map.ofEntries(
            Map.entry("instrument", "instrument"),
            Map.entry("amount", "amount"),
            Map.entry("collectedOn", "collected_on"),
            Map.entry("settledOn", "settled_on"),
            Map.entry("description", "description"),
            Map.entry("note", "note"));

    private final SecurityDepositService securityDepositService;

    public SecurityDepositController(SecurityDepositService securityDepositService) {
        this.securityDepositService = securityDepositService;
    }

    // collectedOn is required here and nullable in the column on purpose: the
    // money is arriving now, through this form, so the date is never unknown.
    // The column stays nullable for the migration path, where it often is.
    public record CreateDepositRequest(
            @NotNull UUID tenancy,
            @NotNull @Positive BigDecimal amount,
            @NotNull SecurityDepositSource source,
            @NotNull LocalDate collectedOn) { }

    // No source: this endpoint IS the source.
    public record CreateMigratedDepositRequest(
            @NotNull UUID tenancy,
            @NotNull @Positive BigDecimal amount,
            LocalDate collectedOn) { }

    @PreAuthorize("hasAuthority('security_deposit:view')")
    @GetMapping("/{uuid}")
    public ResponseEntity<SecurityDepositResponse> getDeposit(@PathVariable UUID uuid) {
        return securityDepositService.findById(uuid)
                .map(SecurityDepositResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Every deposit this tenancy has ever had, settled ones included. */
    @PreAuthorize("hasAuthority('security_deposit:view')")
    @GetMapping
    public ResponseEntity<List<SecurityDepositResponse>> listByTenancy(
            @RequestParam("tenancy") UUID tenancy) {

        return ResponseEntity.ok(
                securityDepositService.findByTenancy(tenancy).stream()
                        .map(SecurityDepositResponse::from)
                        .toList());
    }

    /** What the park is still holding. The rent roll figures. */
    @PreAuthorize("hasAuthority('security_deposit:view')")
    @GetMapping("/held")
    public ResponseEntity<List<HeldDeposit>> listHeld(@RequestParam("property") UUID propertyId) {
        return ResponseEntity.ok(securityDepositService.findHeldByProperty(propertyId));
    }

    /**
     * Still held, but the tenant has gone -- oldest first. No deadline is
     * applied: the elapsed days are reported and the operator applies their
     * own window.
     */
    @PreAuthorize("hasAuthority('security_deposit:view')")
    @GetMapping("/aging")
    public ResponseEntity<List<HeldDeposit>> listAging(@RequestParam("property") UUID propertyId) {
        return ResponseEntity.ok(securityDepositService.findAgingByProperty(propertyId));
    }

    @PreAuthorize("hasAuthority('security_deposit:create')")
    @PostMapping
    public ResponseEntity<SecurityDepositResponse> createDeposit(
            @Valid @RequestBody CreateDepositRequest request) {

        return securityDepositService
                .create(request.tenancy(), request.amount(), request.collectedOn(), request.source())
                .map(SecurityDepositResponse::from)
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created))
                .orElse(ResponseEntity.notFound().build());
    }

    // Deposits that came across with an acquired park. Separate permission,
    // because loading history is a different act from taking money today.
    @PreAuthorize("hasAuthority('security_deposit:create_migrations')")
    @PostMapping("/migrations")
    public ResponseEntity<SecurityDepositResponse> createMigratedDeposit(
            @Valid @RequestBody CreateMigratedDepositRequest request) {

        return securityDepositService.createMigrated(request.tenancy(), request.amount(), request.collectedOn())
                .map(SecurityDepositResponse::from)
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created))
                .orElse(ResponseEntity.notFound().build());
    }

    // Settling is PATCHing settledOn; clearing it un-settles.
    @PreAuthorize("hasAuthority('security_deposit:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<SecurityDepositResponse> patchDeposit(
            @PathVariable UUID uuid,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> changes = new HashMap<>();
        PATCHABLE_COLUMNS.forEach((jsonField, column) -> {
            if (request.containsKey(jsonField)) {
                changes.put(column, request.get(jsonField));
            }
        });

        return securityDepositService.patchDeposit(uuid, changes)
                .map(SecurityDepositResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // For a row entered in error. Money genuinely returned is settled, not deleted.
    @PreAuthorize("hasAuthority('security_deposit:delete')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> deleteDeposit(@PathVariable UUID uuid) {
        return securityDepositService.deleteDeposit(uuid)
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
}