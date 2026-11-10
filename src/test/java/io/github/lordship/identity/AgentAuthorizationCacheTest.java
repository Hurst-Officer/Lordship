package io.github.lordship.identity;

import io.github.lordship.shared.PropertyScope;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * No Spring here on purpose -- the cache has no collaborators, and the contract
 * worth pinning is "load once, then serve from memory until told otherwise".
 */
public class AgentAuthorizationCacheTest {

    private final AgentAuthorizationCache cache = new AgentAuthorizationCache();

    private static AgentAuthorization snapshotFor(UUID agentId) {
        return new AgentAuthorization(
                new AgentPrincipal(agentId, UUID.randomUUID(), new PropertyScope.All()),
                null,
                Set.of("tenants:view"),
                Set.of()
        );
    }

    private static Function<UUID, AgentAuthorization> countingLoader(AtomicInteger loads) {
        return agentId -> {
            loads.incrementAndGet();
            return snapshotFor(agentId);
        };
    }

    @Test
    void get_shouldLoadOnce_thenServeFromMemory() {
        // Arrange
        UUID agentId = UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();

        // Act
        cache.get(agentId, countingLoader(loads));
        cache.get(agentId, countingLoader(loads));
        cache.get(agentId, countingLoader(loads));

        // Assert
        assertEquals(1, loads.get());
    }

    @Test
    void invalidate_shouldForceOneReload() {
        // Arrange
        UUID agentId = UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();
        cache.get(agentId, countingLoader(loads));

        // Act
        cache.invalidate(agentId);
        cache.get(agentId, countingLoader(loads));

        // Assert
        assertEquals(2, loads.get());
    }

    @Test
    void invalidate_shouldLeaveOtherAgentsAlone() {
        // Arrange
        UUID kept = UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();
        cache.get(kept, countingLoader(loads));

        // Act
        cache.invalidate(UUID.randomUUID());
        cache.get(kept, countingLoader(loads));

        // Assert
        assertEquals(1, loads.get());
    }

    @Test
    void invalidateAll_shouldForceAReloadForEveryAgent() {
        // Arrange
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();
        cache.get(first, countingLoader(loads));
        cache.get(second, countingLoader(loads));

        // Act
        cache.invalidateAll();
        cache.get(first, countingLoader(loads));
        cache.get(second, countingLoader(loads));

        // Assert
        assertEquals(4, loads.get());
    }

    // A token naming an agent that is gone must not be answered from a cached
    // "missing", or a re-created agent would stay invisible for the whole TTL.
    @Test
    void get_shouldNotCacheAMissingAgent() {
        // Arrange
        UUID agentId = UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();
        Function<UUID, AgentAuthorization> absent = id -> {
            loads.incrementAndGet();
            return null;
        };

        // Act
        AgentAuthorization first = cache.get(agentId, absent);
        AgentAuthorization second = cache.get(agentId, absent);

        // Assert
        assertNull(first);
        assertNull(second);
        assertEquals(2, loads.get());
    }

    // One instance is shared across concurrent requests, so the snapshot has to own
    // its collections. getAgentAssignedPropertyUUIDs hands back a plain HashSet.
    @Test
    void snapshot_shouldCopyTheSetsItIsGiven() {
        // Arrange
        Set<UUID> assignedProperties = new HashSet<>();
        assignedProperties.add(UUID.randomUUID());

        AgentAuthorization authorization = new AgentAuthorization(
                new AgentPrincipal(UUID.randomUUID(), UUID.randomUUID(),
                        new PropertyScope.Restricted(assignedProperties)),
                null,
                Set.of("tenants:view"),
                Set.of()
        );

        // Act
        assignedProperties.add(UUID.randomUUID());

        // Assert
        assertEquals(1, authorization.principal().propertyScope().idsOrEmpty().size());
        assertThrows(UnsupportedOperationException.class,
                () -> authorization.permissions().add("tenants:edit"));
        assertThrows(UnsupportedOperationException.class,
                () -> authorization.principal().propertyScope().idsOrEmpty().add(UUID.randomUUID()));
    }
}