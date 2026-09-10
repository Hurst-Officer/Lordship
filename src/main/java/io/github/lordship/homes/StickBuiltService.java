package io.github.lordship.homes;

import io.github.lordship.audit.AuditContext;
import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.homes.internal.StickBuiltRepository;
import io.github.lordship.homes.internal.StickBuiltRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class StickBuiltService {

    private static final String TABLE = "stick_built";

    private final StickBuiltRepository stickBuiltRepository;
    private final AuditService auditService;
    private final AuditContext auditContext;

    public StickBuiltService(StickBuiltRepository stickBuiltRepository,
                             AuditService auditService,
                             AuditContext auditContext) {
        this.stickBuiltRepository = stickBuiltRepository;
        this.auditService = auditService;
        this.auditContext = auditContext;
    }

    // The label an unnamed structure carries. Structure type is unknown at insert, so
    // a new row starts as "Building on lot 4B" and becomes "House on lot 4B" once the
    // type is filled in. These five words are the only place display wording lives.
    public static String defaultName(StructureType structureType, String lotNumber) {
        String kind = structureType == null ? "Building" : switch (structureType) {
            case APARTMENT -> "Apartment";
            case SINGLE_FAMILY -> "House";
            case GARAGE -> "Garage";
            case SHOP -> "Shop";
            case OUTBUILDING -> "Outbuilding";
        };
        return lotNumber == null ? kind : kind + " on lot " + lotNumber;
    }

    @Transactional
    public StickBuilt createStickBuilt(UUID lotId) {
        StickBuiltRow row = stickBuiltRepository.save(lotId, auditContext.getActingUserId())
                .orElseThrow(() -> new IllegalArgumentException("No lot " + lotId));
        auditService.recordInsert(TABLE, row.uuid(), AuditMapper.toMap(row));
        return row.toStickBuilt();
    }

    public Optional<StickBuilt> findById(UUID uuid) {
        return stickBuiltRepository.findById(uuid).map(StickBuiltRow::toStickBuilt);
    }

    // at most one, by uq_stick_built_lot
    public Optional<StickBuilt> findByLot(UUID lotId) {
        return stickBuiltRepository.findByLot(lotId).map(StickBuiltRow::toStickBuilt);
    }

    public List<StickBuilt> findByProperty(String propertyCode) {
        return stickBuiltRepository.findByProperty(propertyCode)
                .stream().map(StickBuiltRow::toStickBuilt).toList();
    }

    @Transactional
    public Optional<StickBuilt> patchStickBuilt(UUID uuid, Map<String, Object> changes) {

        Optional<StickBuiltRow> beforeOpt = stickBuiltRepository.findById(uuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        StickBuiltRow before = beforeOpt.get();

        coerce(changes);
        renameIfStillDefault(before, changes);

        Optional<StickBuiltRow> afterOpt = stickBuiltRepository.patch(uuid, changes);
        if (afterOpt.isEmpty()) {
            return Optional.empty();
        }
        StickBuiltRow after = afterOpt.get();

        var diff = AuditMapper.diff(before, after);
        if (!diff.before().isEmpty()) {
            auditService.recordUpdate(TABLE, uuid, diff.before(), diff.after());
        }
        return Optional.of(after.toStickBuilt());
    }

    @Transactional
    public boolean deleteStickBuilt(UUID uuid) {
        return stickBuiltRepository.findById(uuid).map(stickBuilt -> {
            if (!stickBuiltRepository.softDelete(uuid)) {
                return false;
            }
            auditService.recordDelete(TABLE, uuid, AuditMapper.toMap(stickBuilt));
            return true;
        }).orElse(false);
    }

    // Keeps the generated name in step with the two fields it is built from, but only
    // while it is still the generated one. The moment a person types their own name it
    // stops matching the default and nothing here touches it again.
    private void renameIfStillDefault(StickBuiltRow before, Map<String, Object> changes) {
        boolean namingFieldMoved = changes.containsKey("structure_type") || changes.containsKey("lot_id");
        if (!namingFieldMoved || changes.containsKey("name")) {
            return;
        }

        UUID oldLotId = before.lotId();
        String oldLotNumber = oldLotId == null ? null : stickBuiltRepository.findLotNumber(oldLotId).orElse(null);

        if (!Objects.equals(before.name(), defaultName(before.structureType(), oldLotNumber))) {
            return; // someone named it
        }

        UUID newLotId = changes.containsKey("lot_id") ? (UUID) changes.get("lot_id") : oldLotId;
        StructureType newType = changes.containsKey("structure_type")
                ? readStructureType(changes.get("structure_type"))
                : before.structureType();

        String newLotNumber = Objects.equals(newLotId, oldLotId)
                ? oldLotNumber
                : (newLotId == null ? null : stickBuiltRepository.findLotNumber(newLotId).orElse(null));

        changes.put("name", defaultName(newType, newLotNumber));
    }

    // JSON hands us Strings and Doubles where the columns want UUID and BigDecimal.
    private static void coerce(Map<String, Object> changes) {
        toUuid(changes, "lot_id");
        toDecimal(changes, "bathroom_count");
        toDecimal(changes, "area");
        toStructureType(changes);
    }

    private static void toUuid(Map<String, Object> changes, String key) {
        if (!changes.containsKey(key)) return;
        Object value = changes.get(key);
        changes.put(key, value instanceof String s && !s.isBlank() ? UUID.fromString(s) : null);
    }

    private static void toDecimal(Map<String, Object> changes, String key) {
        if (!changes.containsKey(key)) return;
        Object value = changes.get(key);
        if (value == null || value instanceof String s && s.isBlank()) {
            changes.put(key, null);
        } else {
            changes.put(key, new BigDecimal(value.toString()));
        }
    }

    // Rejected here rather than at the CHECK, so a bad type comes back a 400 naming
    // the value instead of a constraint violation.
    private static void toStructureType(Map<String, Object> changes) {
        if (!changes.containsKey("structure_type")) return;
        StructureType parsed = readStructureType(changes.get("structure_type"));
        changes.put("structure_type", parsed == null ? null : parsed.name());
    }

    private static StructureType readStructureType(Object value) {
        if (value == null || value instanceof String s && s.isBlank()) {
            return null;
        }
        if (value instanceof StructureType t) {
            return t;
        }
        return StructureType.valueOf(value.toString().trim().toUpperCase());
    }
}
