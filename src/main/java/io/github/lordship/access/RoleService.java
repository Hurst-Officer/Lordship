package io.github.lordship.access;

import io.github.lordship.access.internal.AccessKeys;
import io.github.lordship.access.internal.RoleHistory;
import io.github.lordship.access.internal.role.RoleRepository;
import io.github.lordship.access.internal.role.RoleRow;
import io.github.lordship.audit.ActingAgent;
import io.github.lordship.audit.AuditContext;
import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.RuleConflict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// Makes, renames, deletes and restores roles. Permissions are given to roles by RolePermissionService,
// and roles to people by GrantedRoleService.
@Service
public class RoleService {

    /**
     * The roles the server finds by name: ApplicationInitializer puts them back at startup when they are
     * missing, AgentService keeps the last Admin, new agents may be given Unassigned. So they cannot be
     * renamed or deleted (409 role.built_in). What they can do is edited like any other role.
     */
    public static final List<String> BUILT_IN = List.of("Admin", "Office Staff", "Unassigned", "Property Manager");

    // agent_role.role_name is VARCHAR(60)
    static final int NAME_MAX = 60;

    private final RoleRepository roleRepository;
    private final GrantedRoleService grantedRoleService;
    private final RolePermissionService rolePermissionService;
    private final AuditService auditService;
    private final AuditContext auditContext;
    private final AccessKeys accessKeys;
    private final RoleHistory roleHistory;

    private static final Logger log = LoggerFactory.getLogger(RoleService.class);

    public RoleService(RoleRepository roleRepository,
                       GrantedRoleService grantedRoleService,
                       RolePermissionService rolePermissionService,
                       AuditService auditService,
                       AuditContext auditContext,
                       AccessKeys accessKeys,
                       RoleHistory roleHistory) {
        this.roleRepository = roleRepository;
        this.grantedRoleService = grantedRoleService;
        this.rolePermissionService = rolePermissionService;
        this.auditService = auditService;
        this.auditContext = auditContext;
        this.accessKeys = accessKeys;
        this.roleHistory = roleHistory;
    }

    public static boolean isBuiltIn(String roleName) {
        return roleName != null && BUILT_IN.contains(roleName);
    }

    @Transactional
    public int ensureDefaultRoles() {
        int count = 0;
        for (String roleName : BUILT_IN) {
            if (roleRepository.findByName(roleName).isEmpty()) {
                roleRepository.save(new RoleRow(roleName));
                log.warn("Re-seeded missing role: {}", roleName);
                count++;
            }
        }
        return count;
    }

    @Transactional
    public Role createRole(String roleName, String roleDescription) {
        return createRole(roleName, roleDescription, null);
    }

    /**
     * A new role. copyFrom, when not null, is a live role whose permissions the new one starts with
     * (400 role.unknown on copyFromRoleId otherwise). Nobody holds the new role yet.
     * 400 role.name_blank or role.name_too_long; 409 role.name_taken when a live role has the name, whatever its case.
     */
    @Transactional
    public Role createRole(String roleName, String roleDescription, UUID copyFrom) {
        accessKeys.lockRoleNames();
        String name = checkedName(roleName, null);
        if (copyFrom != null && roleRepository.findById(copyFrom).isEmpty()) {
            throw InvalidRequest.onField("copyFromRoleId", "role.unknown", copyFrom);
        }
        RoleRow roleRow = roleRepository.save(new RoleRow(name, blankToNull(roleDescription)));
        auditService.recordInsert("agent_role", roleRow.uuid(), AuditMapper.toMap(roleRow));
        if (copyFrom != null) {
            rolePermissionService.copyPermissions(copyFrom, roleRow.uuid());
        }
        return roleRow.toRole();
    }

    public Optional<Role> findById(UUID uuid) {
        return roleRepository.findById(uuid).map(RoleRow::toRole);
    }

    // Deleted roles, the most recently deleted first
    public List<Role> findDeleted() {
        return roleRepository.findDeleted().stream().map(RoleRow::toRole).toList();
    }

