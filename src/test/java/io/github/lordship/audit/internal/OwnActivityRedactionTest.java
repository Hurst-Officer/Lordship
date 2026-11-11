package io.github.lordship.audit.internal;

import io.github.lordship.audit.AuditContext;
import io.github.lordship.audit.AuditService;
import io.github.lordship.audit.History;
import io.github.lordship.identity.AgentPrincipal;
import io.github.lordship.shared.PropertyScope;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What "My activity" (GET /api/auditlogs/mine) leaves out of an agent's own saves.
 * No database: the saves come from a mocked AuditRepository, through the real AuditService and AuditController.
 */
public class OwnActivityRedactionTest {

    private final AuditRepository auditRepository = mock(AuditRepository.class);
    private final AuditController controller = new AuditController(
            new AuditService(new AuditContext(), JsonMapper.builder().build(), auditRepository));

    private final UUID agentId = UUID.randomUUID();
    private final UUID personId = UUID.randomUUID();

    // One update the agent saved, as AuditRepository.findOwnSaves reads the save from audit_log
    private static HistoryRows.SaveRow update(String table, String recordId, String before, String after, UUID propertyId) {
        return new HistoryRows.SaveRow(UUID.randomUUID(), table, "UPDATE", OffsetDateTime.now(), UUID.randomUUID().toString(),
                1, recordId, "changed", before, after, true,
                "A record", propertyId, propertyId == null ? null : "Cedar Park", null);
    }

    // "My activity" as the agent gets the page, with this property scope and these permissions
    private List<History.Save> myActivity(PropertyScope scope, List<HistoryRows.SaveRow> rows, String... permissions) {
        when(auditRepository.findOwnSaves(any(), any(), anyBoolean(), anyInt(), anyInt())).thenReturn(rows);
        Authentication agent = new UsernamePasswordAuthenticationToken(new AgentPrincipal(agentId, personId, scope), null,
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList());
        return controller.ownActivity("when", false, 0, 25, agent).getBody().saves();
    }

    private static History.Edit edit(History.Save save, String field) {
        return save.changes().stream()
                .filter(change -> change.field().equals(field))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no change named " + field + " in " + save.changes()));
    }

    private static void assertWithheld(History.Edit edit) {
        assertTrue(edit.withheld(), edit.field() + " must be withheld");
        assertNull(edit.before(), edit.field() + " must not show the value before");
        assertNull(edit.after(), edit.field() + " must not show the value after");
    }

    @Test
    void theSocialTheEmergencyContactAndThePassword_areWithheld_evenFromSomeoneWhoMayViewTheRecord() {
        // Arrange: a change to another person, and a password reset of another agent
        HistoryRows.SaveRow person = update("person", UUID.randomUUID().toString(),
                "{\"nameFull\":\"Ana Ruiz\",\"social\":\"old-cipher\",\"emergencyContact\":\"" + UUID.randomUUID() + "\"}",
                "{\"nameFull\":\"Ana Ruiz Soto\",\"social\":\"new-cipher\",\"emergencyContact\":\"" + UUID.randomUUID() + "\"}",
                null);
        HistoryRows.SaveRow password = update("agent", UUID.randomUUID().toString(),
                "{\"agent_password\":\"[redacted]\"}", "{\"agent_password\":\"[reset]\"}", null);

        // Act
        List<History.Save> saves = myActivity(new PropertyScope.All(), List.of(person, password),
                "audit:view_own", "persons:view", "agents:view");

        // Assert: the name shows what the name was and became, the three secret fields only that they changed
        assertEquals("Ana Ruiz", edit(saves.get(0), "nameFull").before());
        assertEquals("Ana Ruiz Soto", edit(saves.get(0), "nameFull").after());
        assertFalse(edit(saves.get(0), "nameFull").withheld());
        assertWithheld(edit(saves.get(0), "social"));
        assertWithheld(edit(saves.get(0), "emergencyContact"));
        assertWithheld(edit(saves.get(1), "agent_password"));
    }

