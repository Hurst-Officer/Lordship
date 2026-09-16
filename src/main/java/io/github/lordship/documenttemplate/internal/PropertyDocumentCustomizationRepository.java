package io.github.lordship.documenttemplate.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What one park changes about a document it was assigned.
 *
 * <p>Three save methods rather than one, because the three actions have three
 * different minimums: an exclusion needs the thing it excludes and nothing
 * else, and an added clause needs a home and a position. One generic save would
 * take six nullable arguments and let five of the six wrong combinations
 * through.
 */
@Repository
public class PropertyDocumentCustomizationRepository {

    // action, assignment, section and clause are set once: what a row does and
    // what it does it to are its identity. Only the added clause's own content
    // moves.
    private static final Set<String> PATCHABLE_COLUMNS = Set.of(
            "ordinal", "title", "body", "condition_field", "condition_values", "note"
    );

    private final JdbcClient jdbc;
    private final PropertyDocumentCustomizationRowMapper rowMapper;

    public PropertyDocumentCustomizationRepository(
            JdbcClient jdbc, PropertyDocumentCustomizationRowMapper propertyDocumentCustomizationRowMapper) {
        this.jdbc = jdbc;
        this.rowMapper = propertyDocumentCustomizationRowMapper;
    }

    /** This park does not use that sub-document at all -- city sewer, no septic addendum. */
    public PropertyDocumentCustomizationRow excludeSection(UUID assignment, UUID section, UUID createdBy) {
        return jdbc.sql("""
                        INSERT INTO property_document_customization (
                            assignment, action, section, created_by
                        ) VALUES (
                            :assignment, 'EXCLUDE_SECTION', :section, :createdBy
                        )
                        RETURNING *
                        """)
                .param("assignment", assignment)
                .param("section", section)
                .param("createdBy", createdBy)
                .query(rowMapper)
                .single();
    }

    /** This park keeps the section but not that one paragraph of it. */
    public PropertyDocumentCustomizationRow excludeClause(UUID assignment, UUID clause, UUID createdBy) {
        return jdbc.sql("""
                        INSERT INTO property_document_customization (
                            assignment, action, clause, created_by
                        ) VALUES (
                            :assignment, 'EXCLUDE_CLAUSE', :clause, :createdBy
                        )
                        RETURNING *
                        """)
                .param("assignment", assignment)
                .param("clause", clause)
                .param("createdBy", createdBy)
                .query(rowMapper)
                .single();
    }

    /**
     * A park rule of its own. Empty, like adding a template clause: the body is
     * a patch away, so "add clause" stays a button rather than a form.
     *
     * <p>The ordinal lands it at the end of the section AS THIS PARK SEES IT,
     * which is why the subquery reads both tables. Template clauses and a
     * park's own clauses share one coordinate space -- that is what lets a park
     * later move its rule to 12.5 and sit between the template's twelfth and
     * thirteenth. Counting only one table would put two clauses on the same
     * number and leave the print order to chance.
     */
    public PropertyDocumentCustomizationRow addClause(UUID assignment, UUID section, UUID createdBy) {
        return jdbc.sql("""
                        INSERT INTO property_document_customization (
                            assignment, action, section, ordinal, created_by
                        ) VALUES (
                            :assignment,
                            'ADD_CLAUSE',
                            :section,
                            (SELECT COALESCE(MAX(ordinal), 0) + 1 FROM (
                                 SELECT ordinal FROM template_clause
                                  WHERE section = :section AND deleted_at IS NULL
                                 UNION ALL
                                 SELECT ordinal FROM property_document_customization
                                  WHERE assignment = :assignment
                                    AND action = 'ADD_CLAUSE'
                                    AND section = :section
                                    AND deleted_at IS NULL
                             ) taken),
                            :createdBy
                        )
                        RETURNING *
                        """)
                .param("assignment", assignment)
                .param("section", section)
                .param("createdBy", createdBy)
                .query(rowMapper)
                .single();
    }

    public Optional<PropertyDocumentCustomizationRow> findById(UUID uuid) {
        return jdbc.sql("""
                        SELECT * FROM property_document_customization
                         WHERE uuid = :uuid AND deleted_at IS NULL
                        """)
                .param("uuid", uuid)
                .query(rowMapper)
                .optional();
    }

