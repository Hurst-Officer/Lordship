package io.github.lordship.meters.internal;

import io.github.lordship.meters.*;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/meters")
public class MeterController {

    public final MeterService meterService;
    public MeterController(MeterService meterService) {
        this.meterService = meterService;
    }

    @PreAuthorize("hasAuthority('meters:create')")
    @PostMapping("/create")
    public ResponseEntity<MeterResponse> createMeter(@RequestBody @Valid MeterCreateRequest meterCreateRequest) {
        Meters meters = meterService.create(meterCreateRequest);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(MeterResponse.from(meters));
    }

    @PreAuthorize("hasAuthority('meters:view')")
    @GetMapping("/{uuid}")
    public ResponseEntity<MeterResponse> getById(@PathVariable UUID uuid) {
        return meterService.findMetersById(uuid)
                .map(m -> ResponseEntity.ok(MeterResponse.from(m)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('meters:view')")
    @GetMapping("/lot/{meterId}")
    public ResponseEntity<List<MeterResponse>> getMeterByLot(@PathVariable UUID meterId) {
        List<MeterResponse> responses = meterService.findActiveMetersByLot(meterId)
                .stream()
                .map(MeterResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @PreAuthorize("hasAuthority('meters:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<MeterResponse> patchMeter(
            @PathVariable UUID uuid,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> changes = new HashMap<>();

        if (request.containsKey("title")) {
            changes.put("title", request.get("title"));
        }

        if (request.containsKey("description")) {
            changes.put("description", request.get("description"));
        }

        if (request.containsKey("installedAt")) {
            changes.put("installed_at", request.get("installedAt"));
        }

        try {
            return meterService.patchMeter(uuid, changes)
                    .map(t -> ResponseEntity.ok(MeterResponse.from(t)))
                    .orElse(ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PreAuthorize("hasAuthority('meters:delete')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> deleteMeter(@PathVariable UUID uuid) {
        return meterService.softDelete(uuid)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PreAuthorize("hasAuthority('meters:edit')")
    @PostMapping("/{uuid}/reads")
    public ResponseEntity<MeterReadResponse> recordRead(
            @PathVariable UUID uuid,
            @RequestBody @Valid MeterReadCreateRequest request) {
        try {
            MeterRead read = meterService.recordRead(
                    uuid, request.meterAmount(), request.readAt(), request.isEstimated());
            return ResponseEntity.status(HttpStatus.CREATED).body(MeterReadResponse.from(read));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PreAuthorize("hasAuthority('meters:view')")
    @GetMapping("/{uuid}/usage")
    public ResponseEntity<UsageResponse> getUsageForPeriod(
            @PathVariable UUID uuid,
            @RequestParam OffsetDateTime start,
            @RequestParam OffsetDateTime end) {
        try {
            Usage usage = meterService.getUsageForPeriod(uuid, start, end);
            return ResponseEntity.ok(UsageResponse.from(usage));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().build(); // 422: reads exist but usage can't be computed
        }
    }

    @PreAuthorize("hasAuthority('meters:view')")
    @GetMapping("/{uuid}/usage/current")
    public ResponseEntity<UsageResponse> getUsageForCurrentPeriod(@PathVariable UUID uuid) {
        try {
            Usage usage = meterService.getUsageForCurrentPeriod(uuid);
            return ResponseEntity.ok(UsageResponse.from(usage));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().build();
        }
    }

    @PreAuthorize("hasAuthority('meters:edit')")
    @PostMapping("/relationships")
    public ResponseEntity<MeterRelationResponse> linkMeters(
            @RequestBody @Valid MeterRelationCreateRequest request) {
        try {
            MeterRelation relationship = meterService.linkMeters(
                    request.parentMeterId(), request.childMeterId(), request.hasUnmeteredRemainder(), request.effectiveFrom());
            return ResponseEntity.status(HttpStatus.CREATED).body(MeterRelationResponse.from(relationship));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PreAuthorize("hasAuthority('meters:edit')")
    @PatchMapping("/relationships/{childMeterId}/unlink")
    public ResponseEntity<MeterRelationResponse> unlinkMeter(
            @PathVariable UUID childMeterId,
            @RequestBody @Valid MeterRelationCloseRequest request) {
        try {
            MeterRelation relationship = meterService.unlinkMeter(childMeterId, request.effectiveTo());
            return ResponseEntity.ok(MeterRelationResponse.from(relationship));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PreAuthorize("hasAuthority('meters:view')")
    @GetMapping("/relationships/{childMeterId}/parent")
    public ResponseEntity<UUID> resolveParentMeter(
            @PathVariable UUID childMeterId,
            @RequestParam LocalDate asOf) {
        return meterService.resolveParentMeter(childMeterId, asOf)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
