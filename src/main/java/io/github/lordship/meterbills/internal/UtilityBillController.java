package io.github.lordship.meterbills.internal;

import io.github.lordship.identity.AgentPrincipal;
import io.github.lordship.meterbills.BillDetail;
import io.github.lordship.meterbills.UtilityBill;
import io.github.lordship.meterbills.UtilityBillService;
import io.github.lordship.meterbills.UtilityService;
import io.github.lordship.meters.MeterUnit;
import io.github.lordship.shared.Patches;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// Utility bills, typed in as received. A bill in a property outside the agent's
// scope (see PropertyScope) is treated as if it did not exist: 404.
// Answers carry the whole bill with its checks, so a screen redraws from one reply.
@RestController
@RequestMapping("/api/utility-bills")
public class UtilityBillController {

    // The scanned bill (documentFile) is set by its upload, once uploads come through the document reader
    private static final Map<String, String> BILL_FIELDS = Map.of(
            "rateGroupId", "rate_group_id", "provider", "provider", "accountNumber", "account_number",
            "statementDate", "statement_date", "periodStart", "period_start", "periodEnd", "period_end",
            "printedTotal", "printed_total", "notes", "notes");
    private static final Map<String, String> METER_FIELDS = Map.of(
            "printedUsage", "printed_usage", "printedUnit", "printed_unit");
    private static final Map<String, String> LINE_FIELDS = Map.of(
            "service", "service", "description", "description", "amount", "amount", "printedRate", "printed_rate",
            "rateUnit", "rate_unit", "passedOn", "passed_on", "billingMonth", "billing_month", "sortOrder", "sort_order");

    // rateGroupId: null for the park's default group. allowDuplicate: the person has seen the look-alikes and goes on.
    public record CreateBillRequest(
            @NotNull UUID propertyId,
            UUID rateGroupId,
            String provider,
            String accountNumber,
            LocalDate statementDate,
            @NotNull LocalDate periodStart,
            @NotNull LocalDate periodEnd,
            BigDecimal printedTotal,
            String notes,
            Boolean allowDuplicate
    ) {}

    // The reads come in pairs; printedUsage and printedUnit come together. At least one of the two.
    public record AddMeterRequest(
            @NotNull UUID mainId,
            LocalDate priorReadOn,
            Long priorReading,
            Boolean priorEstimated,
            LocalDate currentReadOn,
            Long currentReading,
            Boolean currentEstimated,
            BigDecimal printedUsage,
            MeterUnit printedUnit,
            Boolean allowDuplicate
    ) {}

    // passedOn defaults to true; billingMonth to the park's cycle for the service; sortOrder to the end of the bill
    public record AddLineRequest(
            @NotNull UtilityService service,
            String description,
            @NotNull BigDecimal amount,
            BigDecimal printedRate,
            MeterUnit rateUnit,
            Boolean passedOn,
            LocalDate billingMonth,
            Integer sortOrder
    ) {}

    private final UtilityBillService billService;

    public UtilityBillController(UtilityBillService billService) {
        this.billService = billService;
    }

    // ── Bills ──

