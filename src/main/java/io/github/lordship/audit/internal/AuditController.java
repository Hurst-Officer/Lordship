package io.github.lordship.audit.internal;


import io.github.lordship.audit.AuditLog;
import io.github.lordship.audit.AuditService;
import io.github.lordship.audit.History;
import io.github.lordship.shared.PageResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/auditlogs")
public class AuditController {

    private final AuditService auditService;

    // A row's history is listed this many changes at a time
    private static final int HISTORY_PAGE_SIZE = 10;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * One record's changes to some of its fields, newest first, ten at a time. Glade's history bars use it.
     * table is property, lot or mobile_home. Anything else is a 400.
     * Repeat field once for each field to include: ?field=propertyZoning&field=fieldLinks.property_zoning
     * (repeated, not comma-separated, because a custom field's name can hold a comma).
     * day (like 2026-10-03) keeps one day in the time zone named by zone (like America/Los_Angeles).
     */
    @PreAuthorize("hasAuthority('audit:view')")
    @GetMapping("/record/{table}/{id}")
    public ResponseEntity<History.EntryPage> recordHistory(
            @PathVariable String table,
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam(required = false) String zone,
            @RequestParam(defaultValue = "0") int page,
            HttpServletRequest request) {

        String[] fields = request.getParameterValues("field");
        if (!AuditService.SCREEN_TABLES.contains(table) || fields == null || fields.length == 0) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(auditService.fieldHistory(
                new History.Ref(table, id), List.of(fields), day, AuditService.zoneOf(zone), page, HISTORY_PAGE_SIZE));
    }

    @PreAuthorize("hasAuthority('audit:view')")
    @GetMapping("/byagent/{uuid}")
    public ResponseEntity<PageResult<AuditLogResponse>> findAgentAuditLogs(
            @PathVariable UUID uuid,
            @Valid AgentAuditLogPageRequest request,
            Authentication authentication) {

        PageResult<AuditLog> result = auditService.findAgentAuditLogs(
                uuid,
                request.page(),
                request.pageSize(),
                request.sortBy(),
                request.ascending()
        );

        PageResult<AuditLogResponse> mapped = result.map(log ->
                AuditLogResponse.from(log, authentication)
        );

        return ResponseEntity.ok(mapped);
    }
}
