package io.github.lordship.homes.internal;

import io.github.lordship.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
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
public class SecuredPartyRepositoryTest extends IntegrationTest {

    @Autowired
    SecuredPartyRepository securedPartyRepository;

    @Autowired
    HomeRepository homeRepository;

    private UUID mobileHomeOn(String propertyCode, String lotNumber) {
        UUID propertyId = testData.insertProperty(propertyCode).uuid();
        UUID lotId = testData.insertLot(propertyId, lotNumber).uuid();
        return homeRepository.save(lotId, null).orElseThrow().uuid();
    }

    private UUID person(String name) {
        return testData.insertPerson(name).uuid();
    }

    private Set<UUID> uuids(List<SecuredPartyRow> rows) {
        return rows.stream().map(SecuredPartyRow::uuid).collect(Collectors.toSet());
    }

    // ── save ─────────────────────────────────────────────────────────────────────

    @Test
    void save_shouldPersistTheMinimumRow() {
        UUID mobileHomeId = mobileHomeOn("SP001", "1");
        UUID personId = person("First National Bank");
        LocalDate start = LocalDate.of(2026, 10, 1);

        SecuredPartyRow row = securedPartyRepository.save(mobileHomeId, personId, start).orElseThrow();

        assertNotNull(row.uuid());
        assertEquals(mobileHomeId, row.mobileHomeId());
        assertEquals(personId, row.personId());
        assertEquals(start, row.startDate());
        assertNull(row.endDate());
        assertFalse(row.acceptPayments());
        assertNull(row.deletedAt());
        assertNotNull(row.createdAt());
    }

    @Test
    void save_shouldAcceptANullStartDate() {
        UUID mobileHomeId = mobileHomeOn("SP002", "1");

        SecuredPartyRow row = securedPartyRepository.save(
                mobileHomeId, person("First National Bank"), null).orElseThrow();

        assertNull(row.startDate());
    }

    // Selecting from mobile_home means a home that is missing or deleted inserts
    // nothing rather than tripping the foreign key.
    @Test
    void save_shouldReturnEmpty_whenTheHomeDoesNotExist() {
        assertTrue(securedPartyRepository.save(
                UUID.randomUUID(), person("First National Bank"), LocalDate.now()).isEmpty());
    }

    @Test
    void save_shouldReturnEmpty_whenTheHomeIsSoftDeleted() {
        UUID mobileHomeId = mobileHomeOn("SP003", "1");
        homeRepository.softDelete(mobileHomeId);

        assertTrue(securedPartyRepository.save(
                mobileHomeId, person("First National Bank"), LocalDate.now()).isEmpty());
    }

    // A home can carry more than one secured party at once (e.g. a first and second
    // lienholder), and adding one must not touch the others.
    @Test
    void save_shouldAllowSeveralSecuredPartiesOnOneHome() {
        UUID mobileHomeId = mobileHomeOn("SP004", "1");

        SecuredPartyRow first = securedPartyRepository.save(
                mobileHomeId, person("First National Bank"), LocalDate.of(2026, 1, 1)).orElseThrow();
        SecuredPartyRow second = securedPartyRepository.save(
                mobileHomeId, person("Credit Union of Shelbyville"), LocalDate.of(2026, 3, 1)).orElseThrow();

        List<SecuredPartyRow> active = securedPartyRepository.findActiveByHome(mobileHomeId);

        assertEquals(2, active.size());
        assertEquals(Set.of(first.uuid(), second.uuid()), uuids(active));
    }

    @Test
    void save_shouldAllowTheSamePersonOnTwoDifferentHomes() {
        UUID personId = person("First National Bank");

        securedPartyRepository.save(mobileHomeOn("SP005", "1"), personId, LocalDate.of(2026, 1, 1));
        securedPartyRepository.save(mobileHomeOn("SP006", "1"), personId, LocalDate.of(2026, 1, 1));

        assertEquals(2, securedPartyRepository.findByPerson(personId).size());
    }

    // ── uq_secured_party_active_person ──────────────────────────────────────────

    @Test
    void save_shouldRefuseTheSamePersonTwiceWhileBothRowsAreActive() {
        UUID mobileHomeId = mobileHomeOn("SP021", "1");
        UUID personId = person("First National Bank");

        securedPartyRepository.save(mobileHomeId, personId, LocalDate.of(2026, 1, 1));

        assertThrows(DataIntegrityViolationException.class,
                () -> securedPartyRepository.save(mobileHomeId, personId, LocalDate.of(2026, 5, 1)));
    }

