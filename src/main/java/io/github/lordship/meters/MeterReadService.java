package io.github.lordship.meters;

import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.meters.internal.MeterReadRepository;
import io.github.lordship.meters.internal.MeterRepository;
import io.github.lordship.meters.internal.MeterSettingRepository;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.Patches;
import io.github.lordship.shared.RuleConflict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads of mains and submeters, and what they say about usage.
 *
 * <p>Staff reads come from the read grid. Provider reads come from a utility
 * bill's printed old and new numbers (see {@link #providerRead}); a bill's old
 * number is usually the last bill's new number, so the same read is found and
 * reused rather than entered twice, and a different number on the same day is
 * refused as a mismatch worth looking at.
 *
 * <p>A read a bill names is part of that bill: its number, day and kind no longer
 * change. So are a main's reads between a bill's old and new read, since the
 * bill's usage adds them up: none is added, changed or removed there. A typo is
 * fixed by taking the main off the bill, correcting the read, and adding it back;
 * a provider's own misread is corrected on its next bill.
 *
 * <p>Writes to one meter's reads take a lock on the meter first, so two people
 * entering the same meter at once wait for each other instead of racing.
 */
@Service
public class MeterReadService {

    // How far back the grid looks before its first day, so a read's usual use per day can be judged
    private static final int HISTORY_DAYS = 400;

    private final MeterReadRepository reads;
    private final MeterRepository meters;
    private final MeterSettingRepository settings;
    private final AuditService auditService;

    public MeterReadService(MeterReadRepository reads, MeterRepository meters, MeterSettingRepository settings,
                            AuditService auditService) {
        this.reads = reads;
        this.meters = meters;
        this.settings = settings;
        this.auditService = auditService;
    }

    // ── Reading ──

    /** One meter's whole history, oldest first. Exactly one of mainId, submeterId. Works for a deleted meter too. */
    public List<ReadInterval> history(UUID mainId, UUID submeterId) {
        requireMeterExists(mainId, submeterId);
        return ReadChain.evaluate(reads.chain(mainId, submeterId, null), settingsOf(mainId, submeterId));
    }

    /**
     * Every meter's reads in a park between two days, for the read grid. Each
     * read's usage counts from the read before it even when that one is before {@code from}.
     */
    public List<MeterReadings> grid(UUID propertyId, LocalDate from, LocalDate to) {
        if (from == null || to == null) throw InvalidRequest.of("value.required");
        if (to.isBefore(from)) throw InvalidRequest.onField("to", "meter_read.range_backwards");

        Map<UUID, List<MeterRead>> byMeter = new LinkedHashMap<>();
        for (MeterRead read : reads.ofProperty(propertyId, from.minusDays(HISTORY_DAYS), to)) {
            UUID key = read.mainId() != null ? read.mainId() : read.submeterId();
            byMeter.computeIfAbsent(key, k -> new ArrayList<>()).add(read);
        }
        Map<UUID, List<MeterSetting>> settingsByMeter = new HashMap<>();
        for (MeterSetting s : settings.ofProperty(propertyId, false)) {
            settingsByMeter.computeIfAbsent(s.mainId() != null ? s.mainId() : s.submeterId(), k -> new ArrayList<>()).add(s);
        }

        List<MeterReadings> out = new ArrayList<>();
        byMeter.forEach((meterId, chain) -> {
            List<MeterSetting> meterSettings = settingsByMeter.get(meterId);
            if (meterSettings == null) return;
            List<ReadInterval> inRange = ReadChain.evaluate(chain, meterSettings).stream()
                    .filter(r -> !r.read().readOn().isBefore(from))
                    .toList();
            if (!inRange.isEmpty()) {
                boolean isMain = chain.getFirst().mainId() != null;
                out.add(new MeterReadings(isMain ? meterId : null, isMain ? null : meterId, inRange));
            }
        });
        return out;
    }

    public Optional<MeterRead> find(UUID uuid) {
        return reads.find(uuid);
    }

