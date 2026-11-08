package io.github.lordship.audit.internal;


import io.github.lordship.audit.AuditLog;
import io.github.lordship.audit.AuditService;
import io.github.lordship.audit.History;
import io.github.lordship.identity.AgentPrincipal;
import io.github.lordship.shared.PageResult;
import io.github.lordship.shared.PropertyScope;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/auditlogs")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    // One record's history (GET /api/auditlogs/record/...) is in the screens module: RecordHistoryController.
    // It checks the agent's property scope, which needs the lots and homes modules.

    // The most saves "My activity" asks for at once
    private static final int MAX_OWN_SAVES = 500;

    /**
     * The signed-in agent's own saves, for "My activity" on their own page in Glade: one per save (a bulk change
     * of 30 lots is one), with what kind of record, which one, and which fields an update changed. No values:
     * each record's own history bars show those, to whoever may see the record.
     * sort: when (default), kind or record; ascending defaults to false (newest first). limit: 1 to 500.
     * A save under a property the agent is no longer assigned to comes back with hidden true and nothing that
     * names the record. 404 for a caller who is not an agent.
     */
    @PreAuthorize("hasAnyAuthority('audit:view_own', 'audit:view')")
    @GetMapping("/mine")
    public ResponseEntity<History.SavePage> ownActivity(
            @RequestParam(defaultValue = "when") String sort,
            @RequestParam(defaultValue = "false") boolean ascending,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "25") int limit,
            Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof AgentPrincipal principal)) {
            return ResponseEntity.notFound().build();
        }
        PropertyScope scope = principal.propertyScope();
        History.SavePage page = auditService.ownActivity(
                principal.agentUuid(), sort, ascending, offset, Math.clamp(limit, 1, MAX_OWN_SAVES));
        return ResponseEntity.ok(new History.SavePage(
                page.saves().stream()
                        .map(save -> save.propertyId() != null && !scope.allows(save.propertyId()) ? save.withoutRecord() : save)
                        .toList(),
                page.more()));
    }

    // Everything one agent changed, on every property. So it needs every property in scope:
    // an agent limited to their assigned properties gets 403.
    @PreAuthorize("hasAuthority('audit:view')")
    @GetMapping("/byagent/{uuid}")
    public ResponseEntity<PageResult<AuditLogResponse>> findAgentAuditLogs(
            @PathVariable UUID uuid,
            @Valid AgentAuditLogPageRequest request,
            Authentication authentication) {

        if (!AgentPrincipal.scopeOf(authentication).isAll()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

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
