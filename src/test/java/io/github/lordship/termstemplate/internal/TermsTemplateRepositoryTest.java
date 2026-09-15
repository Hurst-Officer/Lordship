package io.github.lordship.termstemplate.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.TestDataSupport;
import io.github.lordship.properties.internal.PropertyRow;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.FeeMethod;
import io.github.lordship.shared.SystemPrincipal;
import io.github.lordship.shared.UtilityMethod;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
public class TermsTemplateRepositoryTest extends IntegrationTest {

    @Autowired
    TermsTemplateRepository termsTemplateRepository;

    @Autowired
    TestDataSupport testData;

    // ── save: the minimal insert ─────────────────────────────────────────────

    @Test
    void save_shouldApplyEveryDatabaseDefault_whenOnlyTheRequiredColumnsAreGiven() {
        // Arrange -- the insert names four columns; the other twenty-six are the
        // database's job. This is the only test that proves those defaults exist.
        TermsTemplateRow row = new TermsTemplateRow(
                null, "RT Defaults", AgreementType.LAND, SystemPrincipal.AGENT_UUID);

        // Act
        TermsTemplateRow saved = termsTemplateRepository.save(row);

        // Assert
        assertThat(saved.uuid()).isNotNull();
        assertThat(saved.property()).isNull();
        assertThat(saved.copiedFrom()).isNull();
        assertThat(saved.agreementType()).isEqualTo(AgreementType.LAND);
        assertThat(saved.targetRate()).isNull();
        assertThat(saved.carFee()).isEqualByComparingTo(new BigDecimal("65.00"));
        assertThat(saved.allowedCars()).isEqualTo(2);
        assertThat(saved.carsMax()).isEqualTo(4);
        assertThat(saved.petFee()).isEqualByComparingTo(new BigDecimal("45.00"));
        assertThat(saved.allowedPets()).isEqualTo(2);
        assertThat(saved.paymentDueDay()).isEqualTo(1);
        assertThat(saved.gracePeriodDays()).isEqualTo(7);
        assertThat(saved.lateFeeMethod()).isEqualTo(FeeMethod.FLAT);
        assertThat(saved.nsfFeeMethod()).isEqualTo(FeeMethod.FLAT);
        assertThat(saved.waterMethod()).isEqualTo(UtilityMethod.NONE);
        assertThat(saved.trashMethod()).isEqualTo(UtilityMethod.NONE);
        assertThat(saved.createdAt()).isNotNull();
        assertThat(saved.updatedAt()).isNotNull();
        assertThat(saved.createdBy()).isEqualTo(SystemPrincipal.AGENT_UUID);
        assertThat(saved.deletedAt()).isNull();
    }

    // Reading a column that does not exist throws; reading the WRONG column
    // silently returns someone else's value. Only a round trip catches that.
    @Test
    void rowMapper_shouldPopulateEveryColumn_onARoundTrip() {
        // Arrange -- give every column a value distinct from its default
        TermsTemplateRow saved = termsTemplateRepository.save(new TermsTemplateRow(
                null, "RT Round Trip", AgreementType.STORAGE, SystemPrincipal.AGENT_UUID));
        termsTemplateRepository.patch(saved.uuid(), distinctValues());

        // Act
        TermsTemplateRow read = termsTemplateRepository.findById(saved.uuid()).orElseThrow();

        // Assert
        assertThat(read.name()).isEqualTo("RT Renamed");
        assertThat(read.targetRate()).isEqualByComparingTo(new BigDecimal("321.00"));
        assertThat(read.carFee()).isEqualByComparingTo(new BigDecimal("11.00"));
        assertThat(read.allowedCars()).isEqualTo(3);
        assertThat(read.carsMax()).isEqualTo(7);
        assertThat(read.petFee()).isEqualByComparingTo(new BigDecimal("12.00"));
        assertThat(read.allowedPets()).isEqualTo(5);
        assertThat(read.paymentDueDay()).isEqualTo(3);
        assertThat(read.gracePeriodDays()).isEqualTo(9);
        assertThat(read.lateFeeAmount()).isEqualByComparingTo(new BigDecimal("13.00"));
        assertThat(read.nsfFeeAmount()).isEqualByComparingTo(new BigDecimal("14.00"));
        assertThat(read.ruleViolationFeeAmount()).isEqualByComparingTo(new BigDecimal("15.00"));
        assertThat(read.waterMethod()).isEqualTo(UtilityMethod.SUBMETERED);
        assertThat(read.powerMethod()).isEqualTo(UtilityMethod.RUBS);
        assertThat(read.sewerMethod()).isEqualTo(UtilityMethod.FLAT);
        assertThat(read.sewerFlatAmount()).isEqualByComparingTo(new BigDecimal("16.00"));
        assertThat(read.trashMethod()).isEqualTo(UtilityMethod.RUBS);
        assertThat(read.note()).isEqualTo("RT note");
        assertThat(read.agreementType()).isEqualTo(AgreementType.STORAGE);
    }

