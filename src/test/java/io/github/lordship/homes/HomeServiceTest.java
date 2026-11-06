package io.github.lordship.homes;

import io.github.lordship.audit.AuditContext;
import io.github.lordship.audit.AuditService;
import io.github.lordship.homes.internal.HomeRepository;
import io.github.lordship.homes.internal.HomeRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class HomeServiceTest {

    @Mock
    HomeRepository homeRepository;

    @Mock
    AuditService auditService;

    @Mock
    AuditContext auditContext;

    @InjectMocks
    HomeService homeService;

    private HomeRow row(UUID uuid, String name, UUID lotId, Integer sections) {
        return new HomeRow(
                uuid, name, lotId, null, null, null, null, null, null, null,
                null, null, "FT", sections, null, null, null, null, null, false,
                OffsetDateTime.now(ZoneOffset.UTC), null, null
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturePatch(UUID uuid) {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(homeRepository).patch(eq(uuid), captor.capture());
        return captor.getValue();
    }

    // ── defaultName ──────────────────────────────────────────────────────────────

    @Test
    void defaultName_readsSectionCountAsAWidthWord() {
        assertEquals("Mobile home on lot 4B", HomeService.defaultName(null, "4B"));
        assertEquals("Single wide on lot 4B", HomeService.defaultName(1, "4B"));
        assertEquals("Double wide on lot 4B", HomeService.defaultName(2, "4B"));
        assertEquals("Triple wide on lot 4B", HomeService.defaultName(3, "4B"));
        assertEquals("Quad wide on lot 4B", HomeService.defaultName(4, "4B"));
    }

    @Test
    void defaultName_dropsTheLotClause_whenTheHomeSitsOnNoLot() {
        assertEquals("Mobile home", HomeService.defaultName(null, null));
        assertEquals("Double wide", HomeService.defaultName(2, null));
    }

    // ── createHome ───────────────────────────────────────────────────────────────

    @Test
    void createHome_recordsInsert_andReturnsTheRow() {
        UUID lotId = UUID.randomUUID();
        HomeRow saved = row(UUID.randomUUID(), "Mobile home on lot 4B", lotId, null);
        when(homeRepository.save(eq(lotId), any())).thenReturn(Optional.of(saved));

        Home created = homeService.createHome(lotId);

        assertEquals(saved.uuid(), created.uuid());
        assertEquals("Mobile home on lot 4B", created.name());
        verify(auditService).recordInsert(eq("mobile_home"), eq(saved.uuid()), any());
    }

    @Test
    void createHome_throwsNamingTheLot_whenTheLotDoesNotExist() {
        UUID lotId = UUID.randomUUID();
        when(homeRepository.save(eq(lotId), any())).thenReturn(Optional.empty());

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> homeService.createHome(lotId));

        // the id has to be in the message: this is what an office worker forwards
        assertTrue(thrown.getMessage().contains(lotId.toString()));
        verifyNoInteractions(auditService);
    }

    // ── the generated name keeps itself current ──────────────────────────────────

    @Test
    void patchHome_upgradesTheGeneratedName_whenSectionsArrives() {
        UUID uuid = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        HomeRow before = row(uuid, "Mobile home on lot 4B", lotId, null);

        when(homeRepository.findByIdForUpdate(uuid)).thenReturn(Optional.of(before));
        when(homeRepository.findLotNumber(lotId)).thenReturn(Optional.of("4B"));
        when(homeRepository.patch(eq(uuid), any())).thenReturn(Optional.of(row(uuid, "Double wide on lot 4B", lotId, 2)));

        homeService.patchHome(uuid, new HashMap<>(Map.of("sections", 2)));

        assertEquals("Double wide on lot 4B", capturePatch(uuid).get("name"));
    }

    @Test
    void patchHome_rebuildsAgainstTheNewLot_whenTheHomeIsMoved() {
        UUID uuid = UUID.randomUUID();
        UUID oldLot = UUID.randomUUID();
        UUID newLot = UUID.randomUUID();
        HomeRow before = row(uuid, "Double wide on lot 4B", oldLot, 2);

        when(homeRepository.findByIdForUpdate(uuid)).thenReturn(Optional.of(before));
        when(homeRepository.findLotNumber(oldLot)).thenReturn(Optional.of("4B"));
        when(homeRepository.findLotNumber(newLot)).thenReturn(Optional.of("9C"));
        when(homeRepository.patch(eq(uuid), any())).thenReturn(Optional.of(row(uuid, "Double wide on lot 9C", newLot, 2)));

        Map<String, Object> changes = new HashMap<>();
        changes.put("lot_id", newLot.toString());
        homeService.patchHome(uuid, changes);

        // the width word carries over, the lot number does not
        assertEquals("Double wide on lot 9C", capturePatch(uuid).get("name"));
    }

    @Test
    void patchHome_leavesAHumanNamedHomeAlone() {
        UUID uuid = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        HomeRow before = row(uuid, "Blue one by the office", lotId, null);

        when(homeRepository.findByIdForUpdate(uuid)).thenReturn(Optional.of(before));
        when(homeRepository.findLotNumber(lotId)).thenReturn(Optional.of("4B"));
        when(homeRepository.patch(eq(uuid), any())).thenReturn(Optional.of(before));

        homeService.patchHome(uuid, new HashMap<>(Map.of("sections", 2)));

        assertFalse(capturePatch(uuid).containsKey("name"));
    }

    @Test
    void patchHome_doesNotOverrideAnExplicitRename_inTheSameRequest() {
        UUID uuid = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        HomeRow before = row(uuid, "Mobile home on lot 4B", lotId, null);

        when(homeRepository.findByIdForUpdate(uuid)).thenReturn(Optional.of(before));
        when(homeRepository.patch(eq(uuid), any())).thenReturn(Optional.of(before));

        Map<String, Object> changes = new HashMap<>();
        changes.put("sections", 2);
        changes.put("name", "Granny unit");
        homeService.patchHome(uuid, changes);

        assertEquals("Granny unit", capturePatch(uuid).get("name"));
        // the lot was never looked up, because the rename never ran
        verify(homeRepository, never()).findLotNumber(any());
    }

    @Test
    void patchHome_doesNotRename_whenNeitherSectionsNorLotMoves() {
        UUID uuid = UUID.randomUUID();
        HomeRow before = row(uuid, "Mobile home on lot 4B", UUID.randomUUID(), null);

        when(homeRepository.findByIdForUpdate(uuid)).thenReturn(Optional.of(before));
        when(homeRepository.patch(eq(uuid), any())).thenReturn(Optional.of(before));

        homeService.patchHome(uuid, new HashMap<>(Map.of("make", "Fleetwood")));

        assertFalse(capturePatch(uuid).containsKey("name"));
        verify(homeRepository, never()).findLotNumber(any());
    }

    // ── coercion ─────────────────────────────────────────────────────────────────

    @Test
    void patchHome_coercesMoneyToBigDecimal_ratherThanLettingADoubleThrough() {
        UUID uuid = UUID.randomUUID();
        HomeRow before = row(uuid, "Mobile home", null, null);

        when(homeRepository.findByIdForUpdate(uuid)).thenReturn(Optional.of(before));
        when(homeRepository.patch(eq(uuid), any())).thenReturn(Optional.of(before));

        // Jackson hands a Map<String,Object> body's 42500.75 over as a Double
        homeService.patchHome(uuid, new HashMap<>(Map.of("estimated_value", 42500.75d)));

        Object value = capturePatch(uuid).get("estimated_value");
        assertInstanceOf(BigDecimal.class, value);
        assertEquals(0, new BigDecimal("42500.75").compareTo((BigDecimal) value));
    }

    @Test
    void patchHome_coercesDatesAndUuidsOutOfStrings() {
        UUID uuid = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        HomeRow before = row(uuid, "Blue one", lotId, null);

        when(homeRepository.findByIdForUpdate(uuid)).thenReturn(Optional.of(before));
        when(homeRepository.patch(eq(uuid), any())).thenReturn(Optional.of(before));

        Map<String, Object> changes = new HashMap<>();
        changes.put("estimated_value", 1000);
        changes.put("estimated_value_on", "2026-08-01");
        homeService.patchHome(uuid, changes);

        assertEquals(LocalDate.of(2026, 8, 1), capturePatch(uuid).get("estimated_value_on"));
    }

    @Test
    void patchHome_normalisesConditionCase() {
        UUID uuid = UUID.randomUUID();
        HomeRow before = row(uuid, "Blue one", null, null);

        when(homeRepository.findByIdForUpdate(uuid)).thenReturn(Optional.of(before));
        when(homeRepository.patch(eq(uuid), any())).thenReturn(Optional.of(before));

        homeService.patchHome(uuid, new HashMap<>(Map.of("condition", " good ")));

        assertEquals("GOOD", capturePatch(uuid).get("condition"));
    }

    @Test
    void patchHome_rejectsAnUnknownCondition_beforeItReachesTheDatabase() {
        UUID uuid = UUID.randomUUID();
        HomeRow before = row(uuid, "Blue one", null, null);
        when(homeRepository.findByIdForUpdate(uuid)).thenReturn(Optional.of(before));

        assertThrows(IllegalArgumentException.class, () ->
                homeService.patchHome(uuid, new HashMap<>(Map.of("condition", "MEDIOCRE"))));

        verify(homeRepository, never()).patch(any(), any());
        verifyNoInteractions(auditService);
    }

    // ── audit ────────────────────────────────────────────────────────────────────

    @Test
    void patchHome_recordsUpdate_whenAFieldActuallyChanges() {
        UUID uuid = UUID.randomUUID();
        HomeRow before = row(uuid, "Blue one", null, null);
        HomeRow after = row(uuid, "Blue one", null, 2);

        when(homeRepository.findByIdForUpdate(uuid)).thenReturn(Optional.of(before));
        when(homeRepository.patch(eq(uuid), any())).thenReturn(Optional.of(after));

        homeService.patchHome(uuid, new HashMap<>(Map.of("sections", 2)));

        verify(auditService).recordUpdate(eq("mobile_home"), eq(uuid), any(), any());
    }

    @Test
    void patchHome_recordsNothing_whenTheRowComesBackUnchanged() {
        UUID uuid = UUID.randomUUID();
        HomeRow unchanged = row(uuid, "Blue one", null, 2);

        when(homeRepository.findByIdForUpdate(uuid)).thenReturn(Optional.of(unchanged));
        when(homeRepository.patch(eq(uuid), any())).thenReturn(Optional.of(unchanged));

        homeService.patchHome(uuid, new HashMap<>(Map.of("sections", 2)));

        verifyNoInteractions(auditService);
    }

    @Test
    void patchHome_returnsEmpty_andTouchesNothing_whenTheHomeIsNotThere() {
        UUID uuid = UUID.randomUUID();
        when(homeRepository.findByIdForUpdate(uuid)).thenReturn(Optional.empty());

        assertTrue(homeService.patchHome(uuid, new HashMap<>(Map.of("sections", 2))).isEmpty());

        verify(homeRepository, never()).patch(any(), any());
        verifyNoInteractions(auditService);
    }

    // ── delete ───────────────────────────────────────────────────────────────────

    @Test
    void deleteHome_recordsDelete_whenARowActuallyChanged() {
        HomeRow existing = row(UUID.randomUUID(), "Blue one", null, null);
        when(homeRepository.findById(existing.uuid())).thenReturn(Optional.of(existing));
        when(homeRepository.softDelete(existing.uuid())).thenReturn(true);

        assertTrue(homeService.deleteHome(existing.uuid()));

        verify(auditService).recordDelete(eq("mobile_home"), eq(existing.uuid()), any());
    }

    @Test
    void deleteHome_recordsNothing_whenNoRowChanged() {
        HomeRow existing = row(UUID.randomUUID(), "Blue one", null, null);
        when(homeRepository.findById(existing.uuid())).thenReturn(Optional.of(existing));
        when(homeRepository.softDelete(existing.uuid())).thenReturn(false);

        assertFalse(homeService.deleteHome(existing.uuid()));

        verifyNoInteractions(auditService);
    }

    @Test
    void deleteHome_returnsFalse_whenTheHomeIsNotThere() {
        UUID unknown = UUID.randomUUID();
        when(homeRepository.findById(unknown)).thenReturn(Optional.empty());

        assertFalse(homeService.deleteHome(unknown));

        verify(homeRepository, never()).softDelete(any());
        verifyNoInteractions(auditService);
    }
}
