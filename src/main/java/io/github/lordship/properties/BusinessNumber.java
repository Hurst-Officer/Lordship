package io.github.lordship.properties;

// One registration number for the owner of a property.
// state is a two-letter code, or "US" for federal numbers like an EIN.
// type is whatever the issuer calls it: "UBI", "SOS", "Registry number".
// number is kept exactly as the issuer writes it, so it stays text.
// link and note are optional.
public record BusinessNumber(
        String state,
        String type,
        String number,
        String link,
        String note
) {}
