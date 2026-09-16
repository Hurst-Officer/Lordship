package io.github.lordship.documenttemplate;

public enum CustomizationAction {
    EXCLUDE_SECTION, EXCLUDE_CLAUSE, ADD_CLAUSE
    // What one park changes about a document it was assigned. There is no
    // REPLACE: a global body is never rewritten locally, because a statutory
    // fix has to be able to reach every park that uses the document.
}
