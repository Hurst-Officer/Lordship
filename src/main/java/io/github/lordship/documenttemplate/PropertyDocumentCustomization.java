package io.github.lordship.documenttemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * What one park changes about a document it was assigned. A community on city
 * sewer drops the whole septic section; a park with a rule of its own adds a
 * clause.
 *
 * <p>Which fields mean anything depends on the action, which is why they are
 * nullable and why {@code excludes} / {@code adds} read the intent rather than
 * every caller testing columns:
 *
 * <ul>
 *   <li>EXCLUDE_SECTION -- {@code section}
 *   <li>EXCLUDE_CLAUSE -- {@code clause}
 *   <li>ADD_CLAUSE -- {@code section} receives it, plus ordinal, title, body
 * </ul>
 *
 * <p>A park-authored clause may be conditional on the same terms a template
 * clause may, so a park that charges flat water can write its own flat-water
 * paragraph without it reaching a tenant on RUBS.
 */
public record PropertyDocumentCustomization(
        UUID uuid,
        UUID assignment,
        CustomizationAction action,
        UUID section,
        UUID clause,
        BigDecimal ordinal,
        String title,
        String body,
        String conditionField,
        List<String> conditionValues,
        String note,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {
    public PropertyDocumentCustomization {
        conditionValues = (conditionValues == null) ? List.of() : List.copyOf(conditionValues);
    }

    public boolean isSoftDeleted() {
        return deletedAt != null;
    }

    /** The section this row keeps out of the document, or empty when it is not an exclusion. */
    public UUID excludedSection() {
        return action == CustomizationAction.EXCLUDE_SECTION ? section : null;
    }

    public UUID excludedClause() {
        return action == CustomizationAction.EXCLUDE_CLAUSE ? clause : null;
    }

    public boolean addsClause() {
        return action == CustomizationAction.ADD_CLAUSE;
    }

    public boolean isConditional() {
        return conditionField != null;
    }

    /**
     * Whether this deal gets this clause -- the same rule as
     * {@code TemplateClause.appliesTo}, including the explicit null check. A
     * method that was never supplied does not print a clause that branches on
     * it, and {@code List.of(...)} throws on a null probe rather than answering
     * false.
     */
    public boolean appliesTo(String conditionFieldValue) {
        if (!isConditional()) {
            return true;
        }
        return conditionFieldValue != null && conditionValues.contains(conditionFieldValue);
    }
}
