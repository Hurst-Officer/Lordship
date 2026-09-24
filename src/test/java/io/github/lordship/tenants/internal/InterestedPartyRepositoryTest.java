package io.github.lordship.tenants.internal;

import io.github.lordship.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
public class InterestedPartyRepositoryTest extends IntegrationTest {

    @Autowired
    InterestedPartyRepository interestedPartyRepository;

    @Autowired
    JdbcClient jdbc;

    private UUID tenancy(String propertyCode) {
        return testData.insertTenancy(
                testData.insertLot(testData.insertProperty(propertyCode).uuid(), "1").uuid()
        ).uuid();
    }

    private UUID person(String name) {
        return testData.insertPerson(name).uuid();
    }

    private Set<UUID> uuids(List<InterestedPartyRow> rows) {
        return rows.stream().map(InterestedPartyRow::uuid).collect(Collectors.toSet());
    }

    private int endInterestedParty(UUID uuid, LocalDate endDate) {
        return jdbc.sql("UPDATE tenancy_interested_party SET end_date = :endDate WHERE uuid = :uuid")
                .param("endDate", endDate)
                .param("uuid", uuid)
                .update();
    }

    // ---- save -----------------------------------------------------------------

    @Test
    void save_shouldPersistTheMinimumRow() {
        UUID tenancyId = tenancy("IP101");
        UUID personId = person("Bank of Springfield");
        LocalDate start = LocalDate.of(2026, 10, 1);

        InterestedPartyRow row = interestedPartyRepository.save(tenancyId, personId, start);

        assertNotNull(row.uuid());
        assertEquals(tenancyId, row.tenancyId());
        assertEquals(personId, row.personId());
        assertEquals(start, row.startDate());
        assertNull(row.endDate());
        assertNull(row.deletedAt());
        assertNotNull(row.createdAt());
    }

    @Test
    void save_shouldAcceptANullStartDate() {
        UUID tenancyId = tenancy("IP102");

        InterestedPartyRow row = interestedPartyRepository.save(tenancyId, person("Bank of Springfield"), null);

        assertNull(row.startDate());
    }

    // A tenancy can have more than one interested party at once (e.g. a lienholder
    // and a separate secured party), and adding one must not touch the others.
    @Test
    void save_shouldAllowSeveralInterestedPartiesOnOneTenancy() {
        UUID tenancyId = tenancy("IP103");

        InterestedPartyRow bank = interestedPartyRepository.save(
                tenancyId, person("Bank of Springfield"), LocalDate.of(2026, 1, 1));
        InterestedPartyRow union = interestedPartyRepository.save(
                tenancyId, person("Credit Union of Shelbyville"), LocalDate.of(2026, 3, 1));

        List<InterestedPartyRow> active = interestedPartyRepository.findActiveByTenancy(tenancyId);

        assertEquals(2, active.size());
        assertEquals(Set.of(bank.uuid(), union.uuid()), uuids(active));
        assertNull(active.get(0).endDate());
        assertNull(active.get(1).endDate());
    }

    @Test
    void save_shouldAllowTheSamePersonOnTwoDifferentTenancies() {
        UUID personId = person("Bank of Springfield");

        interestedPartyRepository.save(tenancy("IP104"), personId, LocalDate.of(2026, 1, 1));
        interestedPartyRepository.save(tenancy("IP105"), personId, LocalDate.of(2026, 1, 1));

        assertEquals(2, interestedPartyRepository.findByPerson(personId).size());
    }

    // ---- uq_interested_party_active_person -------------------------------------

    @Test
    void save_shouldRefuseTheSamePersonTwiceWhileBothRowsAreActive() {
        UUID tenancyId = tenancy("IP121");
        UUID personId = person("Bank of Springfield");

        interestedPartyRepository.save(tenancyId, personId, LocalDate.of(2026, 1, 1));

        assertThrows(DataIntegrityViolationException.class,
                () -> interestedPartyRepository.save(tenancyId, personId, LocalDate.of(2026, 5, 1)));
    }