    /** A read whether or not it is deleted, for a bill that names it */
    public Optional<MeterRead> findAny(UUID uuid) {
        return reads.findAny(uuid);
    }

    public List<MeterRead> deleted(UUID mainId, UUID submeterId) {
        requireOneMeter(mainId, submeterId);
        return reads.deletedOf(mainId, submeterId);
    }

    /** The property a read's meter is in, deleted or not, for PropertyScope */
    public Optional<UUID> propertyOfRead(UUID readId) {
        return reads.propertyOf(readId);
    }

    /** The property a meter is in, deleted or not. Exactly one of mainId, submeterId. */
    public Optional<UUID> propertyOfMeter(UUID mainId, UUID submeterId) {
        requireOneMeter(mainId, submeterId);
        return mainId != null
                ? meters.findAnyMain(mainId).map(MeterMain::propertyId)
                : meters.findAnySubmeter(submeterId).map(Submeter::propertyId);
    }

    // ── Writing ──

    /** A read taken by staff. Exactly one of mainId, submeterId. */
    @Transactional
    public ReadInterval record(UUID mainId, UUID submeterId, LocalDate readOn, Long reading, MeterReadKind kind,
                               boolean isEstimated, String notes) {
        lockLiveMeter(mainId, submeterId);
        requireReadable(mainId, submeterId, readOn, reading);
        if (reads.onDay(mainId, submeterId, readOn).isPresent()) {
            throw RuleConflict.of("meter_read.day_taken", readOn);
        }
        refuseInsideBill(mainId, readOn);
        MeterRead saved = reads.insert(mainId, submeterId, readOn, reading,
                kind == null ? MeterReadKind.READ : kind, MeterReadSource.STAFF, isEstimated, Patches.text(notes));
        auditService.recordInsert(MeterReadRepository.TABLE, saved.uuid(), AuditMapper.toMap(saved));
        return intervalOf(saved);
    }

    /**
     * A read printed on a provider's bill. The read already on file for that main
     * and day is reused when it shows the same number; a different number is
     * refused (409) so someone can find out which is right. The main is locked
     * until the bill is saved, so the read cannot be deleted in between.
     *
     * <p>A bill counts usage after its old read up to its new read, so the two are
     * treated differently:
     * <ul>
     *   <li>The old read ({@code kind} null) only lends its number. Whatever is on
     *       file for that day stands, a START included: a new main's first bill
     *       starts from the read taken when it was put in.</li>
     *   <li>The new read is READ, or ROLLOVER when the dial passed its top during
     *       the bill. A read on file already marked ROLLOVER stands. One marked
     *       START cannot be a bill's new read (nothing is counted up to it), and a
     *       plain read on file is not turned into a rollover here: correct it first.</li>
     * </ul>
     *
     * @param kind null for the bill's old read; READ or ROLLOVER for its new read
     */
    @Transactional
    public MeterRead providerRead(UUID mainId, LocalDate readOn, Long reading, boolean isEstimated, MeterReadKind kind) {
        if (kind == MeterReadKind.START) throw new IllegalArgumentException("A bill's new read is a READ or a ROLLOVER");
        lockLiveMeter(mainId, null);
        requireReadable(mainId, null, readOn, reading);
        Optional<MeterRead> sameDay = reads.onDay(mainId, null, readOn);
        if (sameDay.isPresent()) {
            MeterRead onFile = sameDay.get();
            if (onFile.reading().longValue() != reading) {
                throw RuleConflict.of("meter_read.provider_differs", readOn, onFile.reading(), reading);
            }
            if (kind != null) {
                if (onFile.kind() == MeterReadKind.START) throw RuleConflict.of("meter_read.provider_kind", readOn);
                if (kind == MeterReadKind.ROLLOVER && onFile.kind() != MeterReadKind.ROLLOVER) {
                    throw RuleConflict.of("meter_read.provider_not_rollover", readOn);
                }
            }
            return onFile;
        }
        refuseInsideBill(mainId, readOn);
        MeterRead saved = reads.insert(mainId, null, readOn, reading, kind == null ? MeterReadKind.READ : kind,
                MeterReadSource.PROVIDER, isEstimated, null);
        auditService.recordInsert(MeterReadRepository.TABLE, saved.uuid(), AuditMapper.toMap(saved));
        return saved;
    }

