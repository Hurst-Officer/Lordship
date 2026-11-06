package io.github.lordship.properties.internal;

import java.util.Map;

// A change to one business number.
// state, type and number say which number, as the worker last saw it.
// changes holds only the new values, by name: state, type, number, link, note.
public record BusinessNumberChangeRequest(
        String state,
        String type,
        String number,
        Map<String, Object> changes
) {}
