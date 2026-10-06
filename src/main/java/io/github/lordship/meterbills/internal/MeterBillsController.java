package io.github.lordship.meterbills.internal;

import io.github.lordship.meterbills.MeterBills;
import io.github.lordship.meterbills.MeterBillsService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
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

@RestController
@RequestMapping("/meterbills") // Could change to "mbilling" or "billing"
public class MeterBillsController {
    private final MeterBillsService meterBillsService;

    public MeterBillsController(MeterBillsService meterBillsService) {
        this.meterBillsService = meterBillsService;
    }

    @PreAuthorize("hasAuthority('meterbills:create')")
    @PostMapping("/create")
    public ResponseEntity<MeterBillsResponse> createMeterBill(@RequestBody @Valid MeterBillsCreateRequest request) {
        MeterBills bill = meterBillsService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(MeterBillsResponse.from(bill));
    }

    @PreAuthorize("hasAuthority('meterbills:view')")
    @GetMapping("/{uuid}")
    public ResponseEntity<MeterBillsResponse> getById(@PathVariable UUID uuid) {
        return meterBillsService.findMeterBillById(uuid)
                .map(b -> ResponseEntity.ok(MeterBillsResponse.from(b)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('meterbills:view')")
    @GetMapping("/meters/{meterId}")
    public ResponseEntity<List<MeterBillsResponse>> getByMeter(@PathVariable UUID meterId) {
        List<MeterBillsResponse> responses = meterBillsService.findMeterByBilling(meterId)
                .stream()
                .map(MeterBillsResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @PreAuthorize("hasAuthority('meterbills:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<MeterBillsResponse> patchMeterBill(
            @PathVariable UUID uuid,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> changes = new HashMap<>();
        if (request.containsKey("billedAmount")) changes.put("billed_amount", request.get("billedAmount"));
        if (request.containsKey("rateAmount")) changes.put("rate_amount", request.get("rateAmount"));
        if (request.containsKey("periodStart")) changes.put("period_start", request.get("periodStart"));
        if (request.containsKey("periodEnd")) changes.put("period_end", request.get("periodEnd"));

        try {
            return meterBillsService.patchMeterBill(uuid, changes)
                    .map(b -> ResponseEntity.ok(MeterBillsResponse.from(b)))
                    .orElse(ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PreAuthorize("hasAuthority('meterbills:delete')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> deleteMeterBill(@PathVariable UUID uuid) {
        return meterBillsService.softDelete(uuid)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // Uses meter relation and reads to determine a charge
    @PreAuthorize("hasAuthority('meterbills:view')")
    @GetMapping("/{lotMeterId}/charge")
    public ResponseEntity<ChargeCalculationResponse> calculateCharge(
            @PathVariable UUID lotMeterId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd) {
        try {
            return ResponseEntity.ok(ChargeCalculationResponse.from(
                    meterBillsService.calculateCharge(lotMeterId, periodStart, periodEnd)));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().build();
        }
    }
}