    @Test
    void save_shouldAllowThatPersonBack_onceTheEarlierRowHasEnded() {
        UUID tenancyId = tenancy("IP122");
        UUID personId = person("Bank of Springfield");

        InterestedPartyRow first = interestedPartyRepository.save(tenancyId, personId, LocalDate.of(2024, 1, 1));
        endInterestedParty(first.uuid(), LocalDate.of(2025, 6, 30));

        InterestedPartyRow second = interestedPartyRepository.save(tenancyId, personId, LocalDate.of(2026, 1, 1));

        assertNotEquals(first.uuid(), second.uuid());
        assertEquals(1, interestedPartyRepository.findActiveByTenancy(tenancyId).size());
        assertEquals(2, interestedPartyRepository.findByTenancy(tenancyId).size());
    }

    // ---- reads ------------------------------------------------------------------

    @Test
    void findById_shouldHideSoftDeletedRows() {
        UUID tenancyId = tenancy("IP106");
        InterestedPartyRow row = interestedPartyRepository.save(
                tenancyId, person("Bank of Springfield"), LocalDate.of(2026, 1, 1));

        assertTrue(interestedPartyRepository.findById(row.uuid()).isPresent());
        assertTrue(interestedPartyRepository.softDelete(row.uuid()));
        assertTrue(interestedPartyRepository.findById(row.uuid()).isEmpty());
    }

    @Test
    void findByTenancy_shouldHideSoftDeletedRows() {
        UUID tenancyId = tenancy("IP107");
        InterestedPartyRow kept = interestedPartyRepository.save(
                tenancyId, person("Bank of Springfield"), LocalDate.of(2026, 1, 1));
        InterestedPartyRow removed = interestedPartyRepository.save(
                tenancyId, person("Credit Union of Shelbyville"), LocalDate.of(2026, 1, 1));

        interestedPartyRepository.softDelete(removed.uuid());

        assertEquals(Set.of(kept.uuid()), uuids(interestedPartyRepository.findByTenancy(tenancyId)));
    }

    @Test
    void findActiveByTenancy_shouldExcludeEndedRows() {
        UUID tenancyId = tenancy("IP108");
        InterestedPartyRow stayed = interestedPartyRepository.save(
                tenancyId, person("Bank of Springfield"), LocalDate.of(2026, 1, 1));
        InterestedPartyRow concluded = interestedPartyRepository.save(
                tenancyId, person("Credit Union of Shelbyville"), LocalDate.of(2026, 1, 1));

        endInterestedParty(concluded.uuid(), LocalDate.of(2026, 6, 30));

        assertEquals(Set.of(stayed.uuid()), uuids(interestedPartyRepository.findActiveByTenancy(tenancyId)));
        assertEquals(2, interestedPartyRepository.findByTenancy(tenancyId).size());
    }

    @Test
    void findActiveByTenancyAndPerson_shouldSeeOnlyTheLiveRow() {
        UUID tenancyId = tenancy("IP109");
        UUID personId = person("Bank of Springfield");

        InterestedPartyRow first = interestedPartyRepository.save(tenancyId, personId, LocalDate.of(2024, 1, 1));
        assertEquals(first.uuid(), interestedPartyRepository.findActiveByTenancyAndPerson(tenancyId, personId)
                .orElseThrow().uuid());

        endInterestedParty(first.uuid(), LocalDate.of(2025, 6, 30));
        assertTrue(interestedPartyRepository.findActiveByTenancyAndPerson(tenancyId, personId).isEmpty());
    }

    @Test
    void findByPerson_shouldExcludeSoftDeletedRows() {
        UUID personId = person("Bank of Springfield");
        InterestedPartyRow kept = interestedPartyRepository.save(
                tenancy("IP110"), personId, LocalDate.of(2026, 1, 1));
        InterestedPartyRow removed = interestedPartyRepository.save(
                tenancy("IP111"), personId, LocalDate.of(2026, 1, 1));

        interestedPartyRepository.softDelete(removed.uuid());

        assertEquals(Set.of(kept.uuid()), uuids(interestedPartyRepository.findByPerson(personId)));
    }

    // ---- patch --------------------------------------------------------------------

    @Test
    void patch_shouldReturnTheRowUnchanged_whenChangesAreEmpty() {
        UUID tenancyId = tenancy("IP112");
        InterestedPartyRow row = interestedPartyRepository.save(
                tenancyId, person("Bank of Springfield"), LocalDate.of(2026, 1, 1));

        assertEquals(row.uuid(), interestedPartyRepository.patch(row.uuid(), Map.of()).orElseThrow().uuid());
    }

