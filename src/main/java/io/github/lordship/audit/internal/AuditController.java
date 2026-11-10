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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

    // The values in "My activity" follow the history bars (screens RecordHistoryController): a kind of record's
    // values need the permission to view that kind. Without it the line still says which fields changed.
    private static final Map<String, Set<String>> VALUE_PERMISSIONS = Map.ofEntries(
            Map.entry("property", Set.of("properties:view")),
            Map.entry("lot", Set.of("lots:view")),
            Map.entry("mobile_home", Set.of("homes:view")),
            Map.entry("agent_role", Set.of("agent_roles:view")),
            Map.entry("agent", Set.of("agent_roles:view", "agents:view")),
            Map.entry("person", Set.of("persons:view")),
            Map.entry("document_template", Set.of("document_template:view")),
            Map.entry("document_section", Set.of("document_template:view")),
            Map.entry("template_clause", Set.of("document_template:view")),
            Map.entry("document_style", Set.of("document_template:view")),
            Map.entry("property_document_assignment", Set.of("property_document:view")),
            Map.entry("property_document_customization", Set.of("property_document:view")));

    /**
     * The signed-in agent's own saves, for "My activity" on their own page in Glade: one per save (a bulk change
     * of 30 lots is one), with what kind of record, which one, and what changed: for the tables the screens show,
     * each field's value before and after (the first record's, with sameForAll when every record got the same).
     * The values need the permission to view that kind of record, except on the agent's own person and agent
     * records; without it a field comes back withheld. What a role can do also needs role_permissions:view.
     * The social security number and the password are always withheld.
     * sort: when (default), kind or record; ascending defaults to false (newest first). limit: 1 to 500.
     * A save under a property the agent is no longer assigned to comes back with hidden true and nothing that
     * names the record. 404 for a caller who is not an agent.
     * Needs audit:view_own alone: audit:view (everyone's history bars) does not include it, so taking
     * "See own activity log" away hides "My activity" even from someone who may see activity logs.
     */
    @PreAuthorize("hasAuthority('audit:view_own')")
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
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        History.SavePage page = auditService.ownActivity(
                principal.agentUuid(), sort, ascending, offset, Math.clamp(limit, 1, MAX_OWN_SAVES));
        return ResponseEntity.ok(new History.SavePage(
                page.saves().stream().map(save -> shown(save, principal, authorities)).toList(),
                page.more()));
    }

    // A save as this agent may see it now
    private static History.Save shown(History.Save save, AgentPrincipal principal, Set<String> authorities) {
        PropertyScope scope = principal.propertyScope();
        if (save.propertyId() != null && !scope.allows(save.propertyId())) {
            return save.withoutRecord();
        }
        boolean own = save.records() == 1
                && (("person".equals(save.table()) && String.valueOf(principal.personUuid()).equals(save.recordId()))
                    || ("agent".equals(save.table()) && String.valueOf(principal.agentUuid()).equals(save.recordId())));
        if (!own && VALUE_PERMISSIONS.getOrDefault(save.table(), Set.of()).stream().noneMatch(authorities::contains)) {
            return save.withValuesHidden(field -> true);
        }
        // what a role can do is only shown to someone who may see it, its history included
        if ("agent_role".equals(save.table()) && !authorities.contains("role_permissions:view")) {
            return save.withValuesHidden(field -> field.startsWith("permissions."));
        }
        return save;
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
