package io.github.lordship.propertyassignments.internal;

import io.github.lordship.identity.AgentPrincipal;
import io.github.lordship.propertyassignments.PropertyAssignmentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

// An agent can only assign or end assignments for properties in their own scope (see PropertyScope).
// A property outside it is treated as if it did not exist: 404. So no one can widen their own access.
@RestController
@RequestMapping("/api/property-assignments")
public class PropertyAssignmentController {

    private final PropertyAssignmentService propertyAssignmentService;

    public PropertyAssignmentController(PropertyAssignmentService propertyAssignmentService) {
        this.propertyAssignmentService = propertyAssignmentService;
    }

    @PreAuthorize("hasAuthority('assignments:assign')")
    @PostMapping("/assign")
    public ResponseEntity<PropertyAssignmentResponse> assignPropertyToAgent(@Valid @RequestBody PropertyAssignmentRequest req, Authentication authentication) {
        // only agents assign properties
        if (!(authentication.getPrincipal() instanceof AgentPrincipal principal)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!principal.propertyScope().allows(req.propertyId())) {
            return ResponseEntity.notFound().build();
        }

        PropertyAssignmentResponse response = PropertyAssignmentResponse.from(
            propertyAssignmentService.assign(req.agentId(), req.propertyId(), principal.agentUuid())
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PreAuthorize("hasAuthority('assignments:remove')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> removeAssignment(@PathVariable UUID uuid, Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof AgentPrincipal principal)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!propertyAssignmentService.inScope(uuid, principal.propertyScope())) {
            return ResponseEntity.notFound().build();
        }
        return propertyAssignmentService.endAssignment(uuid) ?
                ResponseEntity.noContent().build() :
                ResponseEntity.notFound().build();
    }
}
