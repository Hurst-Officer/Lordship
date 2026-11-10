package io.github.lordship.meters.internal;

import io.github.lordship.meters.MeterRead;
import io.github.lordship.meters.MeterReadKind;
import io.github.lordship.meters.MeterReadService;
import io.github.lordship.meters.MeterReadings;
import io.github.lordship.meters.ReadInterval;
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

import static io.github.lordship.meters.internal.MeterController.columns;
import static io.github.lordship.meters.internal.MeterController.found;
import static io.github.lordship.meters.internal.MeterController.gone;
import static io.github.lordship.meters.internal.MeterController.inScope;

// Reads of mains and submeters. Each answer carries the usage since the read
// before and any warnings (ran backwards, didn't move, unusually high...).
// Entering and correcting reads is its own permission, so a property manager
// can be given it without being able to change the meter tree.
@RestController
@RequestMapping("/api/meter-reads")
public class MeterReadController {

    private static final Map<String, String> FIELDS = Map.of(
            "readOn", "read_on", "reading", "reading", "kind", "kind",
            "isEstimated", "is_estimated", "notes", "notes");

    // Exactly one of mainId, submeterId
    public record RecordReadRequest(
            UUID mainId,
            UUID submeterId,
            @NotNull LocalDate readOn,
            @NotNull Long reading,
            MeterReadKind kind,
            Boolean isEstimated,
            String notes
    ) {}

    private final MeterReadService readService;

    public MeterReadController(MeterReadService readService) {
        this.readService = readService;
    }

    // Every meter's reads in a park between two days (inclusive), for the read grid
    @PreAuthorize("hasAuthority('meters:view')")
    @GetMapping("/grid")
    public ResponseEntity<List<MeterReadings>> grid(
            @RequestParam UUID propertyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication auth) {
        if (!inScope(Optional.of(propertyId), auth)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(readService.grid(propertyId, from, to));
    }

    // One meter's whole history, oldest first. Pass mainId or submeterId.
    @PreAuthorize("hasAuthority('meters:view')")
    @GetMapping
    public ResponseEntity<List<ReadInterval>> history(@RequestParam(required = false) UUID mainId,
                                                      @RequestParam(required = false) UUID submeterId,
                                                      Authentication auth) {
        if (!inScope(readService.propertyOfMeter(mainId, submeterId), auth)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(readService.history(mainId, submeterId));
    }

    // One meter's deleted reads, the most recently deleted first
    @PreAuthorize("hasAuthority('meters:enter_reads')")
    @GetMapping("/deleted")
    public ResponseEntity<List<MeterRead>> deleted(@RequestParam(required = false) UUID mainId,
                                                   @RequestParam(required = false) UUID submeterId,
                                                   Authentication auth) {
        if (!inScope(readService.propertyOfMeter(mainId, submeterId), auth)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(readService.deleted(mainId, submeterId));
    }

    // 409 when the meter already has a read that day
    @PreAuthorize("hasAuthority('meters:enter_reads')")
    @PostMapping
    public ResponseEntity<ReadInterval> record(@Valid @RequestBody RecordReadRequest r, Authentication auth) {
        if (!inScope(readService.propertyOfMeter(r.mainId(), r.submeterId()), auth)) return ResponseEntity.notFound().build();
        return ResponseEntity.status(HttpStatus.CREATED).body(readService.record(
                r.mainId(), r.submeterId(), r.readOn(), r.reading(), r.kind(),
                Boolean.TRUE.equals(r.isEstimated()), r.notes()));
    }

    @PreAuthorize("hasAuthority('meters:enter_reads')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<ReadInterval> patch(@PathVariable UUID uuid, @RequestBody Map<String, Object> body, Authentication auth) {
        if (!inScope(readService.propertyOfRead(uuid), auth)) return ResponseEntity.notFound().build();
        return found(readService.patch(uuid, columns(body, FIELDS)));
    }

    // 409 while a bill names it as its old or new number
    @PreAuthorize("hasAuthority('meters:enter_reads')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> delete(@PathVariable UUID uuid, Authentication auth) {
        if (!inScope(readService.propertyOfRead(uuid), auth)) return ResponseEntity.notFound().build();
        return gone(readService.delete(uuid));
    }

    // 409 when another read has since been entered for that meter and day
    @PreAuthorize("hasAuthority('meters:enter_reads')")
    @PostMapping("/{uuid}/restore")
    public ResponseEntity<ReadInterval> restore(@PathVariable UUID uuid, Authentication auth) {
        if (!inScope(readService.propertyOfRead(uuid), auth)) return ResponseEntity.notFound().build();
        return found(readService.restore(uuid));
    }
}
