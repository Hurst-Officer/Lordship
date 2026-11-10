package io.github.lordship.meters.internal;

import io.github.lordship.identity.AgentPrincipal;
import io.github.lordship.meters.MeterMain;
import io.github.lordship.meters.MeterService;
import io.github.lordship.meters.MeterSetting;
import io.github.lordship.meters.MeterTree;
import io.github.lordship.meters.MeterType;
import io.github.lordship.meters.MeterUnit;
import io.github.lordship.meters.RateGroup;
import io.github.lordship.meters.Submeter;
import io.github.lordship.meters.SubmeterLotLink;
import io.github.lordship.meters.SubmeterMainLink;
import io.github.lordship.shared.InvalidRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// A park's meter tree. Anything in a property outside the agent's scope (see PropertyScope)
// is treated as if it did not exist: 404.
@RestController
@RequestMapping("/api/meters")
public class MeterController {

    // What a PATCH may change, as the API names it → the column
    private static final Map<String, String> MAIN_FIELDS = Map.ofEntries(
            Map.entry("rateGroupId", "rate_group_id"), Map.entry("title", "title"), Map.entry("provider", "provider"),
            Map.entry("accountNumber", "account_number"), Map.entry("serialNumber", "serial_number"),
            Map.entry("pointX", "point_x"), Map.entry("pointY", "point_y"), Map.entry("installedOn", "installed_on"),
            Map.entry("removedOn", "removed_on"), Map.entry("notes", "notes"));
    private static final Map<String, String> SUBMETER_FIELDS = Map.ofEntries(
            Map.entry("title", "title"), Map.entry("serialNumber", "serial_number"),
            Map.entry("pointX", "point_x"), Map.entry("pointY", "point_y"), Map.entry("installedOn", "installed_on"),
            Map.entry("removedOn", "removed_on"), Map.entry("notes", "notes"));
    private static final Map<String, String> RATE_GROUP_FIELDS = Map.of("name", "name", "notes", "notes");
    private static final Map<String, String> LINK_FIELDS = Map.of("effectiveFrom", "effective_from", "effectiveTo", "effective_to");
    private static final Map<String, String> SETTING_FIELDS = Map.of(
            "effectiveFrom", "effective_from", "unit", "unit", "multiplier", "multiplier", "dialMax", "dial_max", "notes", "notes");

    // unit, multiplier and dialMax make the meter's first setting (no start day)
    public record CreateMainRequest(
            @NotNull UUID propertyId,
            @NotNull MeterType utilityType,
            @NotNull MeterUnit unit,
            String title,
            BigDecimal multiplier,
            Long dialMax,
            UUID rateGroupId,
            String provider,
            String accountNumber,
            String serialNumber
    ) {}

    public record CreateSubmeterRequest(
            @NotNull UUID propertyId,
            @NotNull MeterType utilityType,
            @NotNull MeterUnit unit,
            BigDecimal multiplier,
            Long dialMax,
            String title,
            String serialNumber
    ) {}

    public record CreateRateGroupRequest(@NotNull UUID propertyId, String name, String notes) {}

    // Exactly one of mainId, submeterId. Applies from effectiveFrom on; earlier reads keep their usage.
    // unit, multiplier or dialMax left out stay as they were on that day.
    public record AddSettingRequest(
            UUID mainId,
            UUID submeterId,
            @NotNull LocalDate effectiveFrom,
            MeterUnit unit,
            BigDecimal multiplier,
            Long dialMax,
            String notes
    ) {}

    public record LinkToMainRequest(@NotNull UUID mainId, @NotNull LocalDate effectiveFrom) {}

    public record LinkToLotRequest(@NotNull UUID lotId, @NotNull LocalDate effectiveFrom) {}

    private final MeterService meterService;

    public MeterController(MeterService meterService) {
        this.meterService = meterService;
    }

    // ── The tree ──