    @Test
    void aSaveUnderAPropertyOutsideTheAgentsScope_comesBackHidden_withNothingThatNamesTheRecord() {
        // Arrange: a lot the agent renumbered in a park the agent is no longer assigned to
        UUID park = UUID.randomUUID();
        HistoryRows.SaveRow lot = update("lot", UUID.randomUUID().toString(),
                "{\"lotNumber\":\"12\"}", "{\"lotNumber\":\"12A\"}", park);

        // Act
        History.Save outside = myActivity(new PropertyScope.Restricted(Set.of(UUID.randomUUID())), List.of(lot),
                "audit:view_own", "lots:view").get(0);
        History.Save inside = myActivity(new PropertyScope.Restricted(Set.of(park)), List.of(lot),
                "audit:view_own", "lots:view").get(0);

        // Assert: outside the scope the save only says a lot was updated
        assertTrue(outside.hidden());
        assertEquals("lot", outside.table());
        assertNull(outside.recordId());
        assertNull(outside.recordName());
        assertNull(outside.propertyId());
        assertNull(outside.propertyName());
        assertEquals(List.of(), outside.fields());
        assertEquals(List.of(), outside.changes());

        // the same save inside the scope is whole
        assertFalse(inside.hidden());
        assertEquals(lot.recordId(), inside.recordId());
        assertEquals("12A", edit(inside, "lotNumber").after());
    }

    @Test
    void whatARoleCanDo_isWithheldOnARoleSave_withoutRolePermissionsView() {
        // Arrange: two permissions given to a role and the role's description changed, in one save
        HistoryRows.SaveRow role = update("agent_role", UUID.randomUUID().toString(),
                "{\"permissions\":{\"lots:edit\":false,\"lots:delete\":false},\"roleDescription\":\"Front desk\"}",
                "{\"permissions\":{\"lots:edit\":true,\"lots:delete\":true},\"roleDescription\":\"Front desk and lots\"}",
                null);

        // Act
        History.Save without = myActivity(new PropertyScope.All(), List.of(role),
                "audit:view_own", "agent_roles:view").get(0);
        History.Save with = myActivity(new PropertyScope.All(), List.of(role),
                "audit:view_own", "agent_roles:view", "role_permissions:view").get(0);

        // Assert: no permission is named, "permissions" is listed once as withheld, the description still shows
        assertEquals(List.of("permissions", "roleDescription"),
                without.changes().stream().map(History.Edit::field).sorted().toList());
        assertWithheld(edit(without, "permissions"));
        assertEquals("Front desk and lots", edit(without, "roleDescription").after());

        // with role_permissions:view each permission shows
        assertEquals(true, edit(with, "permissions.lots:edit").after());
        assertEquals(false, edit(with, "permissions.lots:delete").before());
    }

    @Test
    void aKindOfRecordTheAgentMayNotView_hasItsValuesWithheld_exceptOnTheAgentsOwnPersonRecord() {
        // Arrange: the agent may see their own activity and nothing else
        HistoryRows.SaveRow someoneElse = update("person", UUID.randomUUID().toString(),
                "{\"nameFull\":\"Ana Ruiz\"}", "{\"nameFull\":\"Ana Ruiz Soto\"}", null);
        HistoryRows.SaveRow own = update("person", personId.toString(),
                "{\"personalPhone\":\"360-555-0101\",\"social\":\"old-cipher\"}",
                "{\"personalPhone\":\"360-555-0199\",\"social\":\"new-cipher\"}", null);

        // Act
        List<History.Save> saves = myActivity(new PropertyScope.All(), List.of(someoneElse, own), "audit:view_own");

        // Assert: another person's name is withheld, the agent's own phone shows, the agent's own social never does
        assertWithheld(edit(saves.get(0), "nameFull"));
        assertEquals("360-555-0199", edit(saves.get(1), "personalPhone").after());
        assertWithheld(edit(saves.get(1), "social"));
    }
}
