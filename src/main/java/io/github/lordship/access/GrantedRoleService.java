package io.github.lordship.access;

import io.github.lordship.access.internal.grantedrole.GrantedRoleRepository;
import io.github.lordship.access.internal.grantedrole.GrantedRoleRow;
import io.github.lordship.access.internal.role.RoleRepository;
import io.github.lordship.access.internal.role.RoleRow;
import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.identity.AgentAuthorizationCache;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;


// assigns roles to agents

@Service
public class GrantedRoleService {

    private final GrantedRoleRepository grantedRoleRepository;
    private final RoleRepository roleRepository;
    private final AuditService auditService;
    private final AgentAuthorizationCache authorizationCache;

    public GrantedRoleService(GrantedRoleRepository grantedRoleRepository,
                              RoleRepository roleRepository,
                              AuditService auditService,
                              AgentAuthorizationCache authorizationCache) {
        this.grantedRoleRepository = grantedRoleRepository;
        this.roleRepository = roleRepository;
        this.auditService = auditService;
        this.authorizationCache = authorizationCache;
    }

    // granting a role the agent already actively holds is a no-op:
    // the existing grant comes back and nothing is audited, since nothing changed.
    // without this the partial unique index uq_granted_role_active turns a
    // double-click into a 500
    @Transactional
    public GrantedRole grantRole(UUID agentId, UUID roleId, UUID grantedBy) {
        Optional<GrantedRoleRow> existing = grantedRoleRepository.findActiveGrant(agentId, roleId);
        if (existing.isPresent()) {
            return existing.get().toGrantedRole();
        }

        GrantedRoleRow row = grantedRoleRepository.save(
                new GrantedRoleRow(agentId, roleId, grantedBy)
        );
        authorizationCache.invalidate(agentId);
        auditService.recordInsert("granted_role", row.uuid(), AuditMapper.toMap(row));
        return row.toGrantedRole();
    }

    @Transactional
    public GrantedRole grantRoleByName(UUID agentId, String roleName, UUID grantedBy) {
        RoleRow row = roleRepository.findByName(roleName)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleName));
        return grantRole(agentId, row.uuid(), grantedBy);
    }

    @Transactional
    public GrantedRole systemGrantRole(UUID agentId, String roleName) {
        RoleRow roleId = roleRepository.findByName(roleName)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleName));;

        Optional<GrantedRoleRow> existing = grantedRoleRepository.findActiveGrant(agentId, roleId.uuid());
        if (existing.isPresent()) {
            return existing.get().toGrantedRole();
        }

        GrantedRoleRow row = grantedRoleRepository.save(
                new GrantedRoleRow(agentId, roleId.uuid(), UUID.fromString("00000000-0000-7000-8000-000000000002"))
        );
        authorizationCache.invalidate(agentId);
        return row.toGrantedRole();
    }


    @Transactional
    public boolean revokeGrant(UUID grantId, UUID revokedBy) {
        Optional<GrantedRoleRow> before = grantedRoleRepository.findById(grantId);
        if (before.isEmpty()) return false;

        if (!grantedRoleRepository.revoke(grantId, revokedBy)) return false;

        authorizationCache.invalidate(before.get().agentId());
        auditService.recordDelete("granted_role", grantId, AuditMapper.toMap(before.get()));
        return true;
    }

    @Transactional
    public boolean revokeRole(UUID agentId, UUID roleId, UUID revokedBy) {
        return grantedRoleRepository.findActiveGrant(agentId, roleId)
                .map(row -> revokeGrant(row.uuid(), revokedBy))
                .orElse(false);
    }

    @Transactional
    public boolean revokeRoleByName(UUID agentId, String roleName, UUID revokedBy) {
        RoleRow row = roleRepository.findByName(roleName)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleName));
        return revokeRole(agentId, row.uuid(), revokedBy);
    }

    // Every holder loses the role, one audited revocation each. Called by
    // RoleService.deleteRole -- a deleted role that leaves its grants standing is
    // a role whose permissions are still being handed out.
    @Transactional
    public int revokeAllForRole(UUID roleId, UUID revokedBy) {
        int revoked = 0;
        for (GrantedRoleRow row : grantedRoleRepository.findByRoleId(roleId)) {
            if (revokeGrant(row.uuid(), revokedBy)) {
                revoked++;
            }
        }
        return revoked;
    }

    // returns revoked grants too, so the caller can tell "never granted" from "revoked"
    public Optional<GrantedRole> findById(UUID grantId) {
        return grantedRoleRepository.findById(grantId).map(GrantedRoleRow::toGrantedRole);
    }

    public List<GrantedRole> findRolesForAgent(UUID agentId) {
        return grantedRoleRepository.findByAgentId(agentId)
                .stream()
                .map(GrantedRoleRow::toGrantedRole)
                .toList();
    }

    // names of the roles the agent holds right now, for the login and /self responses
    public List<String> findRoleNamesForAgent(UUID agentId) {
        return grantedRoleRepository.findActiveRoleNamesForAgent(agentId);
    }

    // the reverse lookup: everyone currently holding a given role
    public List<GrantedRole> findAgentsWithRole(UUID roleId) {
        return grantedRoleRepository.findByRoleId(roleId)
                .stream()
                .map(GrantedRoleRow::toGrantedRole)
                .toList();
    }
}