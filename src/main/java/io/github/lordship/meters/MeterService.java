package io.github.lordship.meters;

import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.meters.internal.MeterLinkRepository;
import io.github.lordship.meters.internal.MeterRepository;
import io.github.lordship.meters.internal.MeterSettingRepository;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.Patches;
import io.github.lordship.shared.RuleConflict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A park's meter tree: the mains the utility companies bill for, the park's
 * own submeters, the rate groups, how to read each meter's dial (dated
 * settings), and the dated links main → submeter → lot.
 *
 * <p>Changes arrive as PATCH maps keyed by column name, already narrowed by the
 * controller to what may change. A meter's utility type never changes, because
 * its links, settings and bills depend on it. How its dial is read changes only
 * through a dated setting, so the usage already worked out for earlier days stays.
 */
@Service
public class MeterService {

    private static final long DEFAULT_DIAL_MAX = 99999;

    private final MeterRepository meters;
    private final MeterLinkRepository links;
    private final MeterSettingRepository settings;
    private final AuditService auditService;

    public MeterService(MeterRepository meters, MeterLinkRepository links, MeterSettingRepository settings,
                        AuditService auditService) {
        this.meters = meters;
        this.links = links;
        this.settings = settings;
        this.auditService = auditService;
    }

    // ── Reading the tree ──

    public MeterTree tree(UUID propertyId) {
        return new MeterTree(
                meters.rateGroupsOf(propertyId, false),
                meters.mainsOf(propertyId, false),
                meters.submetersOf(propertyId, false),
                settings.ofProperty(propertyId, false),
                links.mainLinksOfProperty(propertyId, false),
                links.lotLinksOfProperty(propertyId, false));
    }

    /** What has been deleted in a park's tree, for the deleted panel */
    public MeterTree deleted(UUID propertyId) {
        return new MeterTree(
                meters.rateGroupsOf(propertyId, true),
                meters.mainsOf(propertyId, true),
                meters.submetersOf(propertyId, true),
                settings.ofProperty(propertyId, true),
                links.mainLinksOfProperty(propertyId, true),
                links.lotLinksOfProperty(propertyId, true));
    }

    public Optional<MeterMain> findMain(UUID uuid) {
        return meters.findMain(uuid);
    }

    public Optional<Submeter> findSubmeter(UUID uuid) {
        return meters.findSubmeter(uuid);
    }

    /** A main whether or not it is deleted, for records (bills) that still name it */
    public Optional<MeterMain> findAnyMain(UUID uuid) {
        return meters.findAnyMain(uuid);
    }

    /**
     * A live main, locked until the transaction ends: it cannot be deleted while a
     * bill is made to point at it, and two bills naming it are checked one at a time.
     */
    @Transactional
    public Optional<MeterMain> lockMain(UUID uuid) {
        return meters.findMainForUpdate(uuid);
    }

    /** Held the same way: a bill entered under a rate group keeps it from being deleted meanwhile */
    @Transactional
    public Optional<RateGroup> holdRateGroup(UUID uuid) {
        return meters.findRateGroupForShare(uuid);
    }

    // The property each record belongs to, deleted or not, so a controller can check PropertyScope first
    public Optional<UUID> propertyOfMain(UUID uuid) { return meters.findAnyMain(uuid).map(MeterMain::propertyId); }
    public Optional<UUID> propertyOfSubmeter(UUID uuid) { return meters.findAnySubmeter(uuid).map(Submeter::propertyId); }
    public Optional<UUID> propertyOfRateGroup(UUID uuid) { return meters.findAnyRateGroup(uuid).map(RateGroup::propertyId); }
    public Optional<UUID> propertyOfMainLink(UUID uuid) { return links.findAnyMainLink(uuid).map(SubmeterMainLink::propertyId); }
    public Optional<UUID> propertyOfLotLink(UUID uuid) { return links.findAnyLotLink(uuid).map(SubmeterLotLink::propertyId); }
    public Optional<UUID> propertyOfSetting(UUID uuid) { return settings.findAny(uuid).map(MeterSetting::propertyId); }

    // ── Mains ──

