package io.github.lordship.tenancyterms.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.lots.internal.LotRow;
import io.github.lordship.properties.internal.PropertyRow;
import io.github.lordship.shared.*;
import io.github.lordship.tenancy.internal.TenancyRow;
import io.github.lordship.tenancyterms.ChargeTermConfiguration;
import io.github.lordship.tenancyterms.TenancyTermSource;
import io.github.lordship.tenancyterms.TenancyTermStatus;
import io.github.lordship.termstemplate.TermsTemplate;
import io.github.lordship.termstemplate.internal.TermsTemplateRepository;
import io.github.lordship.termstemplate.internal.TermsTemplateRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;


@Transactional
public class TenancyChargeTermRepositoryTest extends IntegrationTest {

    @Autowired
    TenancyChargeTermRepository tenancyChargeTermRepository;

    @Autowired
    TermsTemplateRepository termsTemplateRepository;

    @Autowired
    JdbcClient jdbc;

    private UUID tenancy;
    private UUID property;
    private TermsTemplate template;

    @BeforeEach
    void setUp() {
        PropertyRow propertyRow = testData.insertProperty("CT");
        LotRow lot = testData.insertLot(propertyRow.uuid(), "1");
        TenancyRow tenancyRow = testData.insertTenancy(lot.uuid());

        property = propertyRow.uuid();
        tenancy = tenancyRow.uuid();
        template = templateFor(property, "CT Land Lease");
    }

    // ---- save and the row mapper --------------------------------------------

    @Test
    void save_shouldFillInTheDatabaseGeneratedColumns() {
        // Act
        TenancyChargeTermRow saved = tenancyChargeTermRepository.save(draft(LocalDate.of(2026, 9, 1)));

        // Assert
        assertNotNull(saved.uuid());
        assertNotNull(saved.createdAt());
        assertNull(saved.deletedAt());
        assertEquals(TenancyTermStatus.PROPOSED, saved.status());

        assertEquals(template.uuid(), saved.termsTemplate());
    }

    // Reading a column that does not exist throws; reading the WRONG column
    // silently returns someone else's value. Nine money columns sit next to each
    // other in this table, so every one gets a value nothing else shares.
    @Test
    void rowMapper_shouldPopulateEveryColumn_onARoundTrip() {
        // Arrange -- the pairs are deliberately inconsistent, which a PROPOSED
        // row is allowed to be. That is what lets each column carry a value
        // distinct from every other one.
        TenancyChargeTermRow saved = tenancyChargeTermRepository.save(distinctRow());

        // Act
        TenancyChargeTermRow read = tenancyChargeTermRepository.findById(saved.uuid()).orElseThrow();

        // Assert
        assertEquals(tenancy, read.tenancy());
        assertEquals(LocalDate.of(2027, 3, 4), read.validAt());
        assertEquals(AgreementType.LAND, read.agreementType());

        assertEquals(0, read.rate().compareTo(new BigDecimal("101.00")));
        assertEquals(0, read.carFee().compareTo(new BigDecimal("102.00")));
        assertEquals(3, read.allowedCars());
        assertEquals(7, read.carsMax());
        assertEquals(0, read.petFee().compareTo(new BigDecimal("103.00")));
        assertEquals(5, read.allowedPets());

        assertEquals(9, read.paymentDueDay());
        assertEquals(11, read.gracePeriodDays());

        assertEquals(FeeMethod.FLAT, read.ruleViolationFeeMethod());
        assertEquals(0, read.ruleViolationFeeAmount().compareTo(new BigDecimal("201.00")));
        assertEquals(FeeMethod.BANK_OR_FLAT, read.nsfFeeMethod());
        assertEquals(0, read.nsfFeeAmount().compareTo(new BigDecimal("202.00")));
        assertEquals(FeeMethod.PERCENT_OF_RENT, read.lateFeeMethod());
        assertEquals(0, read.lateFeeAmount().compareTo(new BigDecimal("203.00")));

        assertEquals(UtilityMethod.FLAT, read.waterMethod());
        assertEquals(0, read.waterFlatAmount().compareTo(new BigDecimal("301.00")));
        assertEquals(UtilityMethod.RUBS, read.powerMethod());
        assertEquals(0, read.powerFlatAmount().compareTo(new BigDecimal("302.00")));
        assertEquals(UtilityMethod.SUBMETERED, read.sewerMethod());
        assertEquals(0, read.sewerFlatAmount().compareTo(new BigDecimal("303.00")));
        assertEquals(UtilityMethod.RUBS, read.trashMethod());
        assertEquals(0, read.trashFlatAmount().compareTo(new BigDecimal("304.00")));

        assertEquals(TenancyTermStatus.PROPOSED, read.status());
        assertEquals(TenancyTermSource.CORRECTION, read.source());
        assertEquals(template.uuid(), read.termsTemplate());
        assertEquals("CT round trip", read.note());
        assertEquals(SystemPrincipal.AGENT_UUID, read.createdBy());
    }

