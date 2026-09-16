package io.github.lordship.documenttemplate;

import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One park's decision to use one document. Not a document itself -- a whitelist
 * row, which is why the table reads {@code property_document_assignment} rather
 * than {@code property_document}.
 *
 * <p>A reference, not a copy. Editing the global template reaches every park
 * assigned to it, which is the opposite of how terms work: a terms template is
 * copied into a property so a later edit cannot retroactively change what
 * existing tenants owe. Words propagate so a statutory fix lands everywhere;
 * money does not so a price change cannot rewrite history. Documents already
 * generated are protected either way, because their wording was snapshotted
 * onto the instrument.
 *
 * <p>{@code agreementType} and {@code instrumentType} are carried on the
 * assignment rather than read through the template, so a unique index can stop
 * one park holding two documents that both answer to "the lease". A composite
 * foreign key keeps them honest against the template they came from.
 *
 * <p>{@code customizations} rides along because the freeze needs the document
 * and this park's changes to it at the same moment. Fetching them separately
 * invites the case where one is stale.
 */
public record PropertyDocumentAssignment(
        UUID uuid,
        UUID propertyId,
        AgreementType agreementType,
        InstrumentType instrumentType,
        String note,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt,
        DocumentTemplate document,
        List<PropertyDocumentCustomization> customizations
) {
    public PropertyDocumentAssignment {
        customizations = (customizations == null) ? List.of() : List.copyOf(customizations);
    }

    public boolean isSoftDeleted() {
        return deletedAt != null;
    }

    /** What the office worker sees in a list, without a second lookup. */
    public String documentName() {
        return document == null ? null : document.name();
    }

    /** The wording version in force for this park today, which a render will freeze. */
    public Integer documentVersion() {
        return document == null ? null : document.version();
    }

    /** Whether this park takes the document as written, which most do. */
    public boolean isCustomized() {
        return !customizations.isEmpty();
    }
}
