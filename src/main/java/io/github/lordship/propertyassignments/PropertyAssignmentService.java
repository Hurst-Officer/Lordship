package io.github.lordship.propertyassignments;

import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.identity.AgentAuthorizationCache;
import io.github.lordship.propertyassignments.internal.PropertyAssignmentRepository;
import io.github.lordship.propertyassignments.internal.PropertyAssignmentRow;
import io.github.lordship.shared.PropertyScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PropertyAssignmentService {

    private final PropertyAssignmentRepository propertyAssignmentRepository;
    private final AuditService auditService;
    private final AgentAuthorizationCache authorizationCache;

    public PropertyAssignmentService(PropertyAssignmentRepository propertyAssignmentRepository,
                                     AuditService auditService,
                                     AgentAuthorizationCache authorizationCache) {
        this.propertyAssignmentRepository = propertyAssignmentRepository;
        this.auditService = auditService;
        this.authorizationCache = authorizationCache;
    }

    // the assignment set is baked into the cached PropertyScope, so both of these
    // have to evict the agent they touch
    @Transactional
    public PropertyAssignment assign(UUID agentId, UUID propertyId, UUID assignedBy) {
        PropertyAssignmentRow row = propertyAssignmentRepository.save(
                new PropertyAssignmentRow(agentId, propertyId, assignedBy)
        );
        authorizationCache.invalidate(agentId);
        auditService.recordInsert("agent_property_assignment", row.uuid(), AuditMapper.toMap(row));
        return row.toPropertyAssignment();
    }

    @Transactional
    public boolean endAssignment(UUID assignmentId) {   // covers unassignAgentFromProperty
        return propertyAssignmentRepository.findById(assignmentId).map(assignment -> {
            if (!propertyAssignmentRepository.endAssignment(assignmentId)) {
                return false;
            }
            authorizationCache.invalidate(assignment.agentId());
            auditService.recordDelete("agent_property_assignment", assignmentId, AuditMapper.toMap(assignment));
            return true;
        }).orElse(false);
    }

    // True when the active assignment is to a property this scope may see. False for an unknown assignment.
    public boolean inScope(UUID assignmentId, PropertyScope scope) {
        return propertyAssignmentRepository.findById(assignmentId)
                .map(assignment -> scope.allows(assignment.propertyId()))
                .orElse(false);
    }

    public Set<UUID> getAgentAssignedPropertyUUIDs(UUID agentId) {
        return propertyAssignmentRepository.getAgentActiveAssignments(agentId)
                .stream()
                .map(PropertyAssignmentRow::propertyId)
                .collect(Collectors.toSet());
    }
}