    // ── saveCopy: the column list that used to drop carsMax ──────────────────

    @Test
    void saveCopy_shouldCarryEveryTermColumn_andRecordProvenance() {
        // Arrange -- a template whose every value differs from the DB defaults,
        // so a column missing from the INSERT shows up as a default, not a match.
        PropertyRow property = testData.insertProperty("RC");
        TermsTemplateRow template = termsTemplateRepository.save(new TermsTemplateRow(
                null, "RT Copy Source", AgreementType.LAND, SystemPrincipal.AGENT_UUID));
        termsTemplateRepository.patch(template.uuid(), distinctValues());
        TermsTemplateRow edited = termsTemplateRepository.findById(template.uuid()).orElseThrow();

        // Act
        TermsTemplateRow copy = termsTemplateRepository.saveCopy(
                edited.copyTo(property.uuid(), SystemPrincipal.AGENT_UUID));

        // Assert
        assertThat(copy.uuid()).isNotNull().isNotEqualTo(edited.uuid());
        assertThat(copy.property()).isEqualTo(property.uuid());
        assertThat(copy.copiedFrom()).isEqualTo(edited.uuid());

        assertThat(copy.name()).isEqualTo(edited.name());
        assertThat(copy.agreementType()).isEqualTo(edited.agreementType());
        assertThat(copy.targetRate()).isEqualByComparingTo(edited.targetRate());
        assertThat(copy.carFee()).isEqualByComparingTo(edited.carFee());
        assertThat(copy.allowedCars()).isEqualTo(edited.allowedCars());
        assertThat(copy.carsMax()).isEqualTo(edited.carsMax()); // the column that was missing
        assertThat(copy.petFee()).isEqualByComparingTo(edited.petFee());
        assertThat(copy.allowedPets()).isEqualTo(edited.allowedPets());
        assertThat(copy.paymentDueDay()).isEqualTo(edited.paymentDueDay());
        assertThat(copy.gracePeriodDays()).isEqualTo(edited.gracePeriodDays());
        assertThat(copy.lateFeeMethod()).isEqualTo(edited.lateFeeMethod());
        assertThat(copy.lateFeeAmount()).isEqualByComparingTo(edited.lateFeeAmount());
        assertThat(copy.nsfFeeMethod()).isEqualTo(edited.nsfFeeMethod());
        assertThat(copy.nsfFeeAmount()).isEqualByComparingTo(edited.nsfFeeAmount());
        assertThat(copy.ruleViolationFeeMethod()).isEqualTo(edited.ruleViolationFeeMethod());
        assertThat(copy.ruleViolationFeeAmount()).isEqualByComparingTo(edited.ruleViolationFeeAmount());
        assertThat(copy.waterMethod()).isEqualTo(edited.waterMethod());
        assertThat(copy.waterFlatAmount()).isEqualByComparingTo(edited.waterFlatAmount());
        assertThat(copy.powerMethod()).isEqualTo(edited.powerMethod());
        assertThat(copy.powerFlatAmount()).isEqualByComparingTo(edited.powerFlatAmount());
        assertThat(copy.sewerMethod()).isEqualTo(edited.sewerMethod());
        assertThat(copy.sewerFlatAmount()).isEqualByComparingTo(edited.sewerFlatAmount());
        assertThat(copy.trashMethod()).isEqualTo(edited.trashMethod());
        assertThat(copy.trashFlatAmount()).isEqualByComparingTo(edited.trashFlatAmount());
        assertThat(copy.note()).isEqualTo(edited.note());
    }