    @Test
    void patch_shouldSetEndDate() {
        UUID tenancyId = tenancy("IP113");
        InterestedPartyRow row = interestedPartyRepository.save(
                tenancyId, person("Bank of Springfield"), LocalDate.of(2026, 1, 1));

        Optional<InterestedPartyRow> patched = interestedPartyRepository.patch(
                row.uuid(), Map.of("end_date", LocalDate.of(2026, 6, 30)));

        assertTrue(patched.isPresent());
        assertEquals(LocalDate.of(2026, 6, 30), patched.get().endDate());
        assertEquals(LocalDate.of(2026, 1, 1), patched.get().startDate());
    }

    @Test
    void patch_shouldSetStartDate() {
        UUID tenancyId = tenancy("IP114");
        InterestedPartyRow row = interestedPartyRepository.save(
                tenancyId, person("Bank of Springfield"), LocalDate.of(2026, 1, 1));

        Optional<InterestedPartyRow> patched = interestedPartyRepository.patch(
                row.uuid(), Map.of("start_date", LocalDate.of(2026, 2, 1)));

        assertEquals(LocalDate.of(2026, 2, 1), patched.orElseThrow().startDate());
    }

    @Test
    void patch_shouldSetAcceptPayments() {
        UUID tenancyId = tenancy("IP115");
        InterestedPartyRow row = interestedPartyRepository.save(
                tenancyId, person("Bank of Springfield"), LocalDate.of(2026, 1, 1));

        Optional<InterestedPartyRow> patched = interestedPartyRepository.patch(
                row.uuid(), Map.of("accept_payments", false));

        assertFalse(patched.orElseThrow().acceptPayments());
    }

    @Test
    void patch_shouldSetNotes() {
        UUID tenancyId = tenancy("IP116");
        InterestedPartyRow row = interestedPartyRepository.save(
                tenancyId, person("Bank of Springfield"), LocalDate.of(2026, 1, 1));

        Optional<InterestedPartyRow> patched = interestedPartyRepository.patch(
                row.uuid(), Map.of("notes", "Flagged during default review"));

        assertEquals("Flagged during default review", patched.orElseThrow().notes());
    }

    // This column is why the whole class exists: "notification_reason," (a trailing
    // comma folded into the literal) used to keep the real column name out of
    // ALLOWED_COLUMNS, so a patch trying to set it looked identical to a typo'd
    // column name. Exercising every allowed column individually, rather than
    // trusting the set as a whole, is what would have caught it.
    @Test
    void patch_shouldSetNotificationReason() {
        UUID tenancyId = tenancy("IP117");
        InterestedPartyRow row = interestedPartyRepository.save(
                tenancyId, person("Bank of Springfield"), LocalDate.of(2026, 1, 1));

        Optional<InterestedPartyRow> patched = interestedPartyRepository.patch(
                row.uuid(), Map.of("notification_reason", "Home listed as collateral"));

        assertEquals("Home listed as collateral", patched.orElseThrow().notificationReason());
    }

    @Test
    void patch_shouldRejectAColumnOutsideTheAllowedSet() {
        UUID tenancyId = tenancy("IP118");
        InterestedPartyRow row = interestedPartyRepository.save(
                tenancyId, person("Bank of Springfield"), LocalDate.of(2026, 1, 1));

        assertThrows(InvalidDataAccessApiUsageException.class,
                () -> interestedPartyRepository.patch(row.uuid(), Map.of("person_id", UUID.randomUUID())));
    }

    @Test
    void patch_shouldReturnEmpty_forASoftDeletedRow() {
        UUID tenancyId = tenancy("IP119");
        InterestedPartyRow row = interestedPartyRepository.save(
                tenancyId, person("Bank of Springfield"), LocalDate.of(2026, 1, 1));
        interestedPartyRepository.softDelete(row.uuid());

        assertTrue(interestedPartyRepository.patch(
                row.uuid(), Map.of("end_date", LocalDate.of(2026, 6, 30))).isEmpty());
    }

    // ---- softDelete -----------------------------------------------------------------

    @Test
    void softDelete_shouldReturnFalse_onASecondCall() {
        UUID tenancyId = tenancy("IP120");
        InterestedPartyRow row = interestedPartyRepository.save(
                tenancyId, person("Bank of Springfield"), LocalDate.of(2026, 1, 1));

        assertTrue(interestedPartyRepository.softDelete(row.uuid()));
        assertFalse(interestedPartyRepository.softDelete(row.uuid()));
    }
}