    /**
     * Corrects a read: a typo, or a read marked READ that was really a rollover.
     * Once a bill names the read, only its notes and estimated flag change.
     */
    @Transactional
    public Optional<ReadInterval> patch(UUID uuid, Map<String, Object> raw) {
        MeterRead peek = reads.find(uuid).orElse(null);
        if (peek == null) return Optional.empty();
        lockLiveMeter(peek.mainId(), peek.submeterId());
        Optional<MeterRead> found = reads.findForUpdate(uuid);
        if (found.isEmpty()) return Optional.empty();
        MeterRead before = found.get();

        Map<String, Object> changes = new HashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String col = e.getKey();
            Object v = e.getValue();
            switch (col) {
                case "read_on" -> {
                    LocalDate day = Patches.date(v, "readOn");
                    if (day == null) throw InvalidRequest.onField("readOn", "value.required");
                    changes.put(col, day);
                }
                case "reading" -> {
                    Long n = Patches.whole(v, "reading");
                    if (n == null) throw InvalidRequest.onField("reading", "value.required");
                    changes.put(col, n);
                }
                case "kind" -> {
                    MeterReadKind kind = Patches.choice(v, MeterReadKind.class, "kind");
                    if (kind == null) throw InvalidRequest.onField("kind", "value.required");
                    changes.put(col, kind);
                }
                case "is_estimated" -> changes.put(col, Boolean.TRUE.equals(Patches.bool(v, "isEstimated")));
                default -> changes.put(col, Patches.text(v));
            }
        }

        LocalDate readOn = (LocalDate) changes.getOrDefault("read_on", before.readOn());
        Long reading = (Long) changes.getOrDefault("reading", before.reading());
        MeterReadKind kind = (MeterReadKind) changes.getOrDefault("kind", before.kind());
        boolean countChanges = !readOn.equals(before.readOn()) || !reading.equals(before.reading()) || kind != before.kind();
        if (countChanges && reads.isOnBill(uuid)) {
            throw RuleConflict.of("meter_read.fixed_by_bill", before.readOn());
        }
        if (countChanges) {
            refuseInsideBill(before.mainId(), before.readOn());
            refuseInsideBill(before.mainId(), readOn);
        }
        requireReadable(before.mainId(), before.submeterId(), readOn, reading);
        if (!readOn.equals(before.readOn())) {
            reads.onDay(before.mainId(), before.submeterId(), readOn)
                    .filter(other -> !other.uuid().equals(uuid))
                    .ifPresent(other -> { throw RuleConflict.of("meter_read.day_taken", readOn); });
        }