    // A park's bills with a line in a rent month (billingMonth, any day of it), newest first;
    // without billingMonth, the bills whose service period ended in the last 13 months
    @PreAuthorize("hasAuthority('meterbills:view')")
    @GetMapping
    public ResponseEntity<List<BillDetail>> list(
            @RequestParam UUID propertyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate billingMonth,
            Authentication auth) {
        if (!inScope(Optional.of(propertyId), auth)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(billService.of(propertyId, billingMonth));
    }

    // Bills that may be the one being entered, to show before saving it (see allowDuplicate)
    @PreAuthorize("hasAuthority('meterbills:view')")
    @GetMapping("/look-alikes")
    public ResponseEntity<List<BillDetail>> lookAlikes(
            @RequestParam UUID propertyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String accountNumber,
            @RequestParam(required = false) BigDecimal printedTotal,
            @RequestParam(required = false) UUID mainId,
            @RequestParam(required = false) UUID exceptBillId,
            Authentication auth) {
        if (!inScope(Optional.of(propertyId), auth)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(billService.lookAlikes(propertyId, exceptBillId, periodStart, periodEnd,
                provider, accountNumber, printedTotal, mainId));
    }

    @PreAuthorize("hasAuthority('meterbills:delete')")
    @GetMapping("/deleted")
    public ResponseEntity<List<UtilityBill>> deleted(@RequestParam UUID propertyId, Authentication auth) {
        if (!inScope(Optional.of(propertyId), auth)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(billService.deleted(propertyId));
    }

    @PreAuthorize("hasAuthority('meterbills:view')")
    @GetMapping("/{uuid}")
    public ResponseEntity<BillDetail> get(@PathVariable UUID uuid, Authentication auth) {
        if (!inScope(billService.propertyOfBill(uuid), auth)) return ResponseEntity.notFound().build();
        return found(billService.find(uuid));
    }

    // A bill's deleted lines and meters
    @PreAuthorize("hasAuthority('meterbills:edit')")
    @GetMapping("/{uuid}/deleted")
    public ResponseEntity<Map<String, List<?>>> deletedParts(@PathVariable UUID uuid, Authentication auth) {
        if (!inScope(billService.propertyOfBill(uuid), auth)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(billService.deletedParts(uuid));
    }

    // 409 utility_bill.possible_duplicate when it looks like a bill already entered, unless allowDuplicate
    @PreAuthorize("hasAuthority('meterbills:create')")
    @PostMapping
    public ResponseEntity<BillDetail> create(@Valid @RequestBody CreateBillRequest r, Authentication auth) {
        if (!inScope(Optional.of(r.propertyId()), auth)) return ResponseEntity.notFound().build();
        return ResponseEntity.status(HttpStatus.CREATED).body(billService.createBill(
                r.propertyId(), r.rateGroupId(), r.provider(), r.accountNumber(), r.statementDate(), r.periodStart(),
                r.periodEnd(), r.printedTotal(), r.notes(), Boolean.TRUE.equals(r.allowDuplicate())));
    }

    // A moved service period is checked for look-alikes again; send "allowDuplicate": true to go on
    @PreAuthorize("hasAuthority('meterbills:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<BillDetail> patch(@PathVariable UUID uuid, @RequestBody Map<String, Object> body, Authentication auth) {
        if (!inScope(billService.propertyOfBill(uuid), auth)) return ResponseEntity.notFound().build();
        boolean allowDuplicate = Boolean.TRUE.equals(Patches.bool(body.get("allowDuplicate"), "allowDuplicate"));
        return found(billService.patchBill(uuid, columns(body, BILL_FIELDS), allowDuplicate));
    }

    @PreAuthorize("hasAuthority('meterbills:delete')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> delete(@PathVariable UUID uuid, Authentication auth) {
        if (!inScope(billService.propertyOfBill(uuid), auth)) return ResponseEntity.notFound().build();
        return gone(billService.deleteBill(uuid));
    }

    // 409 when it would now look like another bill, unless allowDuplicate=true
    @PreAuthorize("hasAuthority('meterbills:delete')")
    @PostMapping("/{uuid}/restore")
    public ResponseEntity<BillDetail> restore(@PathVariable UUID uuid,
                                              @RequestParam(defaultValue = "false") boolean allowDuplicate,
                                              Authentication auth) {
        if (!inScope(billService.propertyOfBill(uuid), auth)) return ResponseEntity.notFound().build();
        return found(billService.restoreBill(uuid, allowDuplicate));
    }

    // ── The mains a bill is for ──

    // 409 when a printed read disagrees with the read on file for that main and day,
    // or (unless allowDuplicate) another bill already names the main for these dates
    @PreAuthorize("hasAuthority('meterbills:edit')")
    @PostMapping("/{uuid}/meters")
    public ResponseEntity<BillDetail> addMeter(@PathVariable UUID uuid, @Valid @RequestBody AddMeterRequest r, Authentication auth) {
        if (!inScope(billService.propertyOfBill(uuid), auth)) return ResponseEntity.notFound().build();
        return created(billService.addMeter(uuid, r.mainId(),
                r.priorReadOn(), r.priorReading(), Boolean.TRUE.equals(r.priorEstimated()),
                r.currentReadOn(), r.currentReading(), Boolean.TRUE.equals(r.currentEstimated()),
                r.printedUsage(), r.printedUnit(), Boolean.TRUE.equals(r.allowDuplicate())));
    }

    @PreAuthorize("hasAuthority('meterbills:edit')")
    @PatchMapping("/meters/{uuid}")
    public ResponseEntity<BillDetail> patchMeter(@PathVariable UUID uuid, @RequestBody Map<String, Object> body, Authentication auth) {
        if (!inScope(billService.propertyOfMeter(uuid), auth)) return ResponseEntity.notFound().build();
        return found(billService.patchMeter(uuid, columns(body, METER_FIELDS)));
    }

    @PreAuthorize("hasAuthority('meterbills:edit')")
    @DeleteMapping("/meters/{uuid}")
    public ResponseEntity<Void> deleteMeter(@PathVariable UUID uuid, Authentication auth) {
        if (!inScope(billService.propertyOfMeter(uuid), auth)) return ResponseEntity.notFound().build();
        return gone(billService.deleteMeter(uuid));
    }

    // 409 when another bill now names the main for these dates, unless allowDuplicate=true
    @PreAuthorize("hasAuthority('meterbills:edit')")
    @PostMapping("/meters/{uuid}/restore")
    public ResponseEntity<BillDetail> restoreMeter(@PathVariable UUID uuid,
                                                   @RequestParam(defaultValue = "false") boolean allowDuplicate,
                                                   Authentication auth) {
        if (!inScope(billService.propertyOfMeter(uuid), auth)) return ResponseEntity.notFound().build();
        return found(billService.restoreMeter(uuid, allowDuplicate));
    }

    // ── Lines ──

    @PreAuthorize("hasAuthority('meterbills:edit')")
    @PostMapping("/{uuid}/lines")
    public ResponseEntity<BillDetail> addLine(@PathVariable UUID uuid, @Valid @RequestBody AddLineRequest r, Authentication auth) {
        if (!inScope(billService.propertyOfBill(uuid), auth)) return ResponseEntity.notFound().build();
        return created(billService.addLine(uuid, r.service(), r.description(),
                r.amount(), r.printedRate(), r.rateUnit(), r.passedOn(), r.billingMonth(), r.sortOrder()));
    }

    @PreAuthorize("hasAuthority('meterbills:edit')")
    @PatchMapping("/lines/{uuid}")
    public ResponseEntity<BillDetail> patchLine(@PathVariable UUID uuid, @RequestBody Map<String, Object> body, Authentication auth) {
        if (!inScope(billService.propertyOfLine(uuid), auth)) return ResponseEntity.notFound().build();
        return found(billService.patchLine(uuid, columns(body, LINE_FIELDS)));
    }

    @PreAuthorize("hasAuthority('meterbills:edit')")
    @DeleteMapping("/lines/{uuid}")
    public ResponseEntity<Void> deleteLine(@PathVariable UUID uuid, Authentication auth) {
        if (!inScope(billService.propertyOfLine(uuid), auth)) return ResponseEntity.notFound().build();
        return gone(billService.deleteLine(uuid));
    }

    @PreAuthorize("hasAuthority('meterbills:edit')")
    @PostMapping("/lines/{uuid}/restore")
    public ResponseEntity<BillDetail> restoreLine(@PathVariable UUID uuid, Authentication auth) {
        if (!inScope(billService.propertyOfLine(uuid), auth)) return ResponseEntity.notFound().build();
        return found(billService.restoreLine(uuid));
    }

    // ── Helpers ──

    static boolean inScope(Optional<UUID> propertyId, Authentication auth) {
        return propertyId.isPresent() && AgentPrincipal.scopeOf(auth).allows(propertyId.get());
    }

    static Map<String, Object> columns(Map<String, Object> body, Map<String, String> fields) {
        Map<String, Object> out = new HashMap<>();
        fields.forEach((field, column) -> {
            if (body.containsKey(field)) out.put(column, body.get(field));
        });
        return out;
    }

    // 201 with the bill, or 404 when the bill is not live
    static <T> ResponseEntity<T> created(Optional<T> value) {
        return value.map(v -> ResponseEntity.status(HttpStatus.CREATED).body(v)).orElse(ResponseEntity.notFound().build());
    }

    static <T> ResponseEntity<T> found(Optional<T> value) {
        return value.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    static ResponseEntity<Void> gone(boolean done) {
        return done ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    // No @ExceptionHandler here; ApiExceptionHandler answers in the worker's language.
}
