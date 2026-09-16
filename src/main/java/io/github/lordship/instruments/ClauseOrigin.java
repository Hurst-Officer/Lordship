package io.github.lordship.instruments;

public enum ClauseOrigin {
    TEMPLATE, PROPERTY, INSTRUMENT
    // Where the wording came from -- the global document, or a clause this one
    // park added through a document customization. Frozen onto the instrument
    // because the customization row can be retired later and the snapshot has
    // to keep saying which it was.
}
