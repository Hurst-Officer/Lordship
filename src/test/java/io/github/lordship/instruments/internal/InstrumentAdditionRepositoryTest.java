package io.github.lordship.instruments.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.documenttemplate.internal.DocumentSectionRepository;
import io.github.lordship.documenttemplate.internal.DocumentTemplateRepository;
import io.github.lordship.documenttemplate.internal.DocumentTemplateRow;
import io.github.lordship.documenttemplate.internal.PropertyDocumentAssignmentRepository;
import io.github.lordship.documenttemplate.internal.PropertyDocumentCustomizationRepository;
import io.github.lordship.documenttemplate.internal.TemplateClauseRepository;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
public class InstrumentAdditionRepositoryTest extends IntegrationTest {

    // The SYSTEM agent seeded in V10, which is what V11 attributes its rows to.
    static final UUID SYSTEM_AGENT = UUID.fromString("00000000-0000-7000-8000-000000000002");

    @Autowired DocumentTemplateRepository documentTemplateRepository;
    @Autowired DocumentSectionRepository documentSectionRepository;
    @Autowired TemplateClauseRepository templateClauseRepository;
    @Autowired PropertyDocumentAssignmentRepository assignmentRepository;
    @Autowired PropertyDocumentCustomizationRepository customizationRepository;
    @Autowired InstrumentRepository instrumentRepository;
    @Autowired InstrumentAdditionRepository additionRepository;

    /**
     * A park with a document assigned, a tenancy on one of its lots, and a
     * drafted instrument for it -- the smallest world in which a typed clause
     * means anything.
     *
     * <p>Built through other modules' repositories rather than their services:
     * a service call pulls in the audit write, which has no principal here.
     */
    private record World(UUID propertyId, UUID templateId, UUID sectionId,
                         UUID assignmentId, UUID instrumentId) { }

    private World world(String propertyCode) {
        UUID propertyId = testData.insertProperty(propertyCode).uuid();
        UUID lotId = testData.insertLot(propertyId, "1").uuid();
        UUID tenancyId = testData.insertTenancy(lotId).uuid();

        DocumentTemplateRow template = documentTemplateRepository.save(
                "WA Land Lease " + propertyCode, AgreementType.LAND, InstrumentType.LEASE, SYSTEM_AGENT);
        UUID sectionId = documentSectionRepository.save(template.uuid(), "Rules", SYSTEM_AGENT).uuid();
        UUID assignmentId = assignmentRepository.save(propertyId, template.uuid(),
                template.agreementType(), template.instrumentType(), SYSTEM_AGENT).uuid();
        UUID instrumentId = instrumentRepository.save(
                tenancyId, InstrumentType.LEASE, AgreementType.LAND, SYSTEM_AGENT).uuid();

        return new World(propertyId, template.uuid(), sectionId, assignmentId, instrumentId);
    }

    private void templateClause(UUID sectionId, String ordinal) {
        UUID clause = templateClauseRepository.save(sectionId, SYSTEM_AGENT).uuid();
        templateClauseRepository.patch(clause, Map.of("ordinal", new BigDecimal(ordinal)));
    }

    private void parkClause(UUID assignmentId, UUID sectionId, String ordinal) {
        UUID added = customizationRepository.addClause(assignmentId, sectionId, SYSTEM_AGENT).uuid();
        customizationRepository.patch(added, Map.of("ordinal", new BigDecimal(ordinal)));
    }

    // ---- save ----------------------------------------------------------------

    @Test
    void save_shouldPersistAnEmptyClause_withOnlyItsInstrumentAndSection() {
        // Arrange
        World w = world("A001");

        // Act -- the body is a patch away, so "add clause" is a button
        InstrumentAdditionRow saved = additionRepository.save(w.instrumentId(), w.sectionId(), SYSTEM_AGENT);

        // Assert
        assertNotNull(saved.uuid());
        assertEquals(w.instrumentId(), saved.instrument());
        assertEquals(w.sectionId(), saved.section());
        assertNull(saved.title());
        assertNull(saved.body());
        assertNotNull(saved.ordinal());
        assertNotNull(saved.createdAt());
        assertNull(saved.deletedAt());
    }

    @Test
    void save_shouldLandAfterTheTemplatesLastClause() {
        // Arrange -- template clauses at 10 and 20
        World w = world("A002");
        templateClause(w.sectionId(), "10");
        templateClause(w.sectionId(), "20");

        // Act
        InstrumentAdditionRow saved = additionRepository.save(w.instrumentId(), w.sectionId(), SYSTEM_AGENT);

        // Assert
        assertEquals(0, new BigDecimal("21").compareTo(saved.ordinal()));
    }

    @Test
    void save_shouldLandAfterThisParksOwnClause() {
        // Arrange -- the park's rule sits above anything typed onto one lease
        World w = world("A003");
        templateClause(w.sectionId(), "10");
        parkClause(w.assignmentId(), w.sectionId(), "30");

        // Act
        InstrumentAdditionRow saved = additionRepository.save(w.instrumentId(), w.sectionId(), SYSTEM_AGENT);

        // Assert -- all three tables share one coordinate space, so counting
        // only two would put this clause on top of the park's rule
        assertEquals(0, new BigDecimal("31").compareTo(saved.ordinal()));
    }