    @Test
    void findById_shouldNotReturnASoftDeletedTerm() {
        // Arrange
        TenancyChargeTermRow saved = tenancyChargeTermRepository.save(draft(LocalDate.of(2026, 9, 1)));
        tenancyChargeTermRepository.softDelete(saved.uuid());

        // Act / Assert
        assertTrue(tenancyChargeTermRepository.findById(saved.uuid()).isEmpty());
    }

    @Test
    void findByTenancy_shouldReturnNewestFirst() {
        // Arrange
        tenancyChargeTermRepository.save(draft(LocalDate.of(2026, 9, 1)));
        tenancyChargeTermRepository.save(draft(LocalDate.of(2027, 1, 1)));
        tenancyChargeTermRepository.save(draft(LocalDate.of(2026, 11, 1)));

        // Act
        List<TenancyChargeTermRow> found = tenancyChargeTermRepository.findByTenancy(tenancy);

        // Assert
        assertEquals(
                List.of(
                        LocalDate.of(2027, 1, 1),
                        LocalDate.of(2026, 11, 1),
                        LocalDate.of(2026, 9, 1)),
                found.stream().map(TenancyChargeTermRow::validAt).toList());
    }

    @Test
    void findByBatch_shouldReturnOnlyThatRun() {
        // Arrange -- a bulk run has to be reviewable and abandonable on its own
        UUID batch = UUID.randomUUID();
        tenancyChargeTermRepository.save(draft(LocalDate.of(2026, 9, 1), batch));
        tenancyChargeTermRepository.save(draft(LocalDate.of(2026, 10, 1), batch));
        tenancyChargeTermRepository.save(draft(LocalDate.of(2026, 11, 1), UUID.randomUUID()));
        tenancyChargeTermRepository.save(draft(LocalDate.of(2026, 12, 1)));

        // Act / Assert
        assertEquals(2, tenancyChargeTermRepository.findByBatch(batch).size());
    }

    // ---- patch ---------------------------------------------------------------

    @Test
    void patch_shouldRejectAColumnOutsideTheWhitelist() {
        // Arrange -- changing the agreement type would move the tenancy to a
        // different statute without anybody signing anything
        TenancyChargeTermRow saved = tenancyChargeTermRepository.save(draft(LocalDate.of(2026, 9, 1)));

        // Act / Assert
        assertThrows(InvalidDataAccessApiUsageException.class, () -> tenancyChargeTermRepository.patch(
                saved.uuid(), Map.of("agreement_type", "STORAGE")));
    }

    @Test
    void patch_shouldUpdateAndReturnTheRow() {
        // Arrange
        TenancyChargeTermRow saved = tenancyChargeTermRepository.save(draft(LocalDate.of(2026, 9, 1)));

        // Act
        TenancyChargeTermRow patched = tenancyChargeTermRepository.patch(
                saved.uuid(), Map.of("rate", new BigDecimal("777.00"), "note", "raised")).orElseThrow();

        // Assert
        assertEquals(0, patched.rate().compareTo(new BigDecimal("777.00")));
        assertEquals("raised", patched.note());
    }

    // ---- the escaped CHECK constraints --------------------------------------

