package io.github.lordship.homes;

import io.github.lordship.audit.AuditContext;
import io.github.lordship.audit.AuditService;
import io.github.lordship.homes.internal.StickBuiltRepository;
import io.github.lordship.homes.internal.StickBuiltRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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
public class StickBuiltServiceTest {

    @Mock
    StickBuiltRepository stickBuiltRepository;

    @Mock
    AuditService auditService;

    @Mock
    AuditContext auditContext;

    @InjectMocks
    StickBuiltService stickBuiltService;

    private StickBuiltRow row(UUID uuid, String name, UUID lotId, StructureType type) {
        return new StickBuiltRow(
                uuid, name, lotId, null, type, null, null, null, null, "SQFT",
                null, true, null, OffsetDateTime.now(ZoneOffset.UTC), null, null
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturePatch(UUID uuid) {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(stickBuiltRepository).patch(eq(uuid), captor.capture());
        return captor.getValue();
    }

    // ── defaultName ──────────────────────────────────────────────────────────────

    @Test
    void defaultName_readsStructureTypeAsAWordAnOfficeWorkerUses() {
        assertEquals("Building on lot 4B", StickBuiltService.defaultName(null, "4B"));
        assertEquals("Apartment on lot 4B", StickBuiltService.defaultName(StructureType.APARTMENT, "4B"));
        assertEquals("House on lot 4B", StickBuiltService.defaultName(StructureType.SINGLE_FAMILY, "4B"));
        assertEquals("Garage on lot 4B", StickBuiltService.defaultName(StructureType.GARAGE, "4B"));
        assertEquals("Shop on lot 4B", StickBuiltService.defaultName(StructureType.SHOP, "4B"));
        assertEquals("Outbuilding on lot 4B", StickBuiltService.defaultName(StructureType.OUTBUILDING, "4B"));
    }

    @Test
    void defaultName_dropsTheLotClause_whenTheStructureSitsOnNoLot() {
        assertEquals("Building", StickBuiltService.defaultName(null, null));
        assertEquals("House", StickBuiltService.defaultName(StructureType.SINGLE_FAMILY, null));
    }

    // ── createStickBuilt ─────────────────────────────────────────────────────────

    @Test
    void createStickBuilt_recordsInsert_andReturnsTheRow() {
        UUID lotId = UUID.randomUUID();
        StickBuiltRow saved = row(UUID.randomUUID(), "Building on lot 4B", lotId, null);
        when(stickBuiltRepository.save(eq(lotId), any())).thenReturn(Optional.of(saved));

        StickBuilt created = stickBuiltService.createStickBuilt(lotId);

        assertEquals(saved.uuid(), created.uuid());
        assertEquals("Building on lot 4B", created.name());
        verify(auditService).recordInsert(eq("stick_built"), eq(saved.uuid()), any());
    }

    @Test
    void createStickBuilt_throwsNamingTheLot_whenTheLotDoesNotExist() {
        UUID lotId = UUID.randomUUID();
        when(stickBuiltRepository.save(eq(lotId), any())).thenReturn(Optional.empty());

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> stickBuiltService.createStickBuilt(lotId));

        assertTrue(thrown.getMessage().contains(lotId.toString()));
        verifyNoInteractions(auditService);
    }

    // ── the generated name keeps itself current ──────────────────────────────────

    @Test
    void patchStickBuilt_upgradesTheGeneratedName_whenStructureTypeArrives() {
        UUID uuid = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        StickBuiltRow before = row(uuid, "Building on lot 4B", lotId, null);

        when(stickBuiltRepository.findById(uuid)).thenReturn(Optional.of(before));
        when(stickBuiltRepository.findLotNumber(lotId)).thenReturn(Optional.of("4B"));
        when(stickBuiltRepository.patch(eq(uuid), any()))
                .thenReturn(Optional.of(row(uuid, "House on lot 4B", lotId, StructureType.SINGLE_FAMILY)));

        stickBuiltService.patchStickBuilt(uuid, new HashMap<>(Map.of("structure_type", "SINGLE_FAMILY")));

        assertEquals("House on lot 4B", capturePatch(uuid).get("name"));
    }

    @Test
    void patchStickBuilt_rebuildsAgainstTheNewLot_whenTheStructureIsMoved() {
        UUID uuid = UUID.randomUUID();
        UUID oldLot = UUID.randomUUID();
        UUID newLot = UUID.randomUUID();
        StickBuiltRow before = row(uuid, "Apartment on lot 4B", oldLot, StructureType.APARTMENT);

        when(stickBuiltRepository.findById(uuid)).thenReturn(Optional.of(before));
        when(stickBuiltRepository.findLotNumber(oldLot)).thenReturn(Optional.of("4B"));
        when(stickBuiltRepository.findLotNumber(newLot)).thenReturn(Optional.of("9C"));
        when(stickBuiltRepository.patch(eq(uuid), any()))
                .thenReturn(Optional.of(row(uuid, "Apartment on lot 9C", newLot, StructureType.APARTMENT)));

        Map<String, Object> changes = new HashMap<>();
        changes.put("lot_id", newLot.toString());
        stickBuiltService.patchStickBuilt(uuid, changes);

        // the type word carries over, the lot number does not
        assertEquals("Apartment on lot 9C", capturePatch(uuid).get("name"));
    }

    @Test
    void patchStickBuilt_leavesAHumanNamedStructureAlone() {
        UUID uuid = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        StickBuiltRow before = row(uuid, "Manager House", lotId, null);

        when(stickBuiltRepository.findById(uuid)).thenReturn(Optional.of(before));
        when(stickBuiltRepository.findLotNumber(lotId)).thenReturn(Optional.of("4B"));
        when(stickBuiltRepository.patch(eq(uuid), any())).thenReturn(Optional.of(before));

        stickBuiltService.patchStickBuilt(uuid, new HashMap<>(Map.of("structure_type", "SHOP")));

        assertFalse(capturePatch(uuid).containsKey("name"));
    }

    @Test
    void patchStickBuilt_doesNotOverrideAnExplicitRename_inTheSameRequest() {
        UUID uuid = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        StickBuiltRow before = row(uuid, "Building on lot 4B", lotId, null);

        when(stickBuiltRepository.findById(uuid)).thenReturn(Optional.of(before));
        when(stickBuiltRepository.patch(eq(uuid), any())).thenReturn(Optional.of(before));

        Map<String, Object> changes = new HashMap<>();
        changes.put("structure_type", "APARTMENT");
        changes.put("name", "Apt 2B");
        stickBuiltService.patchStickBuilt(uuid, changes);

        assertEquals("Apt 2B", capturePatch(uuid).get("name"));
        verify(stickBuiltRepository, never()).findLotNumber(any());
    }

    @Test
    void patchStickBuilt_doesNotRename_whenNeitherTypeNorLotMoves() {
        UUID uuid = UUID.randomUUID();
        StickBuiltRow before = row(uuid, "Building on lot 4B", UUID.randomUUID(), null);

        when(stickBuiltRepository.findById(uuid)).thenReturn(Optional.of(before));
        when(stickBuiltRepository.patch(eq(uuid), any())).thenReturn(Optional.of(before));

        stickBuiltService.patchStickBuilt(uuid, new HashMap<>(Map.of("note", "roof done")));

        assertFalse(capturePatch(uuid).containsKey("name"));
        verify(stickBuiltRepository, never()).findLotNumber(any());
    }

    // ── coercion ─────────────────────────────────────────────────────────────────

    @Test
    void patchStickBuilt_coercesAreaToBigDecimal_ratherThanLettingADoubleThrough() {
        UUID uuid = UUID.randomUUID();
        StickBuiltRow before = row(uuid, "Building", null, null);

        when(stickBuiltRepository.findById(uuid)).thenReturn(Optional.of(before));
        when(stickBuiltRepository.patch(eq(uuid), any())).thenReturn(Optional.of(before));

        stickBuiltService.patchStickBuilt(uuid, new HashMap<>(Map.of("area", 1250.75d)));

        Object value = capturePatch(uuid).get("area");
        assertInstanceOf(BigDecimal.class, value);
        assertEquals(0, new BigDecimal("1250.75").compareTo((BigDecimal) value));
    }

    @Test
    void patchStickBuilt_normalisesStructureTypeCase() {
        UUID uuid = UUID.randomUUID();
        StickBuiltRow before = row(uuid, "Manager House", null, null);

        when(stickBuiltRepository.findById(uuid)).thenReturn(Optional.of(before));
        when(stickBuiltRepository.patch(eq(uuid), any())).thenReturn(Optional.of(before));

        stickBuiltService.patchStickBuilt(uuid, new HashMap<>(Map.of("structure_type", " apartment ")));

        assertEquals("APARTMENT", capturePatch(uuid).get("structure_type"));
    }

    @Test
    void patchStickBuilt_rejectsAnUnknownStructureType_beforeItReachesTheDatabase() {
        UUID uuid = UUID.randomUUID();
        StickBuiltRow before = row(uuid, "Manager House", null, null);
        when(stickBuiltRepository.findById(uuid)).thenReturn(Optional.of(before));

        assertThrows(IllegalArgumentException.class, () ->
                stickBuiltService.patchStickBuilt(uuid, new HashMap<>(Map.of("structure_type", "CASTLE"))));

        verify(stickBuiltRepository, never()).patch(any(), any());
        verifyNoInteractions(auditService);
    }

    // ── audit ────────────────────────────────────────────────────────────────────

    @Test
    void patchStickBuilt_recordsUpdate_whenAFieldActuallyChanges() {
        UUID uuid = UUID.randomUUID();
        StickBuiltRow before = row(uuid, "Manager House", null, null);
        StickBuiltRow after = row(uuid, "Manager House", null, StructureType.SHOP);

        when(stickBuiltRepository.findById(uuid)).thenReturn(Optional.of(before));
        when(stickBuiltRepository.patch(eq(uuid), any())).thenReturn(Optional.of(after));

        stickBuiltService.patchStickBuilt(uuid, new HashMap<>(Map.of("structure_type", "SHOP")));

        verify(auditService).recordUpdate(eq("stick_built"), eq(uuid), any(), any());
    }

    @Test
    void patchStickBuilt_recordsNothing_whenTheRowComesBackUnchanged() {
        UUID uuid = UUID.randomUUID();
        StickBuiltRow unchanged = row(uuid, "Manager House", null, StructureType.SHOP);

        when(stickBuiltRepository.findById(uuid)).thenReturn(Optional.of(unchanged));
        when(stickBuiltRepository.patch(eq(uuid), any())).thenReturn(Optional.of(unchanged));

        stickBuiltService.patchStickBuilt(uuid, new HashMap<>(Map.of("structure_type", "SHOP")));

        verifyNoInteractions(auditService);
    }

    @Test
    void patchStickBuilt_returnsEmpty_andTouchesNothing_whenTheStructureIsNotThere() {
        UUID uuid = UUID.randomUUID();
        when(stickBuiltRepository.findById(uuid)).thenReturn(Optional.empty());

        assertTrue(stickBuiltService.patchStickBuilt(uuid, new HashMap<>(Map.of("note", "x"))).isEmpty());

        verify(stickBuiltRepository, never()).patch(any(), any());
        verifyNoInteractions(auditService);
    }

    // ── reads and delete ─────────────────────────────────────────────────────────

    @Test
    void findByLot_isOptional_becauseALotHoldsAtMostOne() {
        UUID lotId = UUID.randomUUID();
        when(stickBuiltRepository.findByLot(lotId)).thenReturn(Optional.empty());

        assertTrue(stickBuiltService.findByLot(lotId).isEmpty());
    }

    @Test
    void deleteStickBuilt_recordsDelete_whenARowActuallyChanged() {
        StickBuiltRow existing = row(UUID.randomUUID(), "Manager House", null, null);
        when(stickBuiltRepository.findById(existing.uuid())).thenReturn(Optional.of(existing));
        when(stickBuiltRepository.softDelete(existing.uuid())).thenReturn(true);

        assertTrue(stickBuiltService.deleteStickBuilt(existing.uuid()));

        verify(auditService).recordDelete(eq("stick_built"), eq(existing.uuid()), any());
    }

    @Test
    void deleteStickBuilt_recordsNothing_whenNoRowChanged() {
        StickBuiltRow existing = row(UUID.randomUUID(), "Manager House", null, null);
        when(stickBuiltRepository.findById(existing.uuid())).thenReturn(Optional.of(existing));
        when(stickBuiltRepository.softDelete(existing.uuid())).thenReturn(false);

        assertFalse(stickBuiltService.deleteStickBuilt(existing.uuid()));

        verifyNoInteractions(auditService);
    }

    @Test
    void deleteStickBuilt_returnsFalse_whenTheStructureIsNotThere() {
        UUID unknown = UUID.randomUUID();
        when(stickBuiltRepository.findById(unknown)).thenReturn(Optional.empty());

        assertFalse(stickBuiltService.deleteStickBuilt(unknown));

        verify(stickBuiltRepository, never()).softDelete(any());
        verifyNoInteractions(auditService);
    }
}