    @Test
    void save_shouldLandAfterThisAgreementsPreviousTypedClause() {
        // Arrange
        World w = world("A004");
        templateClause(w.sectionId(), "10");

        // Act
        additionRepository.save(w.instrumentId(), w.sectionId(), SYSTEM_AGENT);
        InstrumentAdditionRow second = additionRepository.save(w.instrumentId(), w.sectionId(), SYSTEM_AGENT);

        // Assert
        assertEquals(0, new BigDecimal("12").compareTo(second.ordinal()));
    }

    @Test
    void save_shouldIgnoreAnotherAgreementsTypedClauses() {
        // Arrange -- what one tenant agreed to does not shift anybody else's numbering
        World mine = world("A005");
        World theirs = world("A006");
        templateClause(mine.sectionId(), "10");
        templateClause(theirs.sectionId(), "10");

        additionRepository.save(theirs.instrumentId(), theirs.sectionId(), SYSTEM_AGENT);
        additionRepository.save(theirs.instrumentId(), theirs.sectionId(), SYSTEM_AGENT);

        // Act
        InstrumentAdditionRow saved = additionRepository.save(mine.instrumentId(), mine.sectionId(), SYSTEM_AGENT);

        // Assert
        assertEquals(0, new BigDecimal("11").compareTo(saved.ordinal()));
    }

    // ---- patch ---------------------------------------------------------------

    @Test
    void patch_shouldWriteTheWordsOntoTheClause() {
        // Arrange -- the shed the seller left behind
        World w = world("A007");
        InstrumentAdditionRow saved = additionRepository.save(w.instrumentId(), w.sectionId(), SYSTEM_AGENT);

        // Act
        InstrumentAdditionRow patched = additionRepository.patch(saved.uuid(),
                Map.of("title", "Shed",
                        "body", "Tenant may keep the existing shed until June 1.")).orElseThrow();

        // Assert
        assertEquals("Shed", patched.title());
        assertEquals("Tenant may keep the existing shed until June 1.", patched.body());
    }

    @Test
    void patch_shouldMoveAClauseToASparseOrdinal() {
        // Arrange
        World w = world("A008");
        InstrumentAdditionRow saved = additionRepository.save(w.instrumentId(), w.sectionId(), SYSTEM_AGENT);

        // Act -- 12.5 sits between the twelfth and thirteenth, nothing else moves
        InstrumentAdditionRow moved = additionRepository
                .patch(saved.uuid(), Map.of("ordinal", new BigDecimal("12.5"))).orElseThrow();

        // Assert
        assertEquals(0, new BigDecimal("12.5").compareTo(moved.ordinal()));
    }

    @Test
    void patch_shouldRefuseToMoveAClauseToAnotherSection() {
        // Arrange -- where a sentence goes is what it is; moving it is
        // remove-and-retype, which is also the honest record of what happened
        World w = world("A009");
        InstrumentAdditionRow saved = additionRepository.save(w.instrumentId(), w.sectionId(), SYSTEM_AGENT);

        // Act + Assert
        assertThrows(InvalidDataAccessApiUsageException.class,
                () -> additionRepository.patch(saved.uuid(), Map.of("section", UUID.randomUUID())));
    }

    // ---- reading and removing ------------------------------------------------

    @Test
    void findByInstrument_shouldReturnOnlyThisAgreementsClauses() {
        // Arrange
        World mine = world("A010");
        World theirs = world("A011");
        InstrumentAdditionRow shed = additionRepository.save(mine.instrumentId(), mine.sectionId(), SYSTEM_AGENT);
        additionRepository.save(theirs.instrumentId(), theirs.sectionId(), SYSTEM_AGENT);

        // Act
        List<InstrumentAdditionRow> found = additionRepository.findByInstrument(mine.instrumentId());

        // Assert
        assertEquals(1, found.size());
        assertEquals(shed.uuid(), found.get(0).uuid());
    }

    @Test
    void softDelete_shouldTakeTheSentenceBack() {
        // Arrange -- the assistant changed their mind before generating
        World w = world("A012");
        InstrumentAdditionRow saved = additionRepository.save(w.instrumentId(), w.sectionId(), SYSTEM_AGENT);

        // Act
        boolean removed = additionRepository.softDelete(saved.uuid());

        // Assert
        assertTrue(removed);
        assertEquals(Optional.empty(), additionRepository.findById(saved.uuid()));
        assertEquals(List.of(), additionRepository.findByInstrument(w.instrumentId()));
    }

    @Test
    void softDelete_shouldFreeItsOrdinalForTheNextClause() {
        // Arrange
        World w = world("A013");
        templateClause(w.sectionId(), "10");
        InstrumentAdditionRow first = additionRepository.save(w.instrumentId(), w.sectionId(), SYSTEM_AGENT);
        additionRepository.softDelete(first.uuid());

        // Act
        InstrumentAdditionRow second = additionRepository.save(w.instrumentId(), w.sectionId(), SYSTEM_AGENT);

        // Assert -- a retyped clause takes the place of the one it replaces
        assertEquals(0, new BigDecimal("11").compareTo(second.ordinal()));
    }

    @Test
    void softDelete_shouldReturnFalse_whenItWasAlreadyTakenBack() {
        // Arrange
        World w = world("A014");
        InstrumentAdditionRow saved = additionRepository.save(w.instrumentId(), w.sectionId(), SYSTEM_AGENT);
        additionRepository.softDelete(saved.uuid());

        // Act + Assert
        assertFalse(additionRepository.softDelete(saved.uuid()));
    }
}
