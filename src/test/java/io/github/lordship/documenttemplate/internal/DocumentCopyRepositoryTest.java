package io.github.lordship.documenttemplate.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// Copying a document's wording into a new document ("Start from" on New document). The original here has one
// of every link a clause can carry, and one deleted row of each kind:
//   style "notice", and a deleted style
//   section "Lease" wearing "notice", and a deleted section with one clause
//   Water; Valve under Water, wearing "notice"; Bank; Flat standing in for Bank;
//   Notice kept directly before Signature and citing Water; Signature;
//   a deleted clause; Rent citing the deleted clause
@Transactional
public class DocumentCopyRepositoryTest extends IntegrationTest {

    // The SYSTEM agent seeded in V10: who the fixtures below are created by.
    static final UUID SYSTEM_AGENT = UUID.fromString("00000000-0000-7000-8000-000000000002");

    @Autowired
    DocumentTemplateRepository documentTemplateRepository;

    @Autowired
    DocumentStyleRepository documentStyleRepository;

    @Autowired
    DocumentSectionRepository documentSectionRepository;

    @Autowired
    TemplateClauseRepository templateClauseRepository;

    @Autowired
    DocumentCopyRepository documentCopyRepository;

    private UUID original;
    private UUID copy;
    private UUID notice;
    private UUID lease;
    private UUID water;
    private UUID bank;
    private UUID signature;
    private UUID deletedClause;

    @BeforeEach
    void setUp() {
        original = documentTemplateRepository
                .save("WA Land Lease 2026", AgreementType.LAND, InstrumentType.LEASE, SYSTEM_AGENT).uuid();
        copy = documentTemplateRepository
                .save("WA Land Lease 2027", AgreementType.LAND, InstrumentType.LEASE, SYSTEM_AGENT).uuid();

        notice = documentStyleRepository.save(original, "notice", SYSTEM_AGENT).uuid();
        documentStyleRepository.patch(notice, Map.of("css", "font-weight: bold;"));
        documentStyleRepository.softDelete(documentStyleRepository.save(original, "old look", SYSTEM_AGENT).uuid());

        lease = documentSectionRepository.save(original, "Lease", SYSTEM_AGENT).uuid();
        documentSectionRepository.patch(lease, Map.of("style", notice));
        UUID deletedSection = documentSectionRepository.save(original, "Old addendum", SYSTEM_AGENT).uuid();
        clause(deletedSection, "Old rule", Map.of());
        documentSectionRepository.softDelete(deletedSection);

        water = clause(lease, "Water", Map.of());
        clause(lease, "Valve", Map.of("parent", water, "style", notice));
        bank = clause(lease, "Bank", Map.of());
        clause(lease, "Flat", Map.of("variant_of", bank));
        UUID noticeClause = clause(lease, "Notice", Map.of("body", "See {{ref:" + water + "}}."));
        signature = clause(lease, "Signature", Map.of());
        templateClauseRepository.patch(noticeClause, Map.of("requires_next", signature));
        deletedClause = clause(lease, "Old clause", Map.of());
        templateClauseRepository.softDelete(deletedClause);
        clause(lease, "Rent", Map.of("body", "As in {{ref:" + deletedClause + "}}."));
    }

    // A clause with a title, and whatever else the test says about the clause (columns of template_clause)
    private UUID clause(UUID sectionId, String title, Map<String, Object> more) {
        UUID id = templateClauseRepository.save(sectionId, SYSTEM_AGENT).uuid();
        templateClauseRepository.patch(id, Map.of("title", title));
        if (!more.isEmpty()) {
            templateClauseRepository.patch(id, more);
        }
        return id;
    }

    private TemplateClauseRow titled(List<TemplateClauseRow> clauses, String title) {
        return clauses.stream().filter(c -> title.equals(c.title())).findFirst().orElseThrow();
    }

    @Test
    void copyWording_shouldPointEveryLinkAndCitation_atTheCopies() {
        // Act
        Map<UUID, UUID> ids = documentCopyRepository.copyWording(original, copy, SYSTEM_AGENT);

        // Assert -- the copy has a style of the copy's own, and the copy's section wears that style
        List<DocumentStyleRow> styles = documentStyleRepository.findByTemplate(copy);
        assertEquals(List.of(ids.get(notice)), styles.stream().map(DocumentStyleRow::uuid).toList());
        assertEquals("font-weight: bold;", styles.get(0).css());
        List<DocumentSectionRow> sections = documentSectionRepository.findByTemplate(copy);
        assertEquals(List.of(ids.get(lease)), sections.stream().map(DocumentSectionRow::uuid).toList());
        assertEquals(ids.get(notice), sections.get(0).style());

        // each link of a copied clause names another copied clause, never a clause of the original
        List<TemplateClauseRow> clauses = templateClauseRepository.findBySection(ids.get(lease));
        TemplateClauseRow valve = titled(clauses, "Valve");
        assertEquals(ids.get(water), valve.parent());
        assertEquals(ids.get(notice), valve.style());
        assertEquals(ids.get(bank), titled(clauses, "Flat").variantOf());
        TemplateClauseRow noticeCopy = titled(clauses, "Notice");
        assertEquals(ids.get(signature), noticeCopy.requiresNext());
        assertEquals("See {{ref:" + ids.get(water) + "}}.", noticeCopy.body());

        // the copies keep the order of the original
        assertEquals(List.of("Water", "Valve", "Bank", "Flat", "Notice", "Signature", "Rent"),
                clauses.stream().map(TemplateClauseRow::title).toList());

        // and the original still cites the original's Water
        assertEquals("See {{ref:" + water + "}}.",
                titled(templateClauseRepository.findBySection(lease), "Notice").body());
    }

    @Test
    void copyWording_shouldLeaveDeletedRowsBehind() {
        // Act
        Map<UUID, UUID> ids = documentCopyRepository.copyWording(original, copy, SYSTEM_AGENT);

        // Assert -- one style, one section and seven clauses were live
        assertEquals(9, ids.size());
        assertFalse(ids.containsKey(deletedClause));
        assertEquals(1, documentStyleRepository.findByTemplate(copy).size());
        assertEquals(1, documentSectionRepository.findByTemplate(copy).size());
        assertTrue(documentStyleRepository.findDeletedByTemplate(copy).isEmpty());
        assertTrue(documentSectionRepository.findDeletedByTemplate(copy).isEmpty());
        assertTrue(templateClauseRepository.findDeletedBySection(ids.get(lease)).isEmpty());

        // a citation of a clause that was not copied is left as written
        TemplateClauseRow rent = titled(templateClauseRepository.findBySection(ids.get(lease)), "Rent");
        assertEquals("As in {{ref:" + deletedClause + "}}.", rent.body());
    }
}