    @Test
    void saveCopy_shouldNotDisturbTheTemplateItCameFrom() {
        // Arrange
        PropertyRow property = testData.insertProperty("RF");
        TermsTemplateRow template = termsTemplateRepository.save(new TermsTemplateRow(
                null, "RT Frozen", AgreementType.LAND, SystemPrincipal.AGENT_UUID));

        // Act
        TermsTemplateRow copy = termsTemplateRepository.saveCopy(
                template.copyTo(property.uuid(), SystemPrincipal.AGENT_UUID));
        termsTemplateRepository.patch(copy.uuid(), Map.of("target_rate", new BigDecimal("700.00")));

        // Assert -- copy semantics, not reference semantics
        TermsTemplateRow reloaded = termsTemplateRepository.findById(template.uuid()).orElseThrow();
        assertThat(reloaded.targetRate()).isNull();
        //assertThat(reloaded.targetRate()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── Scoped reads ─────────────────────────────────────────────────────────

    @Test
    void findGlobalTemplates_shouldReturnTheSeedsAndExcludePropertySets() {
        // Arrange
        PropertyRow property = testData.insertProperty("RG");
        TermsTemplateRow template = termsTemplateRepository.save(new TermsTemplateRow(
                null, "RT Global Only", AgreementType.LAND, SystemPrincipal.AGENT_UUID));
        termsTemplateRepository.saveCopy(template.copyTo(property.uuid(), SystemPrincipal.AGENT_UUID));

        // Act
        List<TermsTemplateRow> globals = termsTemplateRepository.findGlobalTemplates();

        // Assert -- V1 seeds three, plus the one made here; the copy must not appear
        assertThat(globals).hasSizeGreaterThanOrEqualTo(4);
        assertThat(globals).allSatisfy(row -> assertThat(row.property()).isNull());
        assertThat(globals).extracting(TermsTemplateRow::name).contains("RT Global Only");
    }

    @Test
    void findByProperty_shouldReturnOnlyThatPropertysSets() {
        // Arrange
        PropertyRow mine = testData.insertProperty("RM");
        PropertyRow theirs = testData.insertProperty("RN");
        TermsTemplateRow a = termsTemplateRepository.save(new TermsTemplateRow(
                null, "RT Mine", AgreementType.LAND, SystemPrincipal.AGENT_UUID));
        TermsTemplateRow b = termsTemplateRepository.save(new TermsTemplateRow(
                null, "RT Theirs", AgreementType.LAND, SystemPrincipal.AGENT_UUID));
        termsTemplateRepository.saveCopy(a.copyTo(mine.uuid(), SystemPrincipal.AGENT_UUID));
        termsTemplateRepository.saveCopy(b.copyTo(theirs.uuid(), SystemPrincipal.AGENT_UUID));

        // Act
        List<TermsTemplateRow> found = termsTemplateRepository.findByProperty(mine.uuid());

        // Assert
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().name()).isEqualTo("RT Mine");
    }

    @Test
    void findByPropertyAndAgreementType_shouldReturnTheSetThatSeedsATenancy() {
        // Arrange
        PropertyRow property = testData.insertProperty("RA");
        TermsTemplateRow land = termsTemplateRepository.save(new TermsTemplateRow(
                null, "RT Land Set", AgreementType.LAND, SystemPrincipal.AGENT_UUID));
        TermsTemplateRow storage = termsTemplateRepository.save(new TermsTemplateRow(
                null, "RT Storage Set", AgreementType.STORAGE, SystemPrincipal.AGENT_UUID));
        termsTemplateRepository.saveCopy(land.copyTo(property.uuid(), SystemPrincipal.AGENT_UUID));
        termsTemplateRepository.saveCopy(storage.copyTo(property.uuid(), SystemPrincipal.AGENT_UUID));

        // Act
        Optional<TermsTemplateRow> found =
                termsTemplateRepository.findByPropertyAndAgreementType(property.uuid(), AgreementType.STORAGE);

        // Assert
        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("RT Storage Set");
    }

    @Test
    void findByPropertyAndAgreementType_shouldBeEmpty_whenNoSetWasCopiedIn() {
        // Arrange -- no set copied in means the property may not offer that agreement
        PropertyRow property = testData.insertProperty("RE");

        // Act / Assert
        assertThat(termsTemplateRepository
                .findByPropertyAndAgreementType(property.uuid(), AgreementType.LAND))
                .isEmpty();
    }