    @Test
    void save_shouldAllowThatPersonBack_onceTheEarlierRowHasEnded() {
        UUID mobileHomeId = mobileHomeOn("SP022", "1");
        UUID personId = person("First National Bank");

        SecuredPartyRow first = securedPartyRepository.save(
                mobileHomeId, personId, LocalDate.of(2024, 1, 1)).orElseThrow();
        securedPartyRepository.patch(first.uuid(), Map.of("end_date", LocalDate.of(2025, 6, 30)));

        SecuredPartyRow second = securedPartyRepository.save(
                mobileHomeId, personId, LocalDate.of(2026, 1, 1)).orElseThrow();

        assertNotEquals(first.uuid(), second.uuid());
        assertEquals(1, securedPartyRepository.findActiveByHome(mobileHomeId).size());
        assertEquals(2, securedPartyRepository.findByHome(mobileHomeId).size());
    }

    // ── reads ────────────────────────────────────────────────────────────────────

    @Test
    void findById_shouldHideSoftDeletedRows() {
        UUID mobileHomeId = mobileHomeOn("SP007", "1");
        SecuredPartyRow row = securedPartyRepository.save(
                mobileHomeId, person("First National Bank"), LocalDate.of(2026, 1, 1)).orElseThrow();

        assertTrue(securedPartyRepository.findById(row.uuid()).isPresent());
        assertTrue(securedPartyRepository.softDelete(row.uuid()));
        assertTrue(securedPartyRepository.findById(row.uuid()).isEmpty());
    }

    @Test
    void findByHome_shouldHideSoftDeletedRows() {
        UUID mobileHomeId = mobileHomeOn("SP008", "1");
        SecuredPartyRow kept = securedPartyRepository.save(
                mobileHomeId, person("First National Bank"), LocalDate.of(2026, 1, 1)).orElseThrow();
        SecuredPartyRow removed = securedPartyRepository.save(
                mobileHomeId, person("Credit Union of Shelbyville"), LocalDate.of(2026, 1, 1)).orElseThrow();

        securedPartyRepository.softDelete(removed.uuid());

        assertEquals(Set.of(kept.uuid()), uuids(securedPartyRepository.findByHome(mobileHomeId)));
    }

    @Test
    void findActiveByHome_shouldExcludeEndedRows() {
        UUID mobileHomeId = mobileHomeOn("SP009", "1");
        SecuredPartyRow stayed = securedPartyRepository.save(
                mobileHomeId, person("First National Bank"), LocalDate.of(2026, 1, 1)).orElseThrow();
        SecuredPartyRow concluded = securedPartyRepository.save(
                mobileHomeId, person("Credit Union of Shelbyville"), LocalDate.of(2026, 1, 1)).orElseThrow();

        securedPartyRepository.patch(concluded.uuid(), Map.of("end_date", LocalDate.of(2026, 6, 30)));

        assertEquals(Set.of(stayed.uuid()), uuids(securedPartyRepository.findActiveByHome(mobileHomeId)));
        assertEquals(2, securedPartyRepository.findByHome(mobileHomeId).size());
    }

    @Test
    void findActiveByHomeAndPerson_shouldSeeOnlyTheLiveRow() {
        UUID mobileHomeId = mobileHomeOn("SP010", "1");
        UUID personId = person("First National Bank");

        SecuredPartyRow first = securedPartyRepository.save(
                mobileHomeId, personId, LocalDate.of(2024, 1, 1)).orElseThrow();
        assertEquals(first.uuid(), securedPartyRepository.findActiveByHomeAndPerson(mobileHomeId, personId)
                .orElseThrow().uuid());

        securedPartyRepository.patch(first.uuid(), Map.of("end_date", LocalDate.of(2025, 6, 30)));
        assertTrue(securedPartyRepository.findActiveByHomeAndPerson(mobileHomeId, personId).isEmpty());
    }

    @Test
    void findByPerson_shouldExcludeSoftDeletedRows() {
        UUID personId = person("First National Bank");
        SecuredPartyRow kept = securedPartyRepository.save(
                mobileHomeOn("SP011", "1"), personId, LocalDate.of(2026, 1, 1)).orElseThrow();
        SecuredPartyRow removed = securedPartyRepository.save(
                mobileHomeOn("SP012", "1"), personId, LocalDate.of(2026, 1, 1)).orElseThrow();

        securedPartyRepository.softDelete(removed.uuid());

        assertEquals(Set.of(kept.uuid()), uuids(securedPartyRepository.findByPerson(personId)));
    }

