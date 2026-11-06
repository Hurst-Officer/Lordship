package io.github.lordship.properties.internal;

// A new business number, like state "WA", type "UBI" and number "604 123 456"
public record BusinessNumberRequest(
        String state,
        String type,
        String number
) {}
