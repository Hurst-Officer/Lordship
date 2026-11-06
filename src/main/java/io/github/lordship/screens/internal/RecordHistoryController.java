package io.github.lordship.screens.internal;

import io.github.lordship.audit.AuditService;
import io.github.lordship.audit.History;
import io.github.lordship.homes.HomeService;
import io.github.lordship.identity.AgentPrincipal;
import io.github.lordship.lots.LotService;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.PropertyScope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One record's history, for the bars under each row on Glade's screens.
 *
 * <p>It lives here, not in the audit module, because it has to ask the lots and homes modules
 * whether a record is in the agent's scope. Those modules already use audit, so audit cannot use them.
 */
@RestController
@RequestMapping("/api/auditlogs/record")
public class RecordHistoryController {

    // A row's history is listed this many changes at a time
    private static final int HISTORY_PAGE_SIZE = 10;

    // The history shows a record's values, so it needs the permission to view that record too
    private static final Map<String, String> VIEW_PERMISSION = Map.of(
            "property", "properties:view",
            "lot", "lots:view",
            "mobile_home", "homes:view");

    private final AuditService auditService;
    private final LotService lotService;
    private final HomeService homeService;

    public RecordHistoryController(AuditService auditService, LotService lotService, HomeService homeService) {
        this.auditService = auditService;
        this.lotService = lotService;
        this.homeService = homeService;
    }

    /**
     * One record's changes to some of its fields, newest first, ten at a time.
     * table is property, lot or mobile_home. Anything else is a 400 (audit.table_unknown).
     * Repeat field once for each field to include: ?field=propertyZoning&field=fieldLinks.property_zoning
     * (repeated, not comma-separated, because a custom field's name can hold a comma). None is a 400.
     * day (like 2026-10-03) keeps one day in the time zone named by zone (like America/Los_Angeles).
     * 403 without the permission to view that kind of record.
     * 404 when the record is in a property outside the agent's scope.
     */
    @PreAuthorize("hasAuthority('audit:view')")
    @GetMapping("/{table}/{id}")
    public ResponseEntity<History.EntryPage> recordHistory(
            @PathVariable String table,
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam(required = false) String zone,
            @RequestParam(defaultValue = "0") int page,
            HttpServletRequest request,
            Authentication authentication) {

        if (!AuditService.SCREEN_TABLES.contains(table)) {
            throw InvalidRequest.onField("table", "audit.table_unknown", table);
        }
        String[] fields = request.getParameterValues("field");
        if (fields == null || fields.length == 0) {
            throw InvalidRequest.onField("field", "audit.field_missing");
        }
        if (!can(authentication, VIEW_PERMISSION.get(table))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!inScope(table, id, AgentPrincipal.scopeOf(authentication))) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(auditService.fieldHistory(
                new History.Ref(table, id), List.of(fields), day, AuditService.zoneOf(zone), page, HISTORY_PAGE_SIZE));
    }

    // Whether the record is in a property this scope may see
    private boolean inScope(String table, UUID id, PropertyScope scope) {
        return switch (table) {
            case "property" -> scope.allows(id);
            case "lot" -> lotService.inScope(id, scope);
            case "mobile_home" -> homeService.inScope(id, scope);
            default -> false;
        };
    }

    private static boolean can(Authentication authentication, String permission) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> permission.equals(authority.getAuthority()));
    }
}