        MeterRead after = reads.patch(uuid, changes).orElse(null);
        if (after == null) return Optional.empty();
        MeterService.recordUpdate(auditService, MeterReadRepository.TABLE, uuid, before, after);
        return Optional.of(intervalOf(after));
    }

    // Refused while a bill names it as its old or new number: change the bill first
    @Transactional
    public boolean delete(UUID uuid) {
        MeterRead peek = reads.find(uuid).orElse(null);
        if (peek == null) return false;
        lockAnyMeter(peek.mainId(), peek.submeterId());
        Optional<MeterRead> found = reads.findForUpdate(uuid);
        if (found.isEmpty()) return false;
        if (reads.isOnBill(uuid)) throw RuleConflict.of("meter_read.on_a_bill", found.get().readOn());
        refuseInsideBill(found.get().mainId(), found.get().readOn());
        reads.softDelete(uuid);
        auditService.recordDelete(MeterReadRepository.TABLE, uuid, AuditMapper.toMap(found.get()));
        return true;
    }

    // Refused when the meter is deleted, or another read has since been entered for that meter and day
    @Transactional
    public Optional<ReadInterval> restore(UUID uuid) {
        MeterRead before = reads.findAny(uuid).orElse(null);
        if (before == null) return Optional.empty();
        if (before.deletedAt() != null) {
            lockLiveMeter(before.mainId(), before.submeterId());
            if (reads.onDay(before.mainId(), before.submeterId(), before.readOn()).isPresent()) {
                throw RuleConflict.of("meter_read.day_taken", before.readOn());
            }
            refuseInsideBill(before.mainId(), before.readOn());
            reads.restore(uuid);
            MeterRead after = reads.find(uuid).orElseThrow();
            MeterService.recordUpdate(auditService, MeterReadRepository.TABLE, uuid, before, after);
        }
        return reads.find(uuid).map(this::intervalOf);
    }

    // ── Rules ──

    // A main's read between a bill's old and new read is part of that bill's usage: it stays as it is
    private void refuseInsideBill(UUID mainId, LocalDate day) {
        if (mainId != null && reads.insideBill(mainId, day)) throw RuleConflict.of("meter_read.inside_bill", day);
    }

    private List<MeterSetting> settingsOf(UUID mainId, UUID submeterId) {
        return mainId != null ? settings.ofMain(mainId) : settings.ofSubmeter(submeterId);
    }

    // Locks a live meter for the rest of the transaction, or refuses when it is unknown or deleted
    private void lockLiveMeter(UUID mainId, UUID submeterId) {
        requireOneMeter(mainId, submeterId);
        if (mainId != null) {
            if (meters.findMainForUpdate(mainId).isEmpty()) throw InvalidRequest.onField("mainId", "meter_main.unknown");
        } else if (meters.findSubmeterForUpdate(submeterId).isEmpty()) {
            throw InvalidRequest.onField("submeterId", "submeter.unknown");
        }
    }

    // The same lock, for a delete, which may tidy the reads of a meter that is itself deleted
    private void lockAnyMeter(UUID mainId, UUID submeterId) {
        if (mainId != null) meters.findMainForUpdate(mainId);
        else meters.findSubmeterForUpdate(submeterId);
    }

    private void requireMeterExists(UUID mainId, UUID submeterId) {
        requireOneMeter(mainId, submeterId);
        boolean exists = mainId != null ? meters.findAnyMain(mainId).isPresent() : meters.findAnySubmeter(submeterId).isPresent();
        if (!exists) {
            throw mainId != null ? InvalidRequest.onField("mainId", "meter_main.unknown")
                    : InvalidRequest.onField("submeterId", "submeter.unknown");
        }
    }

    private static void requireOneMeter(UUID mainId, UUID submeterId) {
        if ((mainId == null) == (submeterId == null)) throw InvalidRequest.of("meter_read.one_meter");
    }

    private void requireReadable(UUID mainId, UUID submeterId, LocalDate readOn, Long reading) {
        if (readOn == null) throw InvalidRequest.onField("readOn", "value.required");
        if (reading == null) throw InvalidRequest.onField("reading", "value.required");
        if (readOn.isAfter(LocalDate.now().plusDays(1))) throw InvalidRequest.onField("readOn", "meter_read.in_future");
        if (reading < 0) throw InvalidRequest.onField("reading", "meter_read.negative");
        long dialMax = MeterSetting.inForce(settingsOf(mainId, submeterId), readOn).dialMax();
        if (reading > dialMax) throw InvalidRequest.onField("reading", "meter_read.above_dial", dialMax);
    }

    // The read and its usage since the read before
    private ReadInterval intervalOf(MeterRead read) {
        List<MeterRead> chain = reads.chain(read.mainId(), read.submeterId(), read.readOn().minusDays(HISTORY_DAYS));
        return ReadChain.evaluate(chain, settingsOf(read.mainId(), read.submeterId())).stream()
                .filter(r -> Objects.equals(r.read().uuid(), read.uuid()))
                .findFirst()
                .orElseThrow();
    }
}
