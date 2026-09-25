package io.github.lordship.documentfiles;

/** A file's record together with its bytes, for downloads. */
public record StoredFile(DocumentFile file, byte[] content) {}
