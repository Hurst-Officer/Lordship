package io.github.lordship.meterbills.internal;

import io.github.lordship.meterbills.UtilityCycle;
import io.github.lordship.meterbills.UtilityCycleService;
import io.github.lordship.meterbills.UtilityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.github.lordship.meterbills.internal.UtilityBillController.columns;
import static io.github.lordship.meterbills.internal.UtilityBillController.found;
import static io.github.lordship.meterbills.internal.UtilityBillController.gone;
import static io.github.lordship.meterbills.internal.UtilityBillController.inScope;

// Each park's utility cycle, one row per service and starting month.
@RestController
@RequestMapping("/api/utility-cycles")
public class UtilityCycleController {

    private static final Map<String, String> FIELDS = Map.of(
            "cycleMonths", "cycle_months", "anchorMonth", "anchor_month", "lagMonths", "lag_months",
            "readDay", "read_day", "effectiveFrom", "effective_from", "notes", "notes");

    // rateGroupId: null for the park's own cycle, or a rate group billed on another rhythm
    public record CreateCycleRequest(
            @NotNull UUID propertyId,
            UUID rateGroupId,
            @NotNull UtilityService service,
            @NotNull Integer cycleMonths,
            @NotNull Integer anchorMonth,
            @NotNull Integer lagMonths,
            Integer readDay,
            @NotNull LocalDate effectiveFrom,
            String notes
    ) {}

    // null billingMonth: the park has no cycle for that service yet
    public record ProposedMonth(LocalDate billingMonth) {}

    private final UtilityCycleService cycleService;

    public UtilityCycleController(UtilityCycleService cycleService) {
        this.cycleService = cycleService;
    }

    @PreAuthorize("hasAuthority('meterbills:view')")
    @GetMapping
    public ResponseEntity<List<UtilityCycle>> list(@RequestParam UUID propertyId, Authentication auth) {
        if (!inScope(Optional.of(propertyId), auth)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(cycleService.of(propertyId));
    }

    @PreAuthorize("hasAuthority('meterbills:edit')")
    @GetMapping("/deleted")
    public ResponseEntity<List<UtilityCycle>> deleted(@RequestParam UUID propertyId, Authentication auth) {
        if (!inScope(Optional.of(propertyId), auth)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(cycleService.deleted(propertyId));
    }

    // The rent month a bill for this service would land in, so the bill form can fill it in.
    // rateGroupId: the group the bill is entered under; its own cycle is used when it has one, else the park's.
    @PreAuthorize("hasAuthority('meterbills:view')")
    @GetMapping("/proposed-month")
    public ResponseEntity<ProposedMonth> proposedMonth(
            @RequestParam UUID propertyId,
            @RequestParam(required = false) UUID rateGroupId,
            @RequestParam UtilityService service,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            Authentication auth) {
        if (!inScope(Optional.of(propertyId), auth)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(new ProposedMonth(
                cycleService.proposedBillingMonth(propertyId, rateGroupId, service, periodEnd).orElse(null)));
    }

    // 409 when the park (or that rate group) already has a cycle for that service starting that month
    @PreAuthorize("hasAuthority('meterbills:edit')")
    @PostMapping
    public ResponseEntity<UtilityCycle> create(@Valid @RequestBody CreateCycleRequest r, Authentication auth) {
        if (!inScope(Optional.of(r.propertyId()), auth)) return ResponseEntity.notFound().build();
        return ResponseEntity.status(HttpStatus.CREATED).body(cycleService.create(
                r.propertyId(), r.rateGroupId(), r.service(), r.cycleMonths(), r.anchorMonth(), r.lagMonths(),
                r.readDay(), r.effectiveFrom(), r.notes()));
    }

    @PreAuthorize("hasAuthority('meterbills:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<UtilityCycle> patch(@PathVariable UUID uuid, @RequestBody Map<String, Object> body, Authentication auth) {
        if (!inScope(cycleService.propertyOf(uuid), auth)) return ResponseEntity.notFound().build();
        return found(cycleService.patch(uuid, columns(body, FIELDS)));
    }

    @PreAuthorize("hasAuthority('meterbills:edit')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> delete(@PathVariable UUID uuid, Authentication auth) {
        if (!inScope(cycleService.propertyOf(uuid), auth)) return ResponseEntity.notFound().build();
        return gone(cycleService.delete(uuid));
    }

    @PreAuthorize("hasAuthority('meterbills:edit')")
    @PostMapping("/{uuid}/restore")
    public ResponseEntity<UtilityCycle> restore(@PathVariable UUID uuid, Authentication auth) {
        if (!inScope(cycleService.propertyOf(uuid), auth)) return ResponseEntity.notFound().build();
        return found(cycleService.restore(uuid));
    }
}