    // ── patch ────────────────────────────────────────────────────────────────────

    @Test
    void patch_shouldReturnTheRowUnchanged_whenChangesAreEmpty() {
        UUID mobileHomeId = mobileHomeOn("SP013", "1");
        SecuredPartyRow row = securedPartyRepository.save(
                mobileHomeId, person("First National Bank"), LocalDate.of(2026, 1, 1)).orElseThrow();

        assertEquals(row.uuid(), securedPartyRepository.patch(row.uuid(), Map.of()).orElseThrow().uuid());
    }

    @Test
    void patch_shouldSetEndDate() {
        UUID mobileHomeId = mobileHomeOn("SP014", "1");
        SecuredPartyRow row = securedPartyRepository.save(
                mobileHomeId, person("First National Bank"), LocalDate.of(2026, 1, 1)).orElseThrow();

        Optional<SecuredPartyRow> patched = securedPartyRepository.patch(
                row.uuid(), Map.of("end_date", LocalDate.of(2026, 6, 30)));

        assertTrue(patched.isPresent());
        assertEquals(LocalDate.of(2026, 6, 30), patched.get().endDate());
        assertEquals(LocalDate.of(2026, 1, 1), patched.get().startDate());
    }

    @Test
    void patch_shouldSetStartDate() {
        UUID mobileHomeId = mobileHomeOn("SP015", "1");
        SecuredPartyRow row = securedPartyRepository.save(
                mobileHomeId, person("First National Bank"), LocalDate.of(2026, 1, 1)).orElseThrow();

        Optional<SecuredPartyRow> patched = securedPartyRepository.patch(
                row.uuid(), Map.of("start_date", LocalDate.of(2026, 2, 1)));

        assertEquals(LocalDate.of(2026, 2, 1), patched.orElseThrow().startDate());
    }

    @Test
    void patch_shouldSetAcceptPayments() {
        UUID mobileHomeId = mobileHomeOn("SP016", "1");
        SecuredPartyRow row = securedPartyRepository.save(
                mobileHomeId, person("First National Bank"), LocalDate.of(2026, 1, 1)).orElseThrow();

        Optional<SecuredPartyRow> patched = securedPartyRepository.patch(
                row.uuid(), Map.of("accept_payments", true));

        assertTrue(patched.orElseThrow().acceptPayments());
    }

    // Whitelist rejections throw in Java before any SQL is sent, so unlike the DB
    // constraint tests they can share one transaction (and one test method).
    @Test
    void patch_shouldRejectAColumnOutsideTheAllowedSet() {
        UUID mobileHomeId = mobileHomeOn("SP017", "1");
        SecuredPartyRow row = securedPartyRepository.save(
                mobileHomeId, person("First National Bank"), LocalDate.of(2026, 1, 1)).orElseThrow();

        assertThrows(InvalidDataAccessApiUsageException.class,
                () -> securedPartyRepository.patch(row.uuid(), Map.of("person_id", UUID.randomUUID())));
        assertThrows(InvalidDataAccessApiUsageException.class,
                () -> securedPartyRepository.patch(row.uuid(), Map.of("mobile_home_id", UUID.randomUUID())));
        assertThrows(InvalidDataAccessApiUsageException.class,
                () -> securedPartyRepository.patch(row.uuid(), Map.of("uuid", UUID.randomUUID())));
    }

    @Test
    void patch_shouldReturnEmpty_forASoftDeletedRow() {
        UUID mobileHomeId = mobileHomeOn("SP018", "1");
        SecuredPartyRow row = securedPartyRepository.save(
                mobileHomeId, person("First National Bank"), LocalDate.of(2026, 1, 1)).orElseThrow();
        securedPartyRepository.softDelete(row.uuid());

        assertTrue(securedPartyRepository.patch(
                row.uuid(), Map.of("end_date", LocalDate.of(2026, 6, 30))).isEmpty());
    }

    // ── softDelete ───────────────────────────────────────────────────────────────

    @Test
    void softDelete_shouldReturnFalse_onASecondCall() {
        UUID mobileHomeId = mobileHomeOn("SP019", "1");
        SecuredPartyRow row = securedPartyRepository.save(
                mobileHomeId, person("First National Bank"), LocalDate.of(2026, 1, 1)).orElseThrow();

        assertTrue(securedPartyRepository.softDelete(row.uuid()));
        assertFalse(securedPartyRepository.softDelete(row.uuid()));
    }
}
