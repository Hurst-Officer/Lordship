package io.github.lordship.documentfiles.internal;

/**
 * Where file bytes are kept. A key is a relative path such as "HS-41JB-5F32-LLE.pdf".
 *
 * <p>Today the only version is {@link LocalFolderStorage}. An S3 version will
 * be a second class that implements this.
 */
public interface FileStorage {

    /** Saves the bytes under this key. Throws if the key is already taken. */
    void write(String key, byte[] content);

    /** The bytes saved under this key. Throws if there are none. */
    byte[] read(String key);
}