    /** Everything this park changed about this document -- what the freeze reads. */
    public List<PropertyDocumentCustomizationRow> findByAssignment(UUID assignment) {
        return jdbc.sql("""
                        SELECT * FROM property_document_customization
                         WHERE assignment = :assignment AND deleted_at IS NULL
                         ORDER BY action, ordinal
                        """)
                .param("assignment", assignment)
                .query(rowMapper)
                .list();
    }

    // Hydrating a park's whole document list is two queries, not one per row.
    public List<PropertyDocumentCustomizationRow> findByAssignmentIds(Collection<UUID> assignmentIds) {
        if (assignmentIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT * FROM property_document_customization
                         WHERE assignment IN (:assignmentIds) AND deleted_at IS NULL
                         ORDER BY assignment, action, ordinal
                        """)
                .param("assignmentIds", assignmentIds)
                .query(rowMapper)
                .list();
    }

    /**
     * Whether this park already says this about this target. Adding the same
     * exclusion twice is harmless to the freeze and confusing on the screen, so
     * the service turns a second one into a conflict rather than a duplicate.
     */
    public Optional<PropertyDocumentCustomizationRow> findExclusion(UUID assignment, UUID target) {
        return jdbc.sql("""
                        SELECT * FROM property_document_customization
                         WHERE assignment = :assignment
                           AND action IN ('EXCLUDE_SECTION', 'EXCLUDE_CLAUSE')
                           AND (section = :target OR clause = :target)
                           AND deleted_at IS NULL
                        """)
                .param("assignment", assignment)
                .param("target", target)
                .query(rowMapper)
                .optional();
    }

    /** Which parks dropped a section or a clause -- the list behind a refusal to retire it. */
    public List<PropertyDocumentCustomizationRow> findReferencing(UUID target) {
        return jdbc.sql("""
                        SELECT * FROM property_document_customization
                         WHERE (section = :target OR clause = :target) AND deleted_at IS NULL
                         ORDER BY created_at
                        """)
                .param("target", target)
                .query(rowMapper)
                .list();
    }

    public Optional<PropertyDocumentCustomizationRow> patch(UUID uuid, Map<String, Object> changes) {
        if (changes.isEmpty()) return findById(uuid);

        for (String col : changes.keySet()) {
            if (!PATCHABLE_COLUMNS.contains(col)) {
                throw new IllegalArgumentException("Invalid column: " + col);
            }
        }

        StringBuilder sql = new StringBuilder("UPDATE property_document_customization SET ");
        changes.forEach((col, val) -> {
            sql.append(col).append(" = :").append(col);
            // The driver needs to be told a String[] is a text[], not a record.
            if ("condition_values".equals(col)) {
                sql.append("::text[]");
            }
            sql.append(", ");
        });
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *");

        Map<String, Object> params = new HashMap<>(changes);
        // Not computeIfPresent: it deletes the key when the function returns
        // null, and clearing a condition is exactly the case that returns null.
        // The column is still named in the SET clause, so the parameter has to
        // stay in the map with a null value.
        if (params.containsKey("condition_values")) {
            params.put("condition_values", toStringArray(params.get("condition_values")));
        }
        params.put("uuid", uuid);

        return jdbc.sql(sql.toString())
                .params(params)
                .query(rowMapper)
                .optional();
    }

    /** Undoing a customization puts the document back the way the template wrote it. */
    public boolean softDelete(UUID uuid) {
        return jdbc.sql("""
                        UPDATE property_document_customization SET deleted_at = CURRENT_TIMESTAMP
                         WHERE uuid = :uuid AND deleted_at IS NULL
                        """)
                .param("uuid", uuid)
                .update() > 0;
    }

    // JSON gives us a List; Postgres wants an array. An empty list clears the
    // condition rather than storing {}, so a clause can be made unconditional
    // the same way any other field is cleared.
    private static Object toStringArray(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Collection<?> collection) {
            if (collection.isEmpty()) {
                return null;
            }
            return collection.stream().map(String::valueOf).toArray(String[]::new);
        }
        if (value instanceof String[] array) {
            return array.length == 0 ? null : array;
        }
        throw new IllegalArgumentException("conditionValues must be a list of strings");
    }
}