    /** A new main, with its first setting (no start day) for reading its dial */
    @Transactional
    public MeterMain createMain(UUID propertyId, MeterType type, MeterUnit unit, String title,
                                BigDecimal multiplier, Long dialMax, UUID rateGroupId,
                                String provider, String accountNumber, String serialNumber) {
        requireUnitSuits(unit, type);
        BigDecimal checkedMultiplier = checkedMultiplier(multiplier);
        long checkedDial = checkedDialMax(dialMax);
        String cleanTitle = Patches.requiredText(title, "title");
        if (rateGroupId != null) requireRateGroupIn(rateGroupId, propertyId);

        MeterMain saved = meters.insertMain(propertyId, type, rateGroupId, cleanTitle,
                Patches.text(provider), Patches.text(accountNumber), Patches.text(serialNumber));
        auditService.recordInsert(MeterRepository.MAIN, saved.uuid(), AuditMapper.toMap(saved));
        insertSetting(saved.uuid(), null, propertyId, type, null, unit, checkedMultiplier, checkedDial, null);
        return saved;
    }

    /**
     * Moving a main to another rate group affects bills entered from now on; a
     * bill keeps the group it was entered under.
     */
    @Transactional
    public Optional<MeterMain> patchMain(UUID uuid, Map<String, Object> raw) {
        Optional<MeterMain> found = meters.findMainForUpdate(uuid);
        if (found.isEmpty()) return Optional.empty();
        MeterMain before = found.get();

        Map<String, Object> changes = new HashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String col = e.getKey();
            Object v = e.getValue();
            switch (col) {
                case "rate_group_id" -> {
                    UUID group = Patches.id(v, "rateGroupId");
                    if (group != null) requireRateGroupIn(group, before.propertyId());
                    changes.put(col, group);
                }
                case "title" -> changes.put(col, Patches.requiredText(v, col));
                case "point_x" -> changes.put(col, Patches.number(v, "pointX"));
                case "point_y" -> changes.put(col, Patches.number(v, "pointY"));
                case "installed_on" -> changes.put(col, Patches.date(v, "installedOn"));
                case "removed_on" -> changes.put(col, Patches.date(v, "removedOn"));
                default -> changes.put(col, Patches.text(v));
            }
        }
        requireInstalledBeforeRemoved(changes, before.installedOn(), before.removedOn());

