package io.github.lordship.instruments;

import java.util.UUID;

/**
 * This lease for this tenancy, with the real figures in it, before anybody
 * commits to it.
 *
 * <p>What generate produces, minus the saving. The office worker is not
 * expected to find out what is missing by clicking Generate and reading a 400
 * -- she looks at the document, sees the holes standing in the text, fixes the
 * deal or the template, and looks again.
 *
 * <p>Preview and generate run the same assembly, so what she sees here is what
 * will be frozen. Two code paths would eventually disagree, and the one time
 * they did it would be on a lease somebody had already signed.
 */
public record LeasePreview(
        UUID instrument,
        UUID documentTemplate,
        String documentName,
        Integer documentVersion,
        DocumentFreeze.Frozen frozen
) {

    /** Whether this is fit to put in front of a tenant. */
    public boolean isComplete() {
        return frozen.isComplete();
    }
}
