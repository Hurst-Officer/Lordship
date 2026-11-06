package io.github.lordship.homes;

import io.github.lordship.audit.AuditContext;
import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.homes.internal.HomeRepository;
import io.github.lordship.homes.internal.HomeRow;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.PropertyScope;
import io.github.lordship.shared.RuleConflict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class HomeService {

    private static final String TABLE = "mobile_home";

    private final HomeRepository homeRepository;
    private final AuditService auditService;
    private final AuditContext auditContext;

    public HomeService(HomeRepository homeRepository,
                       AuditService auditService,
                       AuditContext auditContext) {
        this.homeRepository = homeRepository;
        this.auditService = auditService;
        this.auditContext = auditContext;
    }

    // The label an unnamed home carries. Section count is unknown at insert, so a new
    // home starts as "Mobile home on lot 4B" and becomes "Double wide on lot 4B" when
    // sections is filled in.
    public static String defaultName(Integer sections, String lotNumber) {
        String kind = switch (sections == null ? 0 : sections) {
            case 1 -> "Single wide";
            case 2 -> "Double wide";
            case 3 -> "Triple wide";
            case 4 -> "Quad wide";
            default -> "Mobile home";
        };
        return lotNumber == null ? kind : kind + " on lot " + lotNumber;
    }

    @Transactional
    public Home createHome(UUID lotId) {
        HomeRow row = homeRepository.save(lotId, auditContext.getActingUserId())
                .orElseThrow(() -> InvalidRequest.of("home.lot_unknown", lotId));
        auditService.recordInsert(TABLE, row.uuid(), AuditMapper.toMap(row));
        return row.toHome();
    }

    public Optional<Home> findById(UUID uuid) {
        return homeRepository.findById(uuid).map(HomeRow::toHome);
    }

    public List<Home> findByLot(UUID lotId) {
        return homeRepository.findByLot(lotId).stream().map(HomeRow::toHome).toList();
    }

    public List<Home> findByProperty(String propertyCode) {
        return homeRepository.findByProperty(propertyCode).stream().map(HomeRow::toHome).toList();
    }

    public List<Home> findByVin(String vin) {
        return homeRepository.findByVin(vin).stream().map(HomeRow::toHome).toList();
    }

    // ---- property scope -------------------------------------------------------
    // An agent only sees the homes in properties they are assigned to (or all, with assignments:assign-all).
    // A home is in a property through its lot. A home on no lot is only seen with every property.

    // True when the home, deleted or not, is in a property this scope may see.
    // Always true with every property: an unknown home then gets its usual not-found answer.
    public boolean inScope(UUID homeId, PropertyScope scope) {
        if (scope.isAll()) {
            return true;
        }
        return homeRepository.findAnyById(homeId)
                .map(home -> home.lotId() == null ? scope.isAll() : lotInScope(home.lotId(), scope))
                .orElse(false);
    }

    // True when the lot, deleted or not, is in a property this scope may see
    public boolean lotInScope(UUID lotId, PropertyScope scope) {
        return scope.isAll() || homeRepository.findPropertyIdOfLot(lotId).map(scope::allows).orElse(false);
    }

    // True when the property with this code is one this scope may see
    public boolean propertyCodeInScope(String propertyCode, PropertyScope scope) {
        return scope.isAll() || homeRepository.findPropertyIdByCode(propertyCode).map(scope::allows).orElse(false);
    }

    // The homes with this VIN that this scope may see
    public List<Home> findByVin(String vin, PropertyScope scope) {
        return findByVin(vin).stream()
                .filter(home -> home.lotId() == null ? scope.isAll() : lotInScope(home.lotId(), scope))
                .toList();
    }

    @Transactional
    public Optional<Home> patchHome(UUID uuid, Map<String, Object> changes) {

        // locked, so a coworker's save waits for this one and both are logged right
        Optional<HomeRow> homeBeforeOpt = homeRepository.findByIdForUpdate(uuid);
        if (homeBeforeOpt.isEmpty()) {
            return Optional.empty();
        }
        HomeRow homeBefore = homeBeforeOpt.get();

        coerce(changes);
        renameIfStillDefault(homeBefore, changes);

        Optional<HomeRow> homeAfterOpt = homeRepository.patch(uuid, changes);
        if (homeAfterOpt.isEmpty()) {
            return Optional.empty();
        }
        HomeRow homeAfter = homeAfterOpt.get();

        var diff = AuditMapper.diff(homeBefore, homeAfter);
        if (!diff.before().isEmpty()) {
            auditService.recordUpdate(TABLE, uuid, diff.before(), diff.after());
        }
        return Optional.of(homeAfter.toHome());
    }

    @Transactional
    public boolean deleteHome(UUID uuid) {
        return homeRepository.findById(uuid).map(home -> {
            if (!homeRepository.softDelete(uuid)) {
                return false;
            }
            auditService.recordDelete(TABLE, uuid, AuditMapper.toMap(home));
            return true;
        }).orElse(false);
    }

    // Brings a deleted home back, with its secured parties (they were never touched).
    // A home that is not deleted is returned as it is. Empty when there is no such home.
    // Refused while the home's lot is deleted (409).
    @Transactional
    public Optional<Home> restoreHome(UUID uuid) {
        Optional<HomeRow> found = homeRepository.findAnyById(uuid);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        HomeRow before = found.get();
        if (before.deletedAt() == null) {
            return Optional.of(before.toHome());
        }
        if (before.lotId() != null && !homeRepository.isLotLive(before.lotId())) {
            throw RuleConflict.of("home.lot_deleted");
        }

        Optional<HomeRow> after = homeRepository.restore(uuid);
        after.ifPresent(row -> {
            var diff = AuditMapper.diff(before, row);
            auditService.recordUpdate(TABLE, uuid, diff.before(), diff.after());
        });
        // a coworker restored it a moment earlier
        if (after.isEmpty()) {
            return findById(uuid);
        }
        return after.map(HomeRow::toHome);
    }

    // A lot's deleted homes, the most recently deleted first
    public List<Home> findDeletedByLot(UUID lotId) {
        return homeRepository.findDeletedByLot(lotId).stream().map(HomeRow::toHome).toList();
    }

    // Keeps the generated name in step with the two fields it is built from, but only
    // while it is still the generated one. The moment a person types their own name it
    // stops matching the default and nothing here touches it again.
    private void renameIfStillDefault(HomeRow before, Map<String, Object> changes) {
        boolean namingFieldMoved = changes.containsKey("sections") || changes.containsKey("lot_id");
        if (!namingFieldMoved || changes.containsKey("name")) {
            return;
        }

        UUID oldLotId = before.lotId();
        String oldLotNumber = oldLotId == null ? null : homeRepository.findLotNumber(oldLotId).orElse(null);

        if (!Objects.equals(before.name(), defaultName(before.sections(), oldLotNumber))) {
            return; // someone named it
        }

        UUID newLotId = changes.containsKey("lot_id") ? (UUID) changes.get("lot_id") : oldLotId;
        Integer newSections = changes.containsKey("sections")
                ? readInt(changes.get("sections"))
                : before.sections();

        String newLotNumber = Objects.equals(newLotId, oldLotId)
                ? oldLotNumber
                : (newLotId == null ? null : homeRepository.findLotNumber(newLotId).orElse(null));

        changes.put("name", defaultName(newSections, newLotNumber));
    }

    // JSON hands us Strings and Doubles where the columns want UUID, LocalDate and
    // BigDecimal. Money through a Double is the one that would bite quietly.
    private static void coerce(Map<String, Object> changes) {
        toUuid(changes, "lot_id");
        toDate(changes, "estimated_value_on");
        toDecimal(changes, "estimated_value");
        toDecimal(changes, "bathroom_count");
        toDecimal(changes, "width");
        toDecimal(changes, "length");
        toCondition(changes);
    }

    private static void toUuid(Map<String, Object> changes, String key) {
        if (!changes.containsKey(key)) return;
        Object value = changes.get(key);
        changes.put(key, value instanceof String s && !s.isBlank() ? UUID.fromString(s) : null);
    }

    private static void toDate(Map<String, Object> changes, String key) {
        if (!changes.containsKey(key)) return;
        Object value = changes.get(key);
        changes.put(key, value instanceof String s && !s.isBlank() ? LocalDate.parse(s) : null);
    }

    private static void toDecimal(Map<String, Object> changes, String key) {
        if (!changes.containsKey(key)) return;
        Object value = changes.get(key);
        if (value == null || value instanceof String s && s.isBlank()) {
            changes.put(key, null);
            return;
        }
        try {
            changes.put(key, new BigDecimal(value.toString()));
        } catch (NumberFormatException e) {
            throw InvalidRequest.onField(key, "home.not_a_number");
        }
    }

    // Rejected here rather than at the CHECK, so a bad grade comes back a 400 naming
    // the value instead of a constraint violation.
    private static void toCondition(Map<String, Object> changes) {
        if (!changes.containsKey("condition")) return;
        Object value = changes.get("condition");
        if (value == null || value instanceof String s && s.isBlank()) {
            changes.put("condition", null);
            return;
        }
        String grade = value.toString().trim().toUpperCase();
        try {
            changes.put("condition", HomeCondition.valueOf(grade).name());
        } catch (IllegalArgumentException e) {
            throw InvalidRequest.onField("condition", "home.condition_unknown", value);
        }
    }

    private static Integer readInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        String s = value.toString().trim();
        return s.isEmpty() ? null : Integer.valueOf(s);
    }
}
