package io.github.lordship.audit.internal;


import io.github.lordship.audit.AuditLog;
import io.github.lordship.audit.AuditService;
import io.github.lordship.identity.AgentPrincipal;
import io.github.lordship.shared.PageResult;
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