    @Test
    void updateStatus_shouldMoveAConsistentDraftToPending() {
        // Arrange
        TenancyChargeTermRow saved = tenancyChargeTermRepository.save(draft(LocalDate.of(2026, 9, 1)));

        // Act
        Optional<TenancyChargeTermRow> moved = tenancyChargeTermRepository.updateStatus(
                saved.uuid(), TenancyTermStatus.PROPOSED, TenancyTermStatus.PENDING);

        // Assert
        assertTrue(moved.isPresent());
        assertEquals(TenancyTermStatus.PENDING, moved.get().status());
    }

    @Test
    void updateStatus_shouldReturnEmpty_whenTheTermIsNotInTheExpectedState() {
        // Arrange -- the guard that stops two agents submitting the same term
        TenancyChargeTermRow saved = tenancyChargeTermRepository.save(draft(LocalDate.of(2026, 9, 1)));

        // Act / Assert
        assertTrue(tenancyChargeTermRepository.updateStatus(
                saved.uuid(), TenancyTermStatus.PENDING, TenancyTermStatus.ACTIVE).isEmpty());
    }

    // This is the whole reason the service validates before submitting. The
    // amount/method CHECKs all read "status = 'PROPOSED' OR ...", so Postgres
    // re-evaluates them on the UPDATE that changes status -- not on the insert.
    @Test
    void updateStatus_shouldRaiseAnAmountConstraint_onLeavingProposed() {
        // Arrange -- a FLAT late fee with no amount: legal to save, illegal to submit
        TenancyChargeTermRow saved = tenancyChargeTermRepository.save(draft(LocalDate.of(2026, 9, 1)));
        tenancyChargeTermRepository.patch(saved.uuid(), Map.of(
                "late_fee_method", "FLAT",
                "late_fee_amount", BigDecimal.ZERO));

        // Act / Assert
        assertThrows(DataIntegrityViolationException.class, () -> tenancyChargeTermRepository.updateStatus(
                saved.uuid(), TenancyTermStatus.PROPOSED, TenancyTermStatus.PENDING));
    }

    @Test
    void updateStatus_shouldRaiseTheCarsConstraint_onLeavingProposed() {
        // Arrange
        TenancyChargeTermRow saved = tenancyChargeTermRepository.save(draft(LocalDate.of(2026, 9, 1)));
        tenancyChargeTermRepository.patch(saved.uuid(), Map.of("allowed_cars", 6, "cars_max", 2));

        // Act / Assert
        assertThrows(DataIntegrityViolationException.class, () -> tenancyChargeTermRepository.updateStatus(
                saved.uuid(), TenancyTermStatus.PROPOSED, TenancyTermStatus.PENDING));
    }

    @Test
    void updateStatus_shouldRefuseToActivateATermWithNoPaperBehindIt() {
        // Arrange -- term_in_force_needs_paper; source is LEASE, not MIGRATION
        TenancyChargeTermRow saved = tenancyChargeTermRepository.save(draft(LocalDate.of(2026, 9, 1)));
        tenancyChargeTermRepository.updateStatus(
                saved.uuid(), TenancyTermStatus.PROPOSED, TenancyTermStatus.PENDING);

        // Act / Assert
        assertThrows(DataIntegrityViolationException.class, () -> tenancyChargeTermRepository.updateStatus(
                saved.uuid(), TenancyTermStatus.PENDING, TenancyTermStatus.ACTIVE));
    }

    // ---- cancel --------------------------------------------------------------

    @Test
    void cancel_shouldSetAllFourColumnsAtOnce() {
        // Arrange -- term_cancel_facts and term_cancel_fields_only_when_cancelled
        // point in opposite directions, so a partial write fails one of them
        TenancyChargeTermRow active = activeTermAt(LocalDate.of(2026, 9, 1));

        // Act
        TenancyChargeTermRow cancelled = tenancyChargeTermRepository.cancel(
                active.uuid(), SystemPrincipal.AGENT_UUID, "tenant moved out").orElseThrow();

        // Assert
        assertEquals(TenancyTermStatus.CANCELLED, cancelled.status());
        assertNotNull(cancelled.cancelledAt());
        assertEquals(SystemPrincipal.AGENT_UUID, cancelled.cancelledBy());
        assertEquals("tenant moved out", cancelled.cancelReason());
    }

