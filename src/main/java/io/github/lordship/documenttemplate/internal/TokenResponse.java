package io.github.lordship.documenttemplate.internal;

import io.github.lordship.shared.DocumentToken;
import org.springframework.modulith.NamedInterface;

import java.util.List;

/**
 * What the clause editor's token picker shows. Grouped by namespace,
 * because seventy tokens in one alphabetical list is unusable.
 *
 * <p>{@code allowedValues} is populated only for tokens a clause may branch
 * on, so the condition editor can offer a checklist instead of a free-text
 * box someone can typo into.
 *
 * <p>{@code governedBy} and {@code populatedWhen} appear on amount tokens,
 * and are what let the editor propose the condition at the moment a token
 * is inserted -- "this only has a value when term.late_fee_method is FLAT;
 * condition the clause on that?" -- instead of leaving the author to work
 * out which of nine methods applies.
 *
 * <p>Shared with the screens module, so the section page's one call sends the
 * token picker in this same shape.
 */
@NamedInterface("responses")
public record TokenResponse(
        String placeholder,
        String namespace,
        String source,
        String format,
        String description,
        boolean canCondition,
        List<String> allowedValues,
        String governedBy,
        List<String> populatedWhen) {

    public static TokenResponse from(DocumentToken token) {
        return new TokenResponse(
                token.placeholder(),
                token.namespace(),
                token.source().name(),
                token.format().name(),
                token.description(),
                token.canCondition(),
                token.allowedValues().stream().sorted().toList(),
                token.governedBy().map(DocumentToken::token).orElse(null),
                token.populatedWhen().stream().sorted().toList());
    }
}
