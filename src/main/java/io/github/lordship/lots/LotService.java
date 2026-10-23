package io.github.lordship.lots;

import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.lots.internal.*;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.termstemplate.TermsTemplate;
import io.github.lordship.termstemplate.TermsTemplateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class LotService {

    private final LotRepository lotRepository;
    private final LotPermissibleAgreementTypeRepository lotPermissibleAgreementTypeRepository;
    private final TermsTemplateService termsTemplateService;
    private final AuditService auditService;

    public LotService(LotRepository lotRepository,
                      LotPermissibleAgreementTypeRepository lotPermissibleAgreementTypeRepository,
                      TermsTemplateService termsTemplateService,
                      AuditService auditService) {
        this.lotRepository = lotRepository;
        this.lotPermissibleAgreementTypeRepository = lotPermissibleAgreementTypeRepository;
        this.termsTemplateService = termsTemplateService;
        this.auditService = auditService;
    }

    // A new lot starts out as land, priced from the property's land template.
    // If the park never took a land agreement on there is nothing to seed from,
    // and whoever is doing setup picks the type by hand afterwards.
    @Transactional
    public Lot createLot(UUID propertyId, String lotNumber) {
        LotRow saved = lotRepository.save(propertyId, lotNumber);
        auditService.recordInsert("lot", saved.uuid(), AuditMapper.toMap(saved));

        termsTemplateService.findForProperty(propertyId, AgreementType.LAND)
                .ifPresent(template -> savePermissible(saved.uuid(), AgreementType.LAND,
                        template.targetRate(), template.askingRate()));

        return hydrate(saved);
    }

    @Transactional
    public Optional<Lot> patchLot(UUID uuid, Map<String, Object> changes) {
        Optional<LotRow> beforeOpt = lotRepository.findById(uuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        LotRow before = beforeOpt.get();

        boolean rentableAfter = changes.containsKey("is_rentable")
                ? Boolean.TRUE.equals(changes.get("is_rentable"))
                : Boolean.TRUE.equals(before.isRentable());

        if (rentableAfter) {
            // A rentable lot cannot carry a reason -- clear whatever is there.
            if (before.notRentableReason() != null || changes.containsKey("not_rentable_reason")) {
                changes.put("not_rentable_reason", null);
            }
        } else {
            Object supplied = changes.containsKey("not_rentable_reason")
                    ? changes.get("not_rentable_reason")
                    : before.notRentableReason();
            String reasonAfter = (supplied == null) ? null : supplied.toString();
            if (reasonAfter == null || reasonAfter.isBlank()) {
                throw new IllegalArgumentException("notRentableReason is required when a lot is not rentable");
            }
            changes.put("not_rentable_reason", reasonAfter);
        }

        Optional<LotRow> afterOpt = lotRepository.patch(uuid, changes);
        if (afterOpt.isEmpty()) {
            return Optional.empty();
        }
        LotRow after = afterOpt.get();

        var diff = AuditMapper.diff(before, after);
        if (!diff.before().isEmpty()) {
            auditService.recordUpdate("lot", uuid, diff.before(), diff.after());
        }

        return Optional.of(hydrate(after));
    }

    /**
     * Saves where a lot is drawn on the park's plat drawing, in drawing pixels.
     *
     * <p>No audit entry here. The map builder calls this for every lot it saves,
     * and records one entry for the whole map instead of one per lot.
     */
    @Transactional
    public boolean placeShape(UUID lotId, ShapeData shape) {
        return lotRepository.saveShapeData(lotId, shape);
    }

    @Transactional
    public boolean deleteLot(UUID uuid) {
        return lotRepository.findById(uuid).map(existing -> {
            if (!lotRepository.softDelete(uuid)) {
                return false;
            }
            // The permissible types stay: they describe what the space could
            // host, which is still true of a lot nobody is renting today.
            auditService.recordDelete("lot", uuid, AuditMapper.toMap(existing));
            return true;
        }).orElse(false);
    }

    public Optional<Lot> findById(UUID uuid) {
        return lotRepository.findById(uuid).map(this::hydrate);
    }

    public List<Lot> findByProperty(String propertyCode) {
        return hydrate(lotRepository.findByProperty(propertyCode));
    }

    // The map or list feed for one kind of deal: only the spaces that can host
    // it. Whether the selection that follows is a box drag or a column of
    // checkboxes is the front end's business, not this method's.
    public List<Lot> findByPropertyPermitting(String propertyCode, AgreementType agreementType) {
        return findByProperty(propertyCode).stream()
                .filter(lot -> lot.permits(agreementType))
                .toList();
    }

    public List<Lot> findDuplicateNumbers(String propertyCode) {
        return hydrate(lotRepository.findDuplicateNumbers(propertyCode));
    }

    /**
     * Lets this lot host a kind of agreement, at a pair of rates. Either rate
     * left null falls back to the property's template figure for that type.
     *
     * <p>The park has to offer the agreement type before a space in it can, so
     * this is refused when no terms template was ever copied into the property.
     * {@code is_rentable} is deliberately NOT consulted: a lot can be out of
     * service for any number of reasons without that changing what it could
     * ever host. Whether a lot can take a new tenancy today is a question for
     * tenancy creation.
     */
    @Transactional
    public Optional<Lot> permitAgreementType(UUID lotUuid, AgreementType agreementType,
                                             BigDecimal targetRate, BigDecimal askingRate) {
        Optional<LotRow> lotOpt = lotRepository.findById(lotUuid);
        if (lotOpt.isEmpty()) {
            return Optional.empty();
        }
        LotRow lot = lotOpt.get();

        TermsTemplate template = termsTemplateService
                .findForProperty(lot.propertyId(), agreementType)
                .orElseThrow(() -> new IllegalStateException(
                        "This property has no terms template for " + agreementType
                                + ", so no lot in it can host that kind of agreement"));

        BigDecimal target = (targetRate != null) ? targetRate : template.targetRate();
        BigDecimal asking = (askingRate != null) ? askingRate : template.askingRate();
        requireNotNegative(target, "targetRate");
        requireNotNegative(asking, "askingRate");

        savePermissible(lotUuid, agreementType, target, asking);
        return Optional.of(hydrate(lot));
    }

    // Governs new agreements only. A charge term already signed and served is
    // not invalidated by someone editing a setup screen afterwards.
    @Transactional
    public Optional<Lot> revokeAgreementType(UUID lotUuid, AgreementType agreementType) {
        Optional<LotRow> lotOpt = lotRepository.findById(lotUuid);
        if (lotOpt.isEmpty()) {
            return Optional.empty();
        }
        LotRow lot = lotOpt.get();

        Optional<LotPermissibleAgreementTypeRow> existing = permissibleRow(lotUuid, agreementType);
        if (existing.isPresent() && lotPermissibleAgreementTypeRepository.delete(lotUuid, agreementType)) {
            auditService.recordDelete("lot_permissible_agreement_type",
                    existing.get().uuid(), AuditMapper.toMap(existing.get()));
        }

        return Optional.of(hydrate(lot));
    }

    /**
     * One or both rates across a selection of lots, for one agreement type.
     * This is the owner's pricing pass: filter the park to a kind of deal,
     * select the lots that should share a figure, type it once. Passing both
     * lets "existing 650, new 725" be a single pass over one selection.
     *
     * <p>Only lots that already permit the type are touched -- the filter that
     * produced the selection guaranteed that, so a lot appearing here that does
     * not permit it means the selection is stale, not that a row should be
     * created. A rate left null is left alone rather than cleared. Returns how
     * many lots actually changed.
     */
    @Transactional
    public int setRates(Collection<UUID> lotIds, AgreementType agreementType,
                        BigDecimal targetRate, BigDecimal askingRate) {
        if (targetRate == null && askingRate == null) {
            throw new IllegalArgumentException("Supply a targetRate, an askingRate, or both");
        }
        requireNotNegative(targetRate, "targetRate");
        requireNotNegative(askingRate, "askingRate");
        if (lotIds.isEmpty()) {
            return 0;
        }

        List<LotPermissibleAgreementTypeRow> before = lotPermissibleAgreementTypeRepository
                .findByLotIds(lotIds).stream()
                .filter(row -> row.agreementType() == agreementType)
                .toList();

        lotPermissibleAgreementTypeRepository.updateRates(lotIds, agreementType, targetRate, askingRate);

        int changed = 0;
        for (LotPermissibleAgreementTypeRow row : before) {
            // Mirrors the COALESCE in the UPDATE, so the diff reflects what the
            // database actually did rather than what was asked for.
            LotPermissibleAgreementTypeRow after = new LotPermissibleAgreementTypeRow(
                    row.uuid(), row.lotId(), agreementType,
                    (targetRate != null) ? targetRate : row.targetRate(),
                    (askingRate != null) ? askingRate : row.askingRate());

            AuditMapper.Diff diff = AuditMapper.diff(row, after);
            if (!diff.before().isEmpty()) {
                auditService.recordUpdate(
                        "lot_permissible_agreement_type", row.uuid(), diff.before(), diff.after());
                changed++;
            }
        }
        return changed;
    }

    // ---- internals -----------------------------------------------------------

    // One place a Lot gets built. Every read and every write returns through
    // here, so a lot can never report a permissible-type list that is merely
    // whatever the caller happened to have handy.
    private Lot hydrate(LotRow row) {
        return row.toLot(lotPermissibleAgreementTypeRepository.findByLotId(row.uuid()));
    }

    private List<Lot> hydrate(List<LotRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> lotIds = rows.stream().map(LotRow::uuid).toList();
        Map<UUID, List<LotPermissibleAgreementTypeRow>> agreementTypesByLot =
                lotPermissibleAgreementTypeRepository.findByLotIds(lotIds).stream()
                        .collect(Collectors.groupingBy(LotPermissibleAgreementTypeRow::lotId));

        return rows.stream()
                .map(row -> row.toLot(agreementTypesByLot.getOrDefault(row.uuid(), List.of())))
                .toList();
    }

    private static void requireNotNegative(BigDecimal rate, String field) {
        if (rate != null && rate.signum() < 0) {
            throw new IllegalArgumentException(field + " cannot be negative");
        }
    }

    private Optional<LotPermissibleAgreementTypeRow> permissibleRow(UUID lotId, AgreementType agreementType) {
        return lotPermissibleAgreementTypeRepository.findByLotId(lotId).stream()
                .filter(row -> row.agreementType() == agreementType)
                .findFirst();
    }

    // The repository upserts, so which audit entry to write depends on whether
    // the row was already there. Either way the id logged is the row's own, so
    // table_name plus record_id finds exactly one row.
    private void savePermissible(UUID lotId, AgreementType agreementType,
                                 BigDecimal targetRate, BigDecimal askingRate) {
        Optional<LotPermissibleAgreementTypeRow> before = permissibleRow(lotId, agreementType);

        LotPermissibleAgreementTypeRow after = lotPermissibleAgreementTypeRepository.save(
                new LotPermissibleAgreementTypeRow(lotId, agreementType, targetRate, askingRate));

        if (before.isEmpty()) {
            auditService.recordInsert(
                    "lot_permissible_agreement_type", after.uuid(), AuditMapper.toMap(after));
            return;
        }

        AuditMapper.Diff diff = AuditMapper.diff(before.get(), after);
        if (!diff.before().isEmpty()) {
            auditService.recordUpdate(
                    "lot_permissible_agreement_type", after.uuid(), diff.before(), diff.after());
        }
    }
}