package io.github.lordship.documenttemplate;

import io.github.lordship.shared.DocumentToken;
import io.github.lordship.shared.TokenSyntax;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One clause of a sub-document. {@code body} carries {@code {{tokens}}} and
 * never a literal amount, which is what stops a lease stating a late fee the
 * charge term does not.
 *
 * <p>A clause whose wording depends on a method is authored once per method and
 * selected at generate, rather than branching inside the sentence. The NSF
 * clause is three rows: one for BANK_OR_FLAT, one for FLAT, and none at all for
 * NONE. Every body stays editable; what an author cannot do is attach
 * whichever-is-greater wording to a flat-fee deal.
 */
public record TemplateClause(
        UUID uuid,
        BigDecimal ordinal,
        String clauseKey,
        String title,
        String body,
        String conditionField,
        List<String> conditionValues,
        boolean required,
        String statuteRef,
        String note,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {
    public TemplateClause {
        conditionValues = (conditionValues == null) ? List.of() : List.copyOf(conditionValues);
    }

    public boolean isSoftDeleted() {
        return deletedAt != null;
    }

    public boolean isConditional() {
        return conditionField != null;
    }

    /**
     * Whether this clause prints, given the term's value for {@code
     * conditionField}. An unconditional clause always prints and ignores the
     * argument.
     *
     * <p>A null value means the method was never supplied, and a conditional
     * clause does not print for it -- preview of a half-specified deal should
     * show the holes rather than a plausible-looking document. The explicit
     * null check is not decoration: {@code List.of(...)} throws on a null probe
     * rather than answering false.
     */
    public boolean appliesTo(String conditionFieldValue) {
        if (!isConditional()) {
            return true;
        }
        return conditionFieldValue != null && conditionValues.contains(conditionFieldValue);
    }

    /**
     * The token names this body references, without the braces, in the order
     * they appear. Used to reject an unknown token when the clause is saved,
     * and to answer which figures a document actually depends on.
     */
    public Set<String> tokenNames() {
        return tokenNamesIn(body);
    }

    /**
     * Tokens in this body whose value depends on a method the clause does not
     * condition on -- so the clause can print a figure that is not really
     * there. The one that matters: {@code term.late_fee_amount} shares its
     * column with {@code term.late_fee_percent}, so an unconditioned clause
     * renders "$1.50" for a tenant on 1.5% of rent.
     *
     * <p>Guarded means the clause conditions on exactly that method, and every
     * value it names is one that populates the token. Conditioning on
     * {FLAT, PERCENT_OF_RENT} does not guard a FLAT-only amount.
     *
     * <p>A warning, not an error: a summary clause that lists several fees at
     * once is legitimate, and the author is the one who can tell the difference.
     */
    public List<String> unguardedTokens() {
        List<String> unguarded = new ArrayList<>();
        for (String name : tokenNames()) {
            DocumentToken token = DocumentToken.of(name).orElse(null);
            if (token == null) {
                continue;
            }
            DocumentToken governor = token.governedBy().orElse(null);
            if (governor == null) {
                continue;
            }
            boolean guarded = governor.token().equals(conditionField)
                    && !conditionValues.isEmpty()
                    && token.populatedWhen().containsAll(conditionValues);
            if (!guarded) {
                unguarded.add(name);
            }
        }
        return List.copyOf(unguarded);
    }

    /**
     * The same parse against a body that is not a clause yet -- what the
     * service validates before a save, so an unknown token is refused rather
     * than stored.
     */
    public static Set<String> tokenNamesIn(String body) {
        return TokenSyntax.tokenNamesIn(body);
    }

    /**
     * Row tokens this body uses where they cannot resolve -- outside a repeat
     * block, or inside one over the wrong list.
     *
     * <p>Unlike {@link #unguardedTokens()} this is an error, not a warning.
     * An unguarded token at least has a value; a misplaced row token has none,
     * and would print as a stranded {@code {{rent_step.rate}}} on a lease.
     */
    public List<String> misplacedRowTokens() {
        return misplacedRowTokensIn(body);
    }

    /**
     * The same parse against a body that is not a clause yet -- what the
     * service validates before a save, alongside {@link #tokenNamesIn(String)}.
     */
    public static List<String> misplacedRowTokensIn(String body) {
        return TokenSyntax.misplacedRowTokens(body);
    }
}