    @Test
    void cancel_shouldReturnEmpty_whenTheTermWasNeverInForce() {
        // Arrange -- a draft is deleted, not cancelled
        TenancyChargeTermRow saved = tenancyChargeTermRepository.save(draft(LocalDate.of(2026, 9, 1)));

        // Act / Assert
        assertTrue(tenancyChargeTermRepository.cancel(
                saved.uuid(), SystemPrincipal.AGENT_UUID, "nope").isEmpty());
    }

    // ---- soft delete ---------------------------------------------------------

    @Test
    void softDelete_shouldRemoveADraft() {
        // Arrange
        TenancyChargeTermRow saved = tenancyChargeTermRepository.save(draft(LocalDate.of(2026, 9, 1)));

        // Act / Assert
        assertTrue(tenancyChargeTermRepository.softDelete(saved.uuid()));
    }

    // The status guard lives in the WHERE clause, so this answers false rather
    // than raising term_delete_only_before_force -- which is what keeps the
    // service's "only audit when a row actually changed" rule working.
    @Test
    void softDelete_shouldReturnFalse_forATermInForce() {
        // Arrange
        TenancyChargeTermRow active = activeTermAt(LocalDate.of(2026, 9, 1));

        // Act / Assert
        assertFalse(tenancyChargeTermRepository.softDelete(active.uuid()));
        assertTrue(tenancyChargeTermRepository.findById(active.uuid()).isPresent());
    }

    // ---- findInForceOn: what billing asks ------------------------------------

    @Test
    void findInForceOn_shouldPickTheLatestTermTakingEffectOnOrBeforeTheDate() {
        // Arrange
        activeTermAt(LocalDate.of(2026, 1, 1));
        TenancyChargeTermRow current = activeTermAt(LocalDate.of(2026, 7, 1));

        // Act
        Optional<TenancyChargeTermRow> found =
                tenancyChargeTermRepository.findInForceOn(tenancy, LocalDate.of(2026, 9, 1));

        // Assert
        assertTrue(found.isPresent());
        assertEquals(current.uuid(), found.get().uuid());
    }

    @Test
    void findInForceOn_shouldIgnoreATermThatTakesEffectLater() {
        // Arrange -- a rent increase served in advance must not bill early
        TenancyChargeTermRow current = activeTermAt(LocalDate.of(2026, 1, 1));
        activeTermAt(LocalDate.of(2026, 10, 1));

        // Act
        Optional<TenancyChargeTermRow> found =
                tenancyChargeTermRepository.findInForceOn(tenancy, LocalDate.of(2026, 9, 1));

        // Assert
        assertTrue(found.isPresent());
        assertEquals(current.uuid(), found.get().uuid());
    }

    @Test
    void findInForceOn_shouldIgnoreACancelledTerm() {
        // Arrange -- cancelled leaves resolution entirely
        TenancyChargeTermRow superseded = activeTermAt(LocalDate.of(2026, 1, 1));
        TenancyChargeTermRow retracted = activeTermAt(LocalDate.of(2026, 7, 1));
        tenancyChargeTermRepository.cancel(retracted.uuid(), SystemPrincipal.AGENT_UUID, "retracted");

        // Act
        Optional<TenancyChargeTermRow> found =
                tenancyChargeTermRepository.findInForceOn(tenancy, LocalDate.of(2026, 9, 1));

        // Assert
        assertTrue(found.isPresent());
        assertEquals(superseded.uuid(), found.get().uuid());
    }

    @Test
    void findInForceOn_shouldIgnoreATermStillOutForSignature() {
        // Arrange -- PENDING is not in force
        TenancyChargeTermRow pending = tenancyChargeTermRepository.save(draft(LocalDate.of(2026, 1, 1)));
        tenancyChargeTermRepository.updateStatus(
                pending.uuid(), TenancyTermStatus.PROPOSED, TenancyTermStatus.PENDING);

        // Act / Assert
        assertTrue(tenancyChargeTermRepository.findInForceOn(tenancy, LocalDate.of(2026, 9, 1)).isEmpty());
    }