    /**
     * Changes the name or the description. Keys are column names (role_name, role_description).
     * A blank description clears it. A built-in role keeps its name (409 role.built_in).
     */
    @Transactional
    public Optional<Role> patchRole(UUID roleId, Map<String, Object> changes){
        Optional<RoleRow> before = roleRepository.findById(roleId);

        if (before.isEmpty()) return Optional.empty();

        RoleRow beforeRow = before.get();
        if (changes.containsKey("role_name")) {
            accessKeys.lockRoleNames();
            Object raw = changes.get("role_name");
            String name = checkedName(raw instanceof String s ? s : null, roleId);
            if (!name.equals(beforeRow.roleName()) && isBuiltIn(beforeRow.roleName())) {
                throw RuleConflict.of("role.built_in", beforeRow.roleName());
            }
            changes.put("role_name", name);
        }

        if (changes.containsKey("role_description")) {
            Object roleDescription = changes.get("role_description");
            changes.put("role_description", roleDescription instanceof String s ? blankToNull(s) : null);
        }

        Optional<RoleRow> after = roleRepository.patch(roleId, changes);
        if (after.isEmpty()) return Optional.empty();

        RoleRow afterRow = after.get();
        var diff = AuditMapper.diff(beforeRow, afterRow);
        if (!diff.before().isEmpty()) {
            auditService.recordUpdate("agent_role", roleId, diff.before(), diff.after());
        }
        return Optional.of(afterRow.toRole());
    }

    /**
     * Soft-deletes the role and revokes every grant that named it, each revocation
     * audited on its own so the log can answer why an agent lost a permission.
     * The role's own permissions stay with it, so a restore brings the role back whole.
     *
     * <p>The revocations are what take the permissions away for anything reading
     * granted_role directly; PermissionRepository joining agent_role is the backstop
     * for the two ever drifting apart.
     *
     * <p>A built-in role is never deleted (409 role.built_in). Neither is a role whose holders are
     * the last people who can manage roles (409 access.last_key, see AccessKeys).
     */
    @Transactional
    public boolean deleteRole(UUID uuid, UUID revokedBy) {
        Optional<RoleRow> found = roleRepository.findById(uuid);
        if (found.isEmpty()) return false;
        RoleRow roleRow = found.get();
        if (isBuiltIn(roleRow.roleName())) {
            throw RuleConflict.of("role.built_in", roleRow.roleName());
        }

        return accessKeys.keep(() -> {
            if (!roleRepository.softDelete(uuid)) {
                return false;
            }

            int revoked = grantedRoleService.revokeAllForRole(uuid, revokedBy);
            if (revoked > 0) {
                log.info("Deleting role {} revoked {} grant(s)", roleRow.roleName(), revoked);
            }

            auditService.recordDelete("agent_role", uuid, AuditMapper.toMap(roleRow));
            return true;
        });
    }

    /**
     * Brings a deleted role back, with its permissions, and gives it back to the people who lost it
     * when it was deleted (not to anyone who lost it earlier, on their own). A live role is answered as it is.
     * Empty for an unknown role. 409 role.name_taken when a live role took its name since.
     * Written to the audit log as one update of the role: deletedAt from the time back to empty, and the people given it back.
     */
    @Transactional
    public Optional<Role> restoreRole(UUID uuid) {
        accessKeys.lockRoleNames();
        Optional<RoleRow> found = roleRepository.findAnyById(uuid);
        if (found.isEmpty()) return Optional.empty();
        RoleRow row = found.get();
        if (row.deletedAt() == null) return Optional.of(row.toRole());

        if (roleRepository.findLiveByNameIgnoringCase(row.roleName()).isPresent()) {
            throw RuleConflict.of("role.name_taken", row.roleName());
        }
        // found by the role's deleted_at, so before the role comes back
        List<UUID> holders = grantedRoleService.agentsToRegrant(uuid);

        Optional<RoleRow> restored = roleRepository.restore(uuid);
        if (restored.isEmpty()) {
            // restored by someone else since it was read: answered as it is now
            return roleRepository.findById(uuid).map(RoleRow::toRole);
        }
        RoleRow back = restored.get();
        List<UUID> givenBack = grantedRoleService.regrant(uuid, holders, ActingAgent.resolve(auditContext));
        roleHistory.restored(uuid, row.deletedAt(), givenBack);
        return Optional.of(back.toRole());
    }

    // The name trimmed, or why it cannot be used. except is the role being renamed (it may keep its own name).
    private String checkedName(String raw, UUID except) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw InvalidRequest.onField("roleName", "role.name_blank");
        }
        if (name.length() > NAME_MAX) {
            throw InvalidRequest.onField("roleName", "role.name_too_long", NAME_MAX);
        }
        Optional<RoleRow> taken = roleRepository.findLiveByNameIgnoringCase(name);
        if (taken.isPresent() && !taken.get().uuid().equals(except)) {
            throw RuleConflict.of("role.name_taken", taken.get().roleName());
        }
        return name;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
