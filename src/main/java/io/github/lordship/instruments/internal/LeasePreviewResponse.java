package io.github.lordship.instruments.internal;

import io.github.lordship.instruments.DocumentFreeze;
import io.github.lordship.instruments.LeasePreview;
import io.github.lordship.shared.DocumentToken;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The lease as it would come out, and everything wrong with it.
 *
 * <p>{@code complete} is the one field the Generate button reads. The problem
 * lists are what the office worker reads: a value nothing could fill, a section
 * the statute requires that ended up with nothing in it, a reference to a
 * clause that did not print, and a pair of clauses that came apart.
 *
 * <p>Each missing value carries the {@code source} it would have come from,
 * because {@code landlord.name} names the hole and not the fix. A property
 * saved with only a name and an address has no entity on it yet, and what she
 * needs to be told is which screen to go to.
 *
 * <p>Both the source and the sentence, the same way a refusal carries both its
 * code and its message: the sentence is resolved here against the request's
 * Accept-Language, and the source is what a frontend matches on if it would
 * rather word it differently.
 *
 * <p>Clause bodies arrive with the figures already substituted and any
 * unfillable token still standing in the text, and each clause carries the
 * tokens it could not fill. The list at the top says what to go and fix; the
 * list on the clause says where to look. Matching the two is a string compare
 * on the token name, which is why the clause does not repeat the wording.
 */
public record LeasePreviewResponse(
        UUID instrumentId,
        UUID documentTemplateId,
        String documentName,
        Integer documentVersion,
        boolean complete,
        List<MissingValue> unresolved,
        List<String> omittedRequired,
        List<String> brokenReferences,
        List<String> separatedPairs,
        List<Section> sections
) {

    /**
     * A value the document asked for and nothing could supply.
     *
     * <p>{@code source} is where it would have come from, so the screen can
     * point at the property, the deal or the paper rather than printing a token
     * name at somebody. Unknown when a clause names a token the registry has
     * since dropped, which is a template problem rather than a data one.
     */
    public record MissingValue(String token, String source, String message) {

        static MissingValue of(String token, MessageSource messages) {
            String source = DocumentToken.of(token)
                    .map(known -> known.source().name())
                    .orElse("UNKNOWN");
            String key = "token.source." + source;
            return new MissingValue(token, source,
                    messages.getMessage(key, null, key, LocaleContextHolder.getLocale()));
        }
    }

    public record Section(
            BigDecimal ordinal,
            String name,
            String sectionKey,
            boolean signatureBlock,
            boolean listedAsAddendum,
            String statuteRef,
            List<Clause> clauses) { }

    public record Clause(
            BigDecimal ordinal,
            String clauseKey,
            String title,
            String number,
            String label,
            int depth,
            String body,
            String statuteRef,
            String origin,
            List<String> unresolved) { }

    public static LeasePreviewResponse from(LeasePreview preview, MessageSource messages) {
        DocumentFreeze.Frozen frozen = preview.frozen();
        return new LeasePreviewResponse(
                preview.instrument(),
                preview.documentTemplate(),
                preview.documentName(),
                preview.documentVersion(),
                preview.isComplete(),
                frozen.unresolved().stream().map(token -> MissingValue.of(token, messages)).toList(),
                frozen.omittedRequired(),
                frozen.brokenReferences(),
                frozen.separatedPairs(),
                frozen.sections().stream().map(LeasePreviewResponse::section).toList());
    }

    private static Section section(DocumentFreeze.FrozenSection s) {
        return new Section(s.ordinal(), s.name(), s.sectionKey(), s.signatureBlock(),
                s.listedAsAddendum(), s.statuteRef(),
                s.clauses().stream().map(LeasePreviewResponse::clause).toList());
    }

    private static Clause clause(DocumentFreeze.FrozenClause c) {
        return new Clause(c.ordinal(), c.clauseKey(), c.title(), c.number(), c.label(), c.depth(),
                c.body(), c.statuteRef(),
                c.origin() == null ? null : c.origin().name(), c.unresolved());
    }
}