    @Test
    void twoTermsCannotTakeEffectForTheSameTenancyOnTheSameDate() {
        // Arrange -- tenancy_charge_term_in_force_uq is what makes the resolver
        // deterministic rather than "whichever row Postgres reaches first"
        activeTermAt(LocalDate.of(2026, 9, 1));

        // Act / Assert
        assertThrows(DataIntegrityViolationException.class, () -> activeTermAt(LocalDate.of(2026, 9, 1)));
    }

    // ---- attachSource: the composite foreign key -----------------------------

    @Test
    void attachSource_shouldAcceptAnInstrumentFromTheSameTenancy() {
        // Arrange
        TenancyChargeTermRow saved = tenancyChargeTermRepository.save(draft(LocalDate.of(2026, 9, 1)));
        UUID instrument = insertInstrument(tenancy);

        // Act
        TenancyChargeTermRow attached =
                tenancyChargeTermRepository.attachSource(saved.uuid(), instrument).orElseThrow();

        // Assert
        assertEquals(instrument, attached.sourceUuid());
    }

    // The composite FK to instrument(uuid, tenancy) is the only thing standing
    // between a signed lease and the wrong tenant's file.
    @Test
    void attachSource_shouldRejectAnInstrumentFromAnotherTenancy() {
        // Arrange
        TenancyChargeTermRow saved = tenancyChargeTermRepository.save(draft(LocalDate.of(2026, 9, 1)));
        LotRow otherLot = testData.insertLot(property, "2");
        UUID otherTenancy = testData.insertTenancy(otherLot.uuid()).uuid();
        UUID foreignInstrument = insertInstrument(otherTenancy);

        // Act / Assert
        assertThrows(DataIntegrityViolationException.class, () -> tenancyChargeTermRepository.attachSource(saved.uuid(), foreignInstrument));
    }

    // ---- configurations in force ---------------------------------------------

    @Test
    void findConfigurationsInForceByProperty_shouldReturnEveryBranchableMethod() {
        // Arrange -- the query filters on CURRENT_DATE, so the fixture is relative
        activeTermAt(LocalDate.now().minusMonths(1));

        // Act
        List<ChargeTermConfiguration> configurations =
                tenancyChargeTermRepository.findConfigurationsInForceByProperty(property);

        // Assert -- a method missing here reads as "unset" to DocumentAuditService,
        // which then passes a park whose lease is short a paragraph
        assertEquals(1, configurations.size());
        ChargeTermConfiguration configuration = configurations.get(0);
        assertEquals("LAND", configuration.agreementType());
        assertEquals("FLAT", configuration.lateFeeMethod());
        assertEquals("NONE", configuration.waterMethod());
        assertEquals("NONE", configuration.securityDepositMethod());
        assertEquals(1, configuration.tenancyCount());
    }

    @Test
    void findConfigurationsInForceByProperty_shouldSplitOnTheDepositMethod() {
        // Arrange -- two tenancies at one park, identical but for the deposit
        activeTermAt(LocalDate.now().minusMonths(1));

        LotRow secondLot = testData.insertLot(property, "2");
        UUID secondTenancy = testData.insertTenancy(secondLot.uuid()).uuid();

        TenancyChargeTermRow deposited = tenancyChargeTermRepository.save(
                TenancyChargeTermRow.fromTemplate(
                        secondTenancy, template, new BigDecimal("650.00"),
                        LocalDate.now().minusMonths(1),
                        TenancyTermSource.MIGRATION, null, SystemPrincipal.AGENT_UUID));

        tenancyChargeTermRepository.patch(deposited.uuid(), Map.of(
                "security_deposit_method", "MULTIPLE_OF_RENT",
                "security_deposit_amount", new BigDecimal("1.00")));
        tenancyChargeTermRepository.updateStatus(
                deposited.uuid(), TenancyTermStatus.PROPOSED, TenancyTermStatus.PENDING).orElseThrow();
        tenancyChargeTermRepository.updateStatus(
                deposited.uuid(), TenancyTermStatus.PENDING, TenancyTermStatus.ACTIVE).orElseThrow();

        // Act
        List<ChargeTermConfiguration> configurations =
                tenancyChargeTermRepository.findConfigurationsInForceByProperty(property);

        // Assert -- two rows rather than one collapsed row: the deposit method is
        // part of what makes a configuration distinct. This is the GROUP BY test.
        assertEquals(2, configurations.size());
        assertTrue(configurations.stream()
                .anyMatch(c -> "MULTIPLE_OF_RENT".equals(c.securityDepositMethod())));
        assertTrue(configurations.stream()
                .anyMatch(c -> "NONE".equals(c.securityDepositMethod())));
    }