    @Test
    void findGlobalByName_shouldIgnoreCase() {
        // Arrange
        termsTemplateRepository.save(new TermsTemplateRow(
                null, "RT Case Test", AgreementType.LAND, SystemPrincipal.AGENT_UUID));

        // Act / Assert -- the name is a global template's identity, so near-misses matter
        assertThat(termsTemplateRepository.findGlobalByName("rt case test")).isPresent();
        assertThat(termsTemplateRepository.findGlobalByName("RT CASE TEST")).isPresent();
        assertThat(termsTemplateRepository.findGlobalByName("RT Case Test ")).isEmpty();
    }

    // ── patch ────────────────────────────────────────────────────────────────

    @Test
    void patch_shouldBumpUpdatedAt() {
        // Arrange
        TermsTemplateRow saved = termsTemplateRepository.save(new TermsTemplateRow(
                null, "RT Touch", AgreementType.LAND, SystemPrincipal.AGENT_UUID));

        // Act
        TermsTemplateRow patched = termsTemplateRepository
                .patch(saved.uuid(), Map.of("note", "touched")).orElseThrow();

        // Assert -- there is no trigger on this table; the UPDATE sets it by hand
        assertThat(patched.updatedAt()).isAfterOrEqualTo(saved.updatedAt());
        assertThat(patched.createdAt()).isEqualTo(saved.createdAt());
    }

    @Test
    void patch_shouldReturnTheExistingRow_whenThereIsNothingToChange() {
        // Arrange
        TermsTemplateRow saved = termsTemplateRepository.save(new TermsTemplateRow(
                null, "RT Empty Patch", AgreementType.LAND, SystemPrincipal.AGENT_UUID));

        // Act / Assert
        assertThat(termsTemplateRepository.patch(saved.uuid(), Map.of()))
                .isPresent()
                .get()
                .extracting(TermsTemplateRow::uuid)
                .isEqualTo(saved.uuid());
    }

    @Test
    void patch_shouldReturnEmpty_whenTheRowIsSoftDeleted() {
        // Arrange
        TermsTemplateRow saved = termsTemplateRepository.save(new TermsTemplateRow(
                null, "RT Gone", AgreementType.LAND, SystemPrincipal.AGENT_UUID));
        termsTemplateRepository.softDelete(saved.uuid());

        // Act / Assert
        assertThat(termsTemplateRepository.patch(saved.uuid(), Map.of("note", "too late"))).isEmpty();
    }

    // The whitelist exists to stop identity and provenance being rewritten. Testing
    // the deny side keeps this from becoming a second copy of the allow-list.
    @Test
    void patch_shouldRejectColumnsThatAreNotPatchable() {
        // Arrange
        TermsTemplateRow saved = termsTemplateRepository.save(new TermsTemplateRow(
                null, "RT Guarded", AgreementType.LAND, SystemPrincipal.AGENT_UUID));

        // Act / Assert
        for (String column : List.of("uuid", "property", "copied_from", "agreement_type",
                "created_by", "created_at", "deleted_at", "name_full", "1=1")) {
            assertThatThrownBy(() -> termsTemplateRepository.patch(saved.uuid(), Map.of(column, "x")))
                    .as("column " + column + " must not be patchable")
                    .isInstanceOf(InvalidDataAccessApiUsageException.class);
        }
    }

    // ── Constraints the service relies on but never executes ─────────────────

    @Test
    void patch_shouldAcceptAPercentOfRentLateFee() {
        // Arrange
        TermsTemplateRow saved = termsTemplateRepository.save(new TermsTemplateRow(
                null, "RT Percent", AgreementType.LAND, SystemPrincipal.AGENT_UUID));

        // Act -- straight at the CHECK constraint, with no service reconciliation
        TermsTemplateRow patched = termsTemplateRepository.patch(saved.uuid(), Map.of(
                "late_fee_method", "PERCENT_OF_RENT",
                "late_fee_amount", new BigDecimal("1.5"))).orElseThrow();

        // Assert
        assertThat(patched.lateFeeMethod()).isEqualTo(FeeMethod.PERCENT_OF_RENT);
        assertThat(patched.lateFeeAmount()).isEqualByComparingTo(new BigDecimal("1.5"));
    }