        MeterMain after = meters.patchMain(uuid, changes).orElse(null);
        if (after == null) return Optional.empty();
        recordUpdate(MeterRepository.MAIN, uuid, before, after);
        return Optional.of(after);
    }

    // Refused while submeters hang off it, or a bill names it: then it is not a mistake to undo
    @Transactional
    public boolean deleteMain(UUID uuid) {
        Optional<MeterMain> found = meters.findMainForUpdate(uuid);
        if (found.isEmpty()) return false;
        if (meters.mainHasOpenLinks(uuid)) throw RuleConflict.of("meter_main.has_submeters", found.get().title());
        if (meters.mainIsOnBills(uuid)) throw RuleConflict.of("meter_main.on_bills", found.get().title());
        meters.softDelete(MeterRepository.MAIN, uuid);
        auditService.recordDelete(MeterRepository.MAIN, uuid, AuditMapper.toMap(found.get()));
        return true;
    }

    @Transactional
    public Optional<MeterMain> restoreMain(UUID uuid) {
        MeterMain before = meters.findAnyMain(uuid).orElse(null);
        if (before == null) return Optional.empty();
        if (before.rateGroupId() != null && meters.findRateGroupForShare(before.rateGroupId()).isEmpty()) {
            throw RuleConflict.of("meter_main.rate_group_deleted", before.title());
        }
        if (meters.restore(MeterRepository.MAIN, uuid)) {
            MeterMain after = meters.findMain(uuid).orElseThrow();
            recordUpdate(MeterRepository.MAIN, uuid, before, after);
            return Optional.of(after);
        }
        return meters.findMain(uuid);
    }

    // ── Submeters ──

    /** A new submeter, with its first setting (no start day) for reading its dial */
    @Transactional
    public Submeter createSubmeter(UUID propertyId, MeterType type, MeterUnit unit, BigDecimal multiplier,
                                   Long dialMax, String title, String serialNumber) {
        requireUnitSuits(unit, type);
        BigDecimal checkedMultiplier = checkedMultiplier(multiplier);
        long checkedDial = checkedDialMax(dialMax);
        Submeter saved = meters.insertSubmeter(propertyId, type, Patches.text(title), Patches.text(serialNumber));
        auditService.recordInsert(MeterRepository.SUBMETER, saved.uuid(), AuditMapper.toMap(saved));
        insertSetting(null, saved.uuid(), propertyId, type, null, unit, checkedMultiplier, checkedDial, null);
        return saved;
    }

    @Transactional
    public Optional<Submeter> patchSubmeter(UUID uuid, Map<String, Object> raw) {
        Optional<Submeter> found = meters.findSubmeterForUpdate(uuid);
        if (found.isEmpty()) return Optional.empty();
        Submeter before = found.get();

        Map<String, Object> changes = new HashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String col = e.getKey();
            Object v = e.getValue();
            switch (col) {
                case "point_x" -> changes.put(col, Patches.number(v, "pointX"));
                case "point_y" -> changes.put(col, Patches.number(v, "pointY"));
                case "installed_on" -> changes.put(col, Patches.date(v, "installedOn"));
                case "removed_on" -> changes.put(col, Patches.date(v, "removedOn"));
                default -> changes.put(col, Patches.text(v));
            }
        }
        requireInstalledBeforeRemoved(changes, before.installedOn(), before.removedOn());

        Submeter after = meters.patchSubmeter(uuid, changes).orElse(null);
        if (after == null) return Optional.empty();
        recordUpdate(MeterRepository.SUBMETER, uuid, before, after);
        return Optional.of(after);
    }

    // Refused while it is still linked to a main or a lot; end or delete those links first
    @Transactional
    public boolean deleteSubmeter(UUID uuid) {
        Optional<Submeter> found = meters.findSubmeterForUpdate(uuid);
        if (found.isEmpty()) return false;
        if (meters.submeterHasOpenLinks(uuid)) throw RuleConflict.of("submeter.has_links");
        meters.softDelete(MeterRepository.SUBMETER, uuid);
        auditService.recordDelete(MeterRepository.SUBMETER, uuid, AuditMapper.toMap(found.get()));
        return true;
    }

    @Transactional
    public Optional<Submeter> restoreSubmeter(UUID uuid) {
        Submeter before = meters.findAnySubmeter(uuid).orElse(null);
        if (before == null) return Optional.empty();
        if (meters.restore(MeterRepository.SUBMETER, uuid)) {
            Submeter after = meters.findSubmeter(uuid).orElseThrow();
            recordUpdate(MeterRepository.SUBMETER, uuid, before, after);
            return Optional.of(after);
        }
        return meters.findSubmeter(uuid);
    }

    // ── Settings: how to read a meter's dial, from a day on ──

    /**
     * Reads a meter's dial a new way from a day on: a correction (the dial was
     * always 6 digits), or a change (a new register). Reads before that day keep
     * their usage. Unit, multiplier or dial left out stay as on that day. Refused
     * when a bill's usage would change: one that counts reads from that day until
     * the next dated setting starts.
     */
    @Transactional
    public MeterSetting addSetting(UUID mainId, UUID submeterId, LocalDate effectiveFrom, MeterUnit unit,
                                   BigDecimal multiplier, Long dialMax, String notes) {
        if ((mainId == null) == (submeterId == null)) throw InvalidRequest.of("meter_setting.one_meter");
        if (effectiveFrom == null) throw InvalidRequest.onField("effectiveFrom", "value.required");
        UUID propertyId;
        MeterType type;
        if (mainId != null) {
            MeterMain main = meters.findMainForUpdate(mainId)
                    .orElseThrow(() -> InvalidRequest.onField("mainId", "meter_main.unknown"));
            propertyId = main.propertyId();
            type = main.utilityType();
        } else {
            Submeter sub = meters.findSubmeterForUpdate(submeterId)
                    .orElseThrow(() -> InvalidRequest.onField("submeterId", "submeter.unknown"));
            propertyId = sub.propertyId();
            type = sub.utilityType();
        }
        List<MeterSetting> existing = settingsOf(mainId, submeterId);
        if (existing.stream().anyMatch(s -> effectiveFrom.equals(s.effectiveFrom()))) {
            throw RuleConflict.of("meter_setting.day_taken", effectiveFrom);
        }
        // What is left out stays as it was on that day: changing only the dial keeps the multiplier
        MeterSetting current = MeterSetting.inForce(existing, effectiveFrom);
        MeterUnit newUnit = unit != null ? unit : current.unit();
        requireUnitSuits(newUnit, type);
        BigDecimal newMultiplier = multiplier != null ? checkedMultiplier(multiplier) : current.multiplier();
        long newDial = dialMax != null ? checkedDialMax(dialMax) : current.dialMax();
        // It reads the dial only until the next dated setting starts; bills past that day are not touched
        refuseIfBilled(mainId, effectiveFrom, firstStartAfter(existing, effectiveFrom, null));
        return insertSetting(mainId, submeterId, propertyId, type, effectiveFrom, newUnit,
                newMultiplier, newDial, Patches.text(notes));
    }

    /**
     * Corrects a setting. Refused when a bill names a read in the days the setting
     * covers now, or would cover after the change.
     */
    @Transactional
    public Optional<MeterSetting> patchSetting(UUID uuid, Map<String, Object> raw) {
        MeterSetting peek = settings.findAny(uuid).filter(s -> s.deletedAt() == null).orElse(null);
        if (peek == null) return Optional.empty();
        lockMeterOf(peek);
        Optional<MeterSetting> found = settings.findForUpdate(uuid);
        if (found.isEmpty()) return Optional.empty();
        MeterSetting before = found.get();

        Map<String, Object> changes = new HashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String col = e.getKey();
            Object v = e.getValue();
            switch (col) {
                case "effective_from" -> {
                    if (before.effectiveFrom() == null) throw InvalidRequest.onField("effectiveFrom", "meter_setting.first_has_no_day");
                    LocalDate day = Patches.date(v, "effectiveFrom");
                    if (day == null) throw InvalidRequest.onField("effectiveFrom", "value.required");
                    changes.put(col, day);
                }
                case "unit" -> {
                    MeterUnit unit = Patches.choice(v, MeterUnit.class, "unit");
                    requireUnitSuits(unit, before.utilityType());
                    changes.put(col, unit);
                }
                case "multiplier" -> changes.put(col, checkedMultiplier(requiredDecimal(v, "multiplier")));
                case "dial_max" -> changes.put(col, checkedDialMax(requiredWhole(v, "dialMax")));
                default -> changes.put(col, Patches.text(v));
            }
        }
        boolean readingChanges = changes.keySet().stream().anyMatch(c -> !c.equals("notes"));
        if (readingChanges) {
            List<MeterSetting> all = settingsOf(before.mainId(), before.submeterId());
            refuseIfBilled(before.mainId(), before.effectiveFrom(), nextStart(all, before));
            if (changes.containsKey("effective_from")) {
                LocalDate moved = (LocalDate) changes.get("effective_from");
                if (all.stream().anyMatch(s -> !s.uuid().equals(uuid) && moved.equals(s.effectiveFrom()))) {
                    throw RuleConflict.of("meter_setting.day_taken", moved);
                }
                refuseIfBilled(before.mainId(), moved, firstStartAfter(all, moved, uuid));
            }
        }

        MeterSetting after = settings.patch(uuid, changes).orElse(null);
        if (after == null) return Optional.empty();
        recordUpdate(MeterSettingRepository.TABLE, uuid, before, after);
        return Optional.of(after);
    }

    // The first setting cannot go: a meter always needs one. Others go only when no bill relies on them.
    @Transactional
    public boolean deleteSetting(UUID uuid) {
        MeterSetting peek = settings.findAny(uuid).filter(s -> s.deletedAt() == null).orElse(null);
        if (peek == null) return false;
        lockMeterOf(peek);
        Optional<MeterSetting> found = settings.findForUpdate(uuid);
        if (found.isEmpty()) return false;
        MeterSetting setting = found.get();
        if (setting.effectiveFrom() == null) throw RuleConflict.of("meter_setting.first_stays");
        refuseIfBilled(setting.mainId(), setting.effectiveFrom(),
                nextStart(settingsOf(setting.mainId(), setting.submeterId()), setting));
        settings.softDelete(uuid);
        auditService.recordDelete(MeterSettingRepository.TABLE, uuid, AuditMapper.toMap(setting));
        return true;
    }

    @Transactional
    public Optional<MeterSetting> restoreSetting(UUID uuid) {
        MeterSetting before = settings.findAny(uuid).orElse(null);
        if (before == null) return Optional.empty();
        if (before.deletedAt() != null) {
            lockMeterOf(before);
            List<MeterSetting> live = settingsOf(before.mainId(), before.submeterId());
            if (live.stream().anyMatch(s -> java.util.Objects.equals(s.effectiveFrom(), before.effectiveFrom()))) {
                throw RuleConflict.of("meter_setting.day_taken", before.effectiveFrom());
            }
            refuseIfBilled(before.mainId(), before.effectiveFrom(), nextStart(live, before));
            settings.restore(uuid);
            recordUpdate(MeterSettingRepository.TABLE, uuid, before, settings.findAny(uuid).orElseThrow());
        }
        return settings.findAny(uuid).filter(s -> s.deletedAt() == null);
    }

    /** One meter's live settings, the undated one first. Exactly one of mainId, submeterId. */
    public List<MeterSetting> settingsOf(UUID mainId, UUID submeterId) {
        return mainId != null ? settings.ofMain(mainId) : settings.ofSubmeter(submeterId);
    }

    // ── Rate groups ──

    @Transactional
    public RateGroup createRateGroup(UUID propertyId, String name, String notes) {
        String cleanName = Patches.requiredText(name, "name");
        if (meters.rateGroupNameTaken(propertyId, cleanName, null)) throw RuleConflict.of("rate_group.name_taken", cleanName);
        RateGroup saved = meters.insertRateGroup(propertyId, cleanName, Patches.text(notes));
        auditService.recordInsert(MeterRepository.RATE_GROUP, saved.uuid(), AuditMapper.toMap(saved));
        return saved;
    }

    @Transactional
    public Optional<RateGroup> patchRateGroup(UUID uuid, Map<String, Object> raw) {
        Optional<RateGroup> found = meters.findRateGroupForUpdate(uuid);
        if (found.isEmpty()) return Optional.empty();
        RateGroup before = found.get();

        Map<String, Object> changes = new HashMap<>();
        if (raw.containsKey("name")) {
            String name = Patches.requiredText(raw.get("name"), "name");
            if (meters.rateGroupNameTaken(before.propertyId(), name, uuid)) throw RuleConflict.of("rate_group.name_taken", name);
            changes.put("name", name);
        }
        if (raw.containsKey("notes")) changes.put("notes", Patches.text(raw.get("notes")));

        RateGroup after = meters.patchRateGroup(uuid, changes).orElse(null);
        if (after == null) return Optional.empty();
        recordUpdate(MeterRepository.RATE_GROUP, uuid, before, after);
        return Optional.of(after);
    }

    // Refused while mains are in it, bills were entered under it, or it has a cycle of its own
    @Transactional
    public boolean deleteRateGroup(UUID uuid) {
        Optional<RateGroup> found = meters.findRateGroupForUpdate(uuid);
        if (found.isEmpty()) return false;
        if (meters.rateGroupHasMains(uuid)) throw RuleConflict.of("rate_group.has_mains", found.get().name());
        if (meters.rateGroupHasBills(uuid)) throw RuleConflict.of("rate_group.has_bills", found.get().name());
        if (meters.rateGroupHasCycles(uuid)) throw RuleConflict.of("rate_group.has_cycles", found.get().name());
        meters.softDelete(MeterRepository.RATE_GROUP, uuid);
        auditService.recordDelete(MeterRepository.RATE_GROUP, uuid, AuditMapper.toMap(found.get()));
        return true;
    }

    @Transactional
    public Optional<RateGroup> restoreRateGroup(UUID uuid) {
        RateGroup before = meters.findAnyRateGroup(uuid).orElse(null);
        if (before == null) return Optional.empty();
        if (before.deletedAt() != null && meters.rateGroupNameTaken(before.propertyId(), before.name(), uuid)) {
            throw RuleConflict.of("rate_group.name_taken", before.name());
        }
        if (meters.restore(MeterRepository.RATE_GROUP, uuid)) {
            RateGroup after = meters.findRateGroup(uuid).orElseThrow();
            recordUpdate(MeterRepository.RATE_GROUP, uuid, before, after);
            return Optional.of(after);
        }
        return meters.findRateGroup(uuid);
    }

    // ── Links ──

    /**
     * Hangs a submeter off a main from a day on. A submeter already under
     * another main that day is moved: its open link ends the day this one starts.
     * A link that starts later than this day is in the way (409).
     */
    @Transactional
    public SubmeterMainLink linkToMain(UUID submeterId, UUID mainId, LocalDate from) {
        Submeter submeter = meters.findSubmeterForUpdate(submeterId)
                .orElseThrow(() -> InvalidRequest.onField("submeterId", "submeter.unknown"));
        MeterMain main = meters.findMainForShare(mainId)
                .orElseThrow(() -> InvalidRequest.onField("mainId", "meter_main.unknown"));
        if (from == null) throw InvalidRequest.onField("effectiveFrom", "value.required");
        // A main in another park is answered as if it did not exist, so its name never leaks out of scope
        if (!main.propertyId().equals(submeter.propertyId())) {
            throw InvalidRequest.onField("mainId", "meter_main.unknown");
        }
        if (main.utilityType() != submeter.utilityType()) {
            throw InvalidRequest.onField("mainId", "submeter.main_mismatch", main.title());
        }

        for (SubmeterMainLink existing : links.mainLinksOf(submeterId)) {
            boolean endsAfter = existing.effectiveTo() == null || existing.effectiveTo().isAfter(from);
            if (!endsAfter) continue;
            if (existing.mainId().equals(mainId) && existing.effectiveTo() == null
                    && !existing.effectiveFrom().isAfter(from)) {
                return existing; // already under this main: sending it twice changes nothing
            }
            if (!existing.effectiveFrom().isBefore(from)) {
                throw RuleConflict.of("submeter.main_overlap", existing.effectiveFrom());
            }
            SubmeterMainLink ended = links.setMainLinkDates(existing.uuid(), existing.effectiveFrom(), from).orElseThrow();
            recordUpdate(MeterLinkRepository.MAIN_LINK, ended.uuid(), existing, ended);
        }

        SubmeterMainLink saved = links.insertMainLink(submeterId, mainId, submeter.propertyId(), submeter.utilityType(), from);
        auditService.recordInsert(MeterLinkRepository.MAIN_LINK, saved.uuid(), AuditMapper.toMap(saved));
        return saved;
    }

    /**
     * A submeter feeds a lot from a day on. Two lots can share one submeter, but a
     * lot has one submeter per utility at a time: a lot already fed by another
     * submeter of this utility is moved (its open link ends the day this one starts).
     */
    @Transactional
    public SubmeterLotLink linkToLot(UUID submeterId, UUID lotId, LocalDate from) {
        Submeter submeter = meters.findSubmeterForUpdate(submeterId)
                .orElseThrow(() -> InvalidRequest.onField("submeterId", "submeter.unknown"));
        if (from == null) throw InvalidRequest.onField("effectiveFrom", "value.required");
        if (!meters.lotIsInProperty(lotId, submeter.propertyId())) {
            throw InvalidRequest.onField("lotId", "submeter.lot_not_in_property");
        }
        for (SubmeterLotLink existing : links.lotLinksOfLot(lotId, submeter.utilityType())) {
            boolean endsAfter = existing.effectiveTo() == null || existing.effectiveTo().isAfter(from);
            if (!endsAfter) continue;
            if (existing.submeterId().equals(submeterId) && existing.effectiveTo() == null
                    && !existing.effectiveFrom().isAfter(from)) {
                return existing; // already feeds this lot
            }
            if (!existing.effectiveFrom().isBefore(from)) {
                throw RuleConflict.of("submeter.lot_overlap", existing.effectiveFrom());
            }
            SubmeterLotLink ended = links.setLotLinkDates(existing.uuid(), existing.effectiveFrom(), from).orElseThrow();
            recordUpdate(MeterLinkRepository.LOT_LINK, ended.uuid(), existing, ended);
        }
        SubmeterLotLink saved = links.insertLotLink(submeterId, lotId, submeter.propertyId(), submeter.utilityType(), from);
        auditService.recordInsert(MeterLinkRepository.LOT_LINK, saved.uuid(), AuditMapper.toMap(saved));
        return saved;
    }

    /** Changes when a link starts or ends. effective_to is the first day it no longer holds; null reopens it. */
    @Transactional
    public Optional<SubmeterMainLink> patchMainLink(UUID uuid, Map<String, Object> raw) {
        // The submeter first, then the link: the order linkToMain takes them in
        links.findAnyMainLink(uuid).ifPresent(l -> meters.findSubmeterForUpdate(l.submeterId()));
        Optional<SubmeterMainLink> found = links.findMainLink(uuid);
        if (found.isEmpty()) return Optional.empty();
        SubmeterMainLink before = found.get();
        LocalDate from = raw.containsKey("effective_from") ? Patches.date(raw.get("effective_from"), "effectiveFrom") : before.effectiveFrom();
        LocalDate to = raw.containsKey("effective_to") ? Patches.date(raw.get("effective_to"), "effectiveTo") : before.effectiveTo();
        requireDates(from, to);
        if (to == null) requireMainLinkEndsLive(before);
        SubmeterMainLink after = links.setMainLinkDates(uuid, from, to).orElse(null);
        if (after == null) return Optional.empty();
        recordUpdate(MeterLinkRepository.MAIN_LINK, uuid, before, after);
        return Optional.of(after);
    }

    @Transactional
    public Optional<SubmeterLotLink> patchLotLink(UUID uuid, Map<String, Object> raw) {
        links.findAnyLotLink(uuid).ifPresent(l -> meters.findSubmeterForUpdate(l.submeterId()));
        Optional<SubmeterLotLink> found = links.findLotLink(uuid);
        if (found.isEmpty()) return Optional.empty();
        SubmeterLotLink before = found.get();
        LocalDate from = raw.containsKey("effective_from") ? Patches.date(raw.get("effective_from"), "effectiveFrom") : before.effectiveFrom();
        LocalDate to = raw.containsKey("effective_to") ? Patches.date(raw.get("effective_to"), "effectiveTo") : before.effectiveTo();
        requireDates(from, to);
        if (to == null) requireLotLinkEndsLive(before);
        SubmeterLotLink after = links.setLotLinkDates(uuid, from, to).orElse(null);
        if (after == null) return Optional.empty();
        recordUpdate(MeterLinkRepository.LOT_LINK, uuid, before, after);
        return Optional.of(after);
    }

    // Deleting a link is for one made by mistake; ending it (effective_to) is for a real change
    @Transactional
    public boolean deleteMainLink(UUID uuid) {
        links.findAnyMainLink(uuid).ifPresent(l -> meters.findSubmeterForUpdate(l.submeterId()));
        Optional<SubmeterMainLink> found = links.findMainLink(uuid);
        if (found.isEmpty() || !links.softDelete(MeterLinkRepository.MAIN_LINK, uuid)) return false;
        auditService.recordDelete(MeterLinkRepository.MAIN_LINK, uuid, AuditMapper.toMap(found.get()));
        return true;
    }

    @Transactional
    public boolean deleteLotLink(UUID uuid) {
        links.findAnyLotLink(uuid).ifPresent(l -> meters.findSubmeterForUpdate(l.submeterId()));
        Optional<SubmeterLotLink> found = links.findLotLink(uuid);
        if (found.isEmpty() || !links.softDelete(MeterLinkRepository.LOT_LINK, uuid)) return false;
        auditService.recordDelete(MeterLinkRepository.LOT_LINK, uuid, AuditMapper.toMap(found.get()));
        return true;
    }

    // Refused when either end has been deleted; a restore that overlaps a live link is refused by the database (409)
    @Transactional
    public Optional<SubmeterMainLink> restoreMainLink(UUID uuid) {
        SubmeterMainLink before = links.findAnyMainLink(uuid).orElse(null);
        if (before == null) return Optional.empty();
        if (before.deletedAt() != null) {
            requireMainLinkEndsLive(before);
            links.restore(MeterLinkRepository.MAIN_LINK, uuid);
            recordUpdate(MeterLinkRepository.MAIN_LINK, uuid, before, links.findAnyMainLink(uuid).orElseThrow());
        }
        return links.findAnyMainLink(uuid).filter(l -> l.deletedAt() == null);
    }

    @Transactional
    public Optional<SubmeterLotLink> restoreLotLink(UUID uuid) {
        SubmeterLotLink before = links.findAnyLotLink(uuid).orElse(null);
        if (before == null) return Optional.empty();
        if (before.deletedAt() != null) {
            requireLotLinkEndsLive(before);
            links.restore(MeterLinkRepository.LOT_LINK, uuid);
            recordUpdate(MeterLinkRepository.LOT_LINK, uuid, before, links.findAnyLotLink(uuid).orElseThrow());
        }
        return links.findAnyLotLink(uuid).filter(l -> l.deletedAt() == null);
    }

    // ── Rules ──

    private MeterSetting insertSetting(UUID mainId, UUID submeterId, UUID propertyId, MeterType type, LocalDate from,
                                       MeterUnit unit, BigDecimal multiplier, long dialMax, String notes) {
        MeterSetting saved = settings.insert(mainId, submeterId, propertyId, type, from, unit, multiplier, dialMax, notes);
        auditService.recordInsert(MeterSettingRepository.TABLE, saved.uuid(), AuditMapper.toMap(saved));
        return saved;
    }

    // A setting's meter, locked: settings and reads of one meter change one at a time
    private void lockMeterOf(MeterSetting setting) {
        boolean live = setting.mainId() != null
                ? meters.findMainForUpdate(setting.mainId()).isPresent()
                : meters.findSubmeterForUpdate(setting.submeterId()).isPresent();
        if (!live) throw RuleConflict.of("meter_setting.meter_deleted");
    }

    // The first day after this setting that another live setting starts, or null when it runs on
    private static LocalDate nextStart(List<MeterSetting> all, MeterSetting setting) {
        return firstStartAfter(all, setting.effectiveFrom(), setting.uuid());
    }

    // The first day after a day (null: the very start) that a live setting other than one starts, or null when none does
    private static LocalDate firstStartAfter(List<MeterSetting> all, LocalDate day, UUID except) {
        return all.stream()
                .filter(s -> !s.uuid().equals(except) && s.effectiveFrom() != null)
                .filter(s -> day == null || s.effectiveFrom().isAfter(day))
                .map(MeterSetting::effectiveFrom)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    // A main's usage on a bill must not move: refuse when a bill names one of its reads in [from, until)
    private void refuseIfBilled(UUID mainId, LocalDate from, LocalDate until) {
        if (mainId != null && settings.billedReadBetween(mainId, from, until)) {
            throw RuleConflict.of("meter_setting.covers_billed_reads");
        }
    }

    private void requireMainLinkEndsLive(SubmeterMainLink link) {
        if (meters.findSubmeterForUpdate(link.submeterId()).isEmpty() || meters.findMainForShare(link.mainId()).isEmpty()) {
            throw RuleConflict.of("meter_link.end_deleted");
        }
    }

    private void requireLotLinkEndsLive(SubmeterLotLink link) {
        if (meters.findSubmeterForUpdate(link.submeterId()).isEmpty() || !meters.lotIsInProperty(link.lotId(), link.propertyId())) {
            throw RuleConflict.of("meter_link.end_deleted");
        }
    }

    private static void requireUnitSuits(MeterUnit unit, MeterType type) {
        if (unit == null || type == null) throw InvalidRequest.onField("unit", "value.required");
        if (!unit.suits(type)) throw InvalidRequest.onField("unit", "meter.unit_does_not_suit", unit, type);
    }

    private static BigDecimal checkedMultiplier(BigDecimal multiplier) {
        if (multiplier == null) return BigDecimal.ONE;
        if (multiplier.signum() <= 0) throw InvalidRequest.onField("multiplier", "meter.multiplier_not_positive");
        return Patches.fits(multiplier, 16, 6, "multiplier");
    }

    private static long checkedDialMax(Long dialMax) {
        if (dialMax == null) return DEFAULT_DIAL_MAX;
        if (dialMax <= 0) throw InvalidRequest.onField("dialMax", "meter.dial_max_not_positive");
        return dialMax;
    }

    // A setting's dial_max may not be cleared (a create may leave it out for the default)
    private static Long requiredWhole(Object raw, String field) {
        Long n = Patches.whole(raw, field);
        if (n == null) throw InvalidRequest.onField(field, "value.required");
        return n;
    }

    private static BigDecimal requiredDecimal(Object raw, String field) {
        BigDecimal d = Patches.decimal(raw, field);
        if (d == null) throw InvalidRequest.onField(field, "value.required");
        return d;
    }

    private void requireRateGroupIn(UUID rateGroupId, UUID propertyId) {
        RateGroup group = meters.findRateGroupForShare(rateGroupId)
                .orElseThrow(() -> InvalidRequest.onField("rateGroupId", "rate_group.unknown"));
        if (!group.propertyId().equals(propertyId)) throw InvalidRequest.onField("rateGroupId", "rate_group.unknown");
    }

    private static void requireInstalledBeforeRemoved(Map<String, Object> changes, LocalDate installed, LocalDate removed) {
        LocalDate in = changes.containsKey("installed_on") ? (LocalDate) changes.get("installed_on") : installed;
        LocalDate out = changes.containsKey("removed_on") ? (LocalDate) changes.get("removed_on") : removed;
        if (in != null && out != null && out.isBefore(in)) throw InvalidRequest.onField("removedOn", "meter.removed_before_installed");
    }

    private static void requireDates(LocalDate from, LocalDate to) {
        if (from == null) throw InvalidRequest.onField("effectiveFrom", "value.required");
        if (to != null && !to.isAfter(from)) throw InvalidRequest.onField("effectiveTo", "meter_link.ends_before_it_starts");
    }

    // updated_at moves on every save, so it alone is not a change worth a history entry
    static void recordUpdate(AuditService auditService, String table, UUID uuid, Record before, Record after) {
        var diff = AuditMapper.diff(before, after);
        diff.before().remove("updatedAt");
        diff.after().remove("updatedAt");
        if (!diff.before().isEmpty()) auditService.recordUpdate(table, uuid, diff.before(), diff.after());
    }

    private void recordUpdate(String table, UUID uuid, Record before, Record after) {
        recordUpdate(auditService, table, uuid, before, after);
    }
}
