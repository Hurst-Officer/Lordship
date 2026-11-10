package io.github.lordship.access;

import io.github.lordship.access.internal.AccessKeys;
import io.github.lordship.access.internal.RoleHistory;
import io.github.lordship.access.internal.agents.AgentRepository;
import io.github.lordship.access.internal.deniedpermission.DeniedPermissionRepository;
import io.github.lordship.access.internal.deniedpermission.DeniedPermissionRow;
import io.github.lordship.access.internal.permissions.PermissionRepository;
import io.github.lordship.access.internal.permissions.PermissionRow;
import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.identity.AgentAuthorizationCache;
import io.github.lordship.shared.InvalidRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Takes one permission away from one agent, even though a role of the agent's gives that permission,
 * and gives the permission back. The agent keeps the role and every other permission of the role.
 *
 * <p>Only a permission that a live role of the agent's gives can be denied. A denial outlives the role:
 * an agent who loses the role and later gets the role again still has the permission denied.
 *
 * <p>Every change takes effect on the agent's next request: the agent's cached permissions are dropped.
 * Every change is also an entry in the person's history ("permissions.lots:edit", RoleHistory.personPermission),
 * for the bar under the permission's switch on Glade's person page.
 */
@Service
public class DeniedPermissionService {

    private final DeniedPermissionRepository deniedPermissionRepository;
    private final PermissionRepository permissionRepository;
    private final AgentRepository agentRepository;
    private final AuditService auditService;
    private final AgentAuthorizationCache authorizationCache;
    private final AccessKeys accessKeys;
    private final RoleHistory roleHistory;

    public DeniedPermissionService(DeniedPermissionRepository deniedPermissionRepository,
                                   PermissionRepository permissionRepository,
                                   AgentRepository agentRepository,
                                   AuditService auditService,
                                   AgentAuthorizationCache authorizationCache,
                                   AccessKeys accessKeys,
                                   RoleHistory roleHistory) {
        this.deniedPermissionRepository = deniedPermissionRepository;
        this.permissionRepository = permissionRepository;
        this.agentRepository = agentRepository;
        this.auditService = auditService;
        this.authorizationCache = authorizationCache;
        this.accessKeys = accessKeys;
        this.roleHistory = roleHistory;
    }

    /**
     * Takes the permission away from the agent. Denying a permission that is already denied is a no-op:
     * the existing denial comes back and nothing is audited.
     * Empty for an unknown or removed agent.
     * 400 permission.unknown. 400 permission.not_held when no live role of the agent's gives the permission.
     * 409 access.last_key when the denial would leave nobody who can manage roles (see AccessKeys).
     */
    @Transactional
    public Optional<DeniedPermission> deny(UUID agentId, String permissionName, UUID deniedBy) {
        if (agentRepository.findById(agentId).isEmpty()) {
            return Optional.empty();
        }
        PermissionRow permission = permissionNamed(permissionName);

        // the existing-denial check sits inside keep(), behind AccessKeys' lock, so a double-click
        // finds the first denial instead of tripping the unique index uq_denied_permission_active
        return Optional.of(accessKeys.keep(() -> {
            Optional<DeniedPermissionRow> existing = deniedPermissionRepository.findActive(agentId, permission.uuid());
            if (existing.isPresent()) {
                return existing.get().toDeniedPermission(permission.permissionName());
            }
            if (!deniedPermissionRepository.rolesGive(agentId, permission.uuid())) {
                throw InvalidRequest.onField("permissionName", "permission.not_held", permission.permissionName());
            }
            DeniedPermissionRow row = deniedPermissionRepository.save(
                    new DeniedPermissionRow(agentId, permission.uuid(), deniedBy));
            authorizationCache.invalidate(agentId);
            auditService.recordInsert("denied_permission", row.uuid(), AuditMapper.toMap(row));
            roleHistory.personPermission(agentId, permission.permissionName(), false);
            return row.toDeniedPermission(permission.permissionName());
        }));
    }

    /**
     * Gives a denied permission back to the agent. Whether the agent then has the permission
     * depends on the agent's roles, as always.
     * False when the agent has no denial in force for the permission. 400 permission.unknown.
     */
    @Transactional
    public boolean restore(UUID agentId, String permissionName, UUID removedBy) {
        PermissionRow permission = permissionNamed(permissionName);
        Optional<DeniedPermissionRow> denial = deniedPermissionRepository.findActive(agentId, permission.uuid());
        if (denial.isEmpty() || !deniedPermissionRepository.remove(denial.get().uuid(), removedBy)) {
            return false;
        }
        authorizationCache.invalidate(agentId);
        auditService.recordDelete("denied_permission", denial.get().uuid(), AuditMapper.toMap(denial.get()));
        roleHistory.personPermission(agentId, permission.permissionName(), true);
        return true;
    }

    // Every denial in force for the agent, oldest first. Empty for an unknown or removed agent.
    public Optional<List<DeniedPermission>> findForAgent(UUID agentId) {
        if (agentRepository.findById(agentId).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(deniedPermissionRepository.findActiveForAgent(agentId));
    }

    private PermissionRow permissionNamed(String permissionName) {
        return permissionRepository.findByName(permissionName)
                .orElseThrow(() -> InvalidRequest.onField("permissionName", "permission.unknown", permissionName));
    }
}