    @PreAuthorize("hasAuthority('meters:view')")
    @GetMapping
    public ResponseEntity<MeterTree> tree(@RequestParam UUID propertyId, Authentication auth) {
        if (!inScope(Optional.of(propertyId), auth)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(meterService.tree(propertyId));
    }

    // The park's deleted mains, submeters, rate groups and links
    @PreAuthorize("hasAuthority('meters:delete')")
    @GetMapping("/deleted")
    public ResponseEntity<MeterTree> deleted(@RequestParam UUID propertyId, Authentication auth) {
        if (!inScope(Optional.of(propertyId), auth)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(meterService.deleted(propertyId));
    }

    // ── Mains ──

    @PreAuthorize("hasAuthority('meters:create')")
    @PostMapping("/mains")
    public ResponseEntity<MeterMain> createMain(@Valid @RequestBody CreateMainRequest r, Authentication auth) {
        if (!inScope(Optional.of(r.propertyId()), auth)) return ResponseEntity.notFound().build();
        return ResponseEntity.status(HttpStatus.CREATED).body(meterService.createMain(
                r.propertyId(), r.utilityType(), r.unit(), r.title(), r.multiplier(), r.dialMax(), r.rateGroupId(),
                r.provider(), r.accountNumber(), r.serialNumber()));
    }

    @PreAuthorize("hasAuthority('meters:edit')")
    @PatchMapping("/mains/{uuid}")
    public ResponseEntity<MeterMain> patchMain(@PathVariable UUID uuid, @RequestBody Map<String, Object> body, Authentication auth) {
        if (!inScope(meterService.propertyOfMain(uuid), auth)) return ResponseEntity.notFound().build();
        return found(meterService.patchMain(uuid, columns(body, MAIN_FIELDS)));
    }

    // 409 while submeters hang off it or a bill names it
    @PreAuthorize("hasAuthority('meters:delete')")
    @DeleteMapping("/mains/{uuid}")
    public ResponseEntity<Void> deleteMain(@PathVariable UUID uuid, Authentication auth) {
        if (!inScope(meterService.propertyOfMain(uuid), auth)) return ResponseEntity.notFound().build();
        return gone(meterService.deleteMain(uuid));
    }

    @PreAuthorize("hasAuthority('meters:delete')")
    @PostMapping("/mains/{uuid}/restore")
    public ResponseEntity<MeterMain> restoreMain(@PathVariable UUID uuid, Authentication auth) {
        if (!inScope(meterService.propertyOfMain(uuid), auth)) return ResponseEntity.notFound().build();
        return found(meterService.restoreMain(uuid));
    }

    // ── Submeters ──

    @PreAuthorize("hasAuthority('meters:create')")
    @PostMapping("/submeters")
    public ResponseEntity<Submeter> createSubmeter(@Valid @RequestBody CreateSubmeterRequest r, Authentication auth) {
        if (!inScope(Optional.of(r.propertyId()), auth)) return ResponseEntity.notFound().build();
        return ResponseEntity.status(HttpStatus.CREATED).body(meterService.createSubmeter(
                r.propertyId(), r.utilityType(), r.unit(), r.multiplier(), r.dialMax(), r.title(), r.serialNumber()));
    }

    @PreAuthorize("hasAuthority('meters:edit')")
    @PatchMapping("/submeters/{uuid}")
    public ResponseEntity<Submeter> patchSubmeter(@PathVariable UUID uuid, @RequestBody Map<String, Object> body, Authentication auth) {
        if (!inScope(meterService.propertyOfSubmeter(uuid), auth)) return ResponseEntity.notFound().build();
        return found(meterService.patchSubmeter(uuid, columns(body, SUBMETER_FIELDS)));
    }

    // 409 while it is linked to a main or a lot
    @PreAuthorize("hasAuthority('meters:delete')")
    @DeleteMapping("/submeters/{uuid}")
    public ResponseEntity<Void> deleteSubmeter(@PathVariable UUID uuid, Authentication auth) {
        if (!inScope(meterService.propertyOfSubmeter(uuid), auth)) return ResponseEntity.notFound().build();
        return gone(meterService.deleteSubmeter(uuid));
    }

    @PreAuthorize("hasAuthority('meters:delete')")
    @PostMapping("/submeters/{uuid}/restore")
    public ResponseEntity<Submeter> restoreSubmeter(@PathVariable UUID uuid, Authentication auth) {
        if (!inScope(meterService.propertyOfSubmeter(uuid), auth)) return ResponseEntity.notFound().build();
        return found(meterService.restoreSubmeter(uuid));
    }

    // Hangs the submeter off a main from a day on; moves it when it was under another main
    @PreAuthorize("hasAuthority('meters:edit')")
    @PostMapping("/submeters/{uuid}/main")
    public ResponseEntity<SubmeterMainLink> linkToMain(@PathVariable UUID uuid, @Valid @RequestBody LinkToMainRequest r,
                                                       Authentication auth) {
        if (!inScope(meterService.propertyOfSubmeter(uuid), auth)) return ResponseEntity.notFound().build();
        return ResponseEntity.status(HttpStatus.CREATED).body(meterService.linkToMain(uuid, r.mainId(), r.effectiveFrom()));
    }

    // The submeter feeds a lot from a day on. Two lots may share one submeter.
    @PreAuthorize("hasAuthority('meters:edit')")
    @PostMapping("/submeters/{uuid}/lots")
    public ResponseEntity<SubmeterLotLink> linkToLot(@PathVariable UUID uuid, @Valid @RequestBody LinkToLotRequest r,
                                                     Authentication auth) {
        if (!inScope(meterService.propertyOfSubmeter(uuid), auth)) return ResponseEntity.notFound().build();
        return ResponseEntity.status(HttpStatus.CREATED).body(meterService.linkToLot(uuid, r.lotId(), r.effectiveFrom()));
    }

    // ── Links ──

    // effectiveTo is the first day the link no longer holds; null reopens it
    @PreAuthorize("hasAuthority('meters:edit')")
    @PatchMapping("/main-links/{uuid}")
    public ResponseEntity<SubmeterMainLink> patchMainLink(@PathVariable UUID uuid, @RequestBody Map<String, Object> body,
                                                          Authentication auth) {
        if (!inScope(meterService.propertyOfMainLink(uuid), auth)) return ResponseEntity.notFound().build();
        return found(meterService.patchMainLink(uuid, columns(body, LINK_FIELDS)));
    }

    @PreAuthorize("hasAuthority('meters:edit')")
    @PatchMapping("/lot-links/{uuid}")
    public ResponseEntity<SubmeterLotLink> patchLotLink(@PathVariable UUID uuid, @RequestBody Map<String, Object> body,
                                                        Authentication auth) {
        if (!inScope(meterService.propertyOfLotLink(uuid), auth)) return ResponseEntity.notFound().build();
        return found(meterService.patchLotLink(uuid, columns(body, LINK_FIELDS)));
    }

    // For a link made by mistake. A real change ends the link instead (effectiveTo).
    @PreAuthorize("hasAuthority('meters:edit')")
    @DeleteMapping("/main-links/{uuid}")
    public ResponseEntity<Void> deleteMainLink(@PathVariable UUID uuid, Authentication auth) {
        if (!inScope(meterService.propertyOfMainLink(uuid), auth)) return ResponseEntity.notFound().build();
        return gone(meterService.deleteMainLink(uuid));
    }

    @PreAuthorize("hasAuthority('meters:edit')")
    @DeleteMapping("/lot-links/{uuid}")
    public ResponseEntity<Void> deleteLotLink(@PathVariable UUID uuid, Authentication auth) {
        if (!inScope(meterService.propertyOfLotLink(uuid), auth)) return ResponseEntity.notFound().build();
        return gone(meterService.deleteLotLink(uuid));
    }

    // 409 when a live link now overlaps it
    @PreAuthorize("hasAuthority('meters:edit')")
    @PostMapping("/main-links/{uuid}/restore")
    public ResponseEntity<SubmeterMainLink> restoreMainLink(@PathVariable UUID uuid, Authentication auth) {
        if (!inScope(meterService.propertyOfMainLink(uuid), auth)) return ResponseEntity.notFound().build();
        return found(meterService.restoreMainLink(uuid));
    }

    @PreAuthorize("hasAuthority('meters:edit')")
    @PostMapping("/lot-links/{uuid}/restore")
    public ResponseEntity<SubmeterLotLink> restoreLotLink(@PathVariable UUID uuid, Authentication auth) {
        if (!inScope(meterService.propertyOfLotLink(uuid), auth)) return ResponseEntity.notFound().build();
        return found(meterService.restoreLotLink(uuid));
    }

    // ── Settings: how to read a meter's dial, from a day on ──

    // 409 when a bill already names a read of the main on or after that day
    @PreAuthorize("hasAuthority('meters:edit')")
    @PostMapping("/settings")
    public ResponseEntity<MeterSetting> addSetting(@Valid @RequestBody AddSettingRequest r, Authentication auth) {
        if ((r.mainId() == null) == (r.submeterId() == null)) throw InvalidRequest.of("meter_setting.one_meter");
        Optional<UUID> property = r.mainId() != null ? meterService.propertyOfMain(r.mainId())
                : meterService.propertyOfSubmeter(r.submeterId());
        if (!inScope(property, auth)) return ResponseEntity.notFound().build();
        return ResponseEntity.status(HttpStatus.CREATED).body(meterService.addSetting(
                r.mainId(), r.submeterId(), r.effectiveFrom(), r.unit(), r.multiplier(), r.dialMax(), r.notes()));
    }

    // 409 when a bill names a read in the days the setting covers
    @PreAuthorize("hasAuthority('meters:edit')")
    @PatchMapping("/settings/{uuid}")
    public ResponseEntity<MeterSetting> patchSetting(@PathVariable UUID uuid, @RequestBody Map<String, Object> body,
                                                     Authentication auth) {
        if (!inScope(meterService.propertyOfSetting(uuid), auth)) return ResponseEntity.notFound().build();
        return found(meterService.patchSetting(uuid, columns(body, SETTING_FIELDS)));
    }

    // A meter's first setting stays; 409 when a bill relies on this one
    @PreAuthorize("hasAuthority('meters:edit')")
    @DeleteMapping("/settings/{uuid}")
    public ResponseEntity<Void> deleteSetting(@PathVariable UUID uuid, Authentication auth) {
        if (!inScope(meterService.propertyOfSetting(uuid), auth)) return ResponseEntity.notFound().build();
        return gone(meterService.deleteSetting(uuid));
    }

    @PreAuthorize("hasAuthority('meters:edit')")
    @PostMapping("/settings/{uuid}/restore")
    public ResponseEntity<MeterSetting> restoreSetting(@PathVariable UUID uuid, Authentication auth) {
        if (!inScope(meterService.propertyOfSetting(uuid), auth)) return ResponseEntity.notFound().build();
        return found(meterService.restoreSetting(uuid));
    }

    // ── Rate groups ──

    @PreAuthorize("hasAuthority('meters:create')")
    @PostMapping("/rate-groups")
    public ResponseEntity<RateGroup> createRateGroup(@Valid @RequestBody CreateRateGroupRequest r, Authentication auth) {
        if (!inScope(Optional.of(r.propertyId()), auth)) return ResponseEntity.notFound().build();
        return ResponseEntity.status(HttpStatus.CREATED).body(meterService.createRateGroup(r.propertyId(), r.name(), r.notes()));
    }

    @PreAuthorize("hasAuthority('meters:edit')")
    @PatchMapping("/rate-groups/{uuid}")
    public ResponseEntity<RateGroup> patchRateGroup(@PathVariable UUID uuid, @RequestBody Map<String, Object> body, Authentication auth) {
        if (!inScope(meterService.propertyOfRateGroup(uuid), auth)) return ResponseEntity.notFound().build();
        return found(meterService.patchRateGroup(uuid, columns(body, RATE_GROUP_FIELDS)));
    }

    // 409 while mains are in it
    @PreAuthorize("hasAuthority('meters:delete')")
    @DeleteMapping("/rate-groups/{uuid}")
    public ResponseEntity<Void> deleteRateGroup(@PathVariable UUID uuid, Authentication auth) {
        if (!inScope(meterService.propertyOfRateGroup(uuid), auth)) return ResponseEntity.notFound().build();
        return gone(meterService.deleteRateGroup(uuid));
    }

    @PreAuthorize("hasAuthority('meters:delete')")
    @PostMapping("/rate-groups/{uuid}/restore")
    public ResponseEntity<RateGroup> restoreRateGroup(@PathVariable UUID uuid, Authentication auth) {
        if (!inScope(meterService.propertyOfRateGroup(uuid), auth)) return ResponseEntity.notFound().build();
        return found(meterService.restoreRateGroup(uuid));
    }

    // ── Helpers ──

    static boolean inScope(Optional<UUID> propertyId, Authentication auth) {
        return propertyId.isPresent() && AgentPrincipal.scopeOf(auth).allows(propertyId.get());
    }

    // The body's fields this endpoint takes, renamed to columns. Anything else is ignored.
    static Map<String, Object> columns(Map<String, Object> body, Map<String, String> fields) {
        Map<String, Object> out = new HashMap<>();
        fields.forEach((field, column) -> {
            if (body.containsKey(field)) out.put(column, body.get(field));
        });
        return out;
    }

    static <T> ResponseEntity<T> found(Optional<T> value) {
        return value.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    static ResponseEntity<Void> gone(boolean done) {
        return done ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    // No @ExceptionHandler here. A local one would win over ApiExceptionHandler and
    // answer with the diagnostic code instead of the sentence in the worker's language.
}