    // ---- Fixtures ------------------------------------------------------------

    /**
     * A property-level template, inserted straight through the repository.
     * Deliberately not via TermsTemplateService: a repository test has no
     * principal, and going through a service would pull in the audit write,
     * which is meant to refuse an unattributed row rather than invent one.
     */
    private TermsTemplate templateFor(UUID property, String name) {
        return termsTemplateRepository.save(new TermsTemplateRow(
                property, name, AgreementType.LAND, SystemPrincipal.AGENT_UUID)).toTermsTemplate();
    }

    /** A consistent draft copied from the template, as the service would build it. */
    private TenancyChargeTermRow draft(LocalDate validAt) {
        return draft(validAt, null);
    }

    private TenancyChargeTermRow draft(LocalDate validAt, UUID batch) {
        return TenancyChargeTermRow.fromTemplate(
                tenancy, template, new BigDecimal("650.00"), validAt,
                TenancyTermSource.LEASE, batch, SystemPrincipal.AGENT_UUID);
    }

    /**
     * A term in force. Sourced MIGRATION because that is the one value
     * term_in_force_needs_paper exempts, which keeps the fixture from needing a
     * whole instrument behind it.
     */
    private TenancyChargeTermRow activeTermAt(LocalDate validAt) {
        TenancyChargeTermRow saved = tenancyChargeTermRepository.save(
                TenancyChargeTermRow.fromTemplate(
                        tenancy, template, new BigDecimal("650.00"), validAt,
                        TenancyTermSource.MIGRATION, null, SystemPrincipal.AGENT_UUID));

        tenancyChargeTermRepository.updateStatus(
                saved.uuid(), TenancyTermStatus.PROPOSED, TenancyTermStatus.PENDING).orElseThrow();
        return tenancyChargeTermRepository.updateStatus(
                saved.uuid(), TenancyTermStatus.PENDING, TenancyTermStatus.ACTIVE).orElseThrow();
    }

    private UUID insertInstrument(UUID forTenancy) {
        return jdbc.sql("""
                INSERT INTO instrument (tenancy, type, created_by)
                VALUES (:tenancy, :type::instrument_type, :createdBy)
                RETURNING uuid
                """)
                .param("tenancy", forTenancy)
                .param("type", "LEASE")
                .param("createdBy", SystemPrincipal.AGENT_UUID)
                .query(UUID.class)
                .single();
    }

    /**
     * Every column set to a value nothing else in the row shares, so a
     * transposed pair in the row mapper cannot hide. The method/amount pairs are
     * deliberately mismatched -- a PROPOSED row is allowed to be inconsistent,
     * and that is what frees each amount to be unique.
     */
    private TenancyChargeTermRow distinctRow() {
        return new TenancyChargeTermRow(
                null,
                tenancy,
                LocalDate.of(2027, 3, 4),
                AgreementType.LAND,
                new BigDecimal("101.00"),
                new BigDecimal("102.00"),
                3,
                7,
                new BigDecimal("103.00"),
                5,
                9,
                11,
                FeeMethod.FLAT, new BigDecimal("201.00"),
                FeeMethod.BANK_OR_FLAT, new BigDecimal("202.00"),
                FeeMethod.PERCENT_OF_RENT, new BigDecimal("203.00"),
                UtilityMethod.FLAT, new BigDecimal("301.00"),
                UtilityMethod.RUBS, new BigDecimal("302.00"),
                UtilityMethod.SUBMETERED, new BigDecimal("303.00"),
                UtilityMethod.RUBS, new BigDecimal("304.00"),
                SecurityDepositMethod.FLAT, new BigDecimal("101.00"),
                TenancyTermStatus.PROPOSED,
                TenancyTermSource.CORRECTION,
                null,
                template.uuid(),
                null,
                null, null, null,
                null,
                "CT round trip",
                null,
                SystemPrincipal.AGENT_UUID);
    }
}