package io.github.lordship.access;

import io.github.lordship.access.internal.AccessKeys;
import io.github.lordship.access.internal.RoleHistory;
import io.github.lordship.access.internal.grantedrole.GrantedRoleRepository;
import io.github.lordship.access.internal.grantedrole.GrantedRoleRow;
import io.github.lordship.access.internal.role.RoleRepository;
import io.github.lordship.access.internal.role.RoleRow;
import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.identity.AgentAuthorizationCache;
import io.github.lordship.shared.InvalidRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;


// assigns roles to agents

@Service
public class GrantedRoleService {

    private final GrantedRoleRepository grantedRoleRepository;
    private final RoleRepository roleRepository;
    private final AuditService auditService;
    private final AgentAuthorizationCache authorizationCache;
    private final AccessKeys accessKeys;
    private final RoleHistory roleHistory;

    public GrantedRoleService(GrantedRoleRepository grantedRoleRepository,
                              RoleRepository roleRepository,
                              AuditService auditService,
                              AgentAuthorizationCache authorizationCache,
                              AccessKeys accessKeys,
                              RoleHistory roleHistory) {
        this.grantedRoleRepository = grantedRoleRepository;
        this.roleRepository = roleRepository;
        this.auditService = auditService;
        this.authorizationCache = authorizationCache;
        this.accessKeys = accessKeys;
        this.roleHistory = roleHistory;
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
        GrantedRoleRow row = save(agentId, roleId, grantedBy);
        roleHistory.holder(roleId, agentId, true);
        return row.toGrantedRole();
    }

    /**
     * Gives a restored role back to the people who lost it when it was deleted (see agentsToRegrant).
     * People who already hold it again are skipped. Each person's history gets an entry; the role's
     * history gets none here, because the restore writes the people into its own entry.
     * Returns the people given it.
     */
    @Transactional
    public List<UUID> regrant(UUID roleId, List<UUID> agentIds, UUID grantedBy) {
        Map<UUID, Boolean> given = new LinkedHashMap<>();
        for (UUID agentId : agentIds) {
            if (grantedRoleRepository.findActiveGrant(agentId, roleId).isPresent()) continue;
            save(agentId, roleId, grantedBy);
            given.put(agentId, true);
        }
        roleHistory.holdersOnPeople(roleId, given);
        return List.copyOf(given.keySet());
    }

    // The grant itself: the row, the cache, and its own audit row
    private GrantedRoleRow save(UUID agentId, UUID roleId, UUID grantedBy) {
        GrantedRoleRow row = grantedRoleRepository.save(
                new GrantedRoleRow(agentId, roleId, grantedBy)
        );
        authorizationCache.invalidate(agentId);
        auditService.recordInsert("granted_role", row.uuid(), AuditMapper.toMap(row));
        return row;
    }

    @Transactional
    public GrantedRole grantRoleByName(UUID agentId, String roleName, UUID grantedBy) {
        RoleRow row = roleRepository.findByName(roleName)
                .orElseThrow(() -> InvalidRequest.onField("roleName", "role.unknown", roleName));
        return grantRole(agentId, row.uuid(), grantedBy);
    }

    // The same as grantRoleByName, for a client that knows the role by its id (Glade).
    // A role that is unknown or deleted is a 400 on roleId.
    @Transactional
    public GrantedRole grantRoleById(UUID agentId, UUID roleId, UUID grantedBy) {
        if (roleRepository.findById(roleId).isEmpty()) {
            throw InvalidRequest.onField("roleId", "role.unknown", roleId);
        }
        return grantRole(agentId, roleId, grantedBy);
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


    /**
     * Takes a role from a person. Refused (409 access.last_key) when it would leave nobody
     * who can manage roles (see AccessKeys).
     */
    @Transactional
    public boolean revokeGrant(UUID grantId, UUID revokedBy) {
        return accessKeys.keep(() -> revoke(grantId, revokedBy, true));
    }

    // roleSide false: the role is being deleted, so only the person's history says they lost it
    private boolean revoke(UUID grantId, UUID revokedBy, boolean roleSide) {
        Optional<GrantedRoleRow> before = grantedRoleRepository.findById(grantId);
        if (before.isEmpty()) return false;

        if (!grantedRoleRepository.revoke(grantId, revokedBy)) return false;

        authorizationCache.invalidate(before.get().agentId());
        auditService.recordDelete("granted_role", grantId, AuditMapper.toMap(before.get()));
        Map<UUID, Boolean> lost = Map.of(before.get().agentId(), false);
        if (roleSide) {
            roleHistory.holders(before.get().roleId(), lost);
        } else {
            roleHistory.holdersOnPeople(before.get().roleId(), lost);
        }
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
                .orElseThrow(() -> InvalidRequest.onField("roleName", "role.unknown", roleName));
        return revokeRole(agentId, row.uuid(), revokedBy);
    }

    // Every holder loses the role, one audited revocation each. Called by
    // RoleService.deleteRole -- a deleted role that leaves its grants standing is
    // a role whose permissions are still being handed out.
    @Transactional
    public int revokeAllForRole(UUID roleId, UUID revokedBy) {
        return accessKeys.keep(() -> {
            int revoked = 0;
            for (GrantedRoleRow row : grantedRoleRepository.findByRoleId(roleId)) {
                if (revoke(row.uuid(), revokedBy, false)) {
                    revoked++;
                }
            }
            return revoked;
        });
    }

    /**
     * Gives a restored role back to the people who lost it when it was deleted.
     * Call it before the role is restored: the grants are found by the role's deleted_at.
     * Returns the agents, for the caller to grant once the role is live again.
     */
    public List<UUID> agentsToRegrant(UUID roleId) {
        return grantedRoleRepository.findAgentsRevokedWithRole(roleId);
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

    /**
     * True when this agent is the only live agent holding the role.
     * Locks the role row first, so two admins removing each other at the same
     * moment take turns, and the second one sees the first removal.
     * Call it inside a transaction.
     */
    public boolean isLastHolderOfRole(UUID agentId, String roleName) {
        return grantedRoleRepository.lockActiveRoleId(roleName)
                .map(roleId -> grantedRoleRepository.isOnlyLiveHolder(agentId, roleId))
                .orElse(false);
    }

    // the reverse lookup: everyone currently holding a given role
    public List<GrantedRole> findAgentsWithRole(UUID roleId) {
        return grantedRoleRepository.findByRoleId(roleId)
                .stream()
                .map(GrantedRoleRow::toGrantedRole)
                .toList();
    }
}