    @Test
    void patch_shouldBeRejected_whenAFeeMethodIsNoneButTheAmountIsNot() {
        // Arrange
        TermsTemplateRow saved = termsTemplateRepository.save(new TermsTemplateRow(
                null, "RT Bad Pair", AgreementType.LAND, SystemPrincipal.AGENT_UUID));

        // Act / Assert -- the service normally reconciles this pair; the constraint
        // is the backstop for anything that does not go through the service.
        assertThatThrownBy(() -> termsTemplateRepository.patch(saved.uuid(), Map.of(
                "late_fee_method", "NONE",
                "late_fee_amount", new BigDecimal("65.00"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void patch_shouldBeRejected_whenAUtilityIsSubmeteredWithAFlatAmount() {
        // Arrange
        TermsTemplateRow saved = termsTemplateRepository.save(new TermsTemplateRow(
                null, "RT Bad Utility", AgreementType.LAND, SystemPrincipal.AGENT_UUID));

        // Act / Assert -- a submetered utility is computed, so a flat amount is nonsense
        assertThatThrownBy(() -> termsTemplateRepository.patch(saved.uuid(), Map.of(
                "water_method", "SUBMETERED",
                "water_flat_amount", new BigDecimal("20.00"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── Soft delete ──────────────────────────────────────────────────────────

    @Test
    void softDelete_shouldHideTheRow_andReturnFalseTheSecondTime() {
        // Arrange
        TermsTemplateRow saved = termsTemplateRepository.save(new TermsTemplateRow(
                null, "RT Doomed", AgreementType.LAND, SystemPrincipal.AGENT_UUID));

        // Act / Assert
        assertThat(termsTemplateRepository.softDelete(saved.uuid())).isTrue();
        assertThat(termsTemplateRepository.findById(saved.uuid())).isEmpty();
        assertThat(termsTemplateRepository.findGlobalByName("RT Doomed")).isEmpty();
        assertThat(termsTemplateRepository.softDelete(saved.uuid())).isFalse();
    }

    @Test
    void softDelete_shouldFreeTheNameAndTheAgreementSlot() {
        // Arrange -- a deleted set must not block a replacement
        PropertyRow property = testData.insertProperty("RS");
        TermsTemplateRow template = termsTemplateRepository.save(new TermsTemplateRow(
                null, "RT Reusable", AgreementType.LAND, SystemPrincipal.AGENT_UUID));
        TermsTemplateRow copy = termsTemplateRepository.saveCopy(
                template.copyTo(property.uuid(), SystemPrincipal.AGENT_UUID));

        // Act
        termsTemplateRepository.softDelete(copy.uuid());

        // Assert
        assertThat(termsTemplateRepository
                .findByPropertyAndAgreementType(property.uuid(), AgreementType.LAND))
                .isEmpty();
        assertThat(termsTemplateRepository.findByProperty(property.uuid())).isEmpty();
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /** Every patchable column set to something no default would produce. */
    private static Map<String, Object> distinctValues() {
        return Map.ofEntries(
                Map.entry("name", "RT Renamed"),
                Map.entry("target_rate", new BigDecimal("321.00")),
                Map.entry("escalation_percent", new BigDecimal("4.00")),
                Map.entry("escalation_months", 12),
                Map.entry("car_fee", new BigDecimal("11.00")),
                Map.entry("allowed_cars", 3),
                Map.entry("cars_max", 7),
                Map.entry("pet_fee", new BigDecimal("12.00")),
                Map.entry("allowed_pets", 5),
                Map.entry("payment_due_day", 3),
                Map.entry("grace_period_days", 9),
                Map.entry("late_fee_method", "FLAT"),
                Map.entry("late_fee_amount", new BigDecimal("13.00")),
                Map.entry("nsf_fee_method", "FLAT"),
                Map.entry("nsf_fee_amount", new BigDecimal("14.00")),
                Map.entry("rule_violation_fee_method", "FLAT"),
                Map.entry("rule_violation_fee_amount", new BigDecimal("15.00")),
                Map.entry("water_method", "SUBMETERED"),
                Map.entry("water_flat_amount", BigDecimal.ZERO),
                Map.entry("power_method", "RUBS"),
                Map.entry("power_flat_amount", BigDecimal.ZERO),
                Map.entry("sewer_method", "FLAT"),
                Map.entry("sewer_flat_amount", new BigDecimal("16.00")),
                Map.entry("trash_method", "RUBS"),
                Map.entry("trash_flat_amount", BigDecimal.ZERO),
                Map.entry("note", "RT note"));
    }
}