package io.github.lordship.documentfiles.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalFolderStorageTest {

    @TempDir
    Path folder;

    @Test
    void write_shouldSaveTheBytes_soReadReturnsThem() {
        // Arrange
        LocalFolderStorage storage = new LocalFolderStorage(folder.toString());
        byte[] content = "a lease".getBytes(StandardCharsets.UTF_8);

        // Act
        storage.write("HS-TEST-0001-LLE.pdf", content);

        // Assert
        assertArrayEquals(content, storage.read("HS-TEST-0001-LLE.pdf"));
        assertTrue(Files.exists(folder.resolve("HS-TEST-0001-LLE.pdf")));
    }

    @Test
    void write_shouldCreateSubfolders() {
        // Arrange
        LocalFolderStorage storage = new LocalFolderStorage(folder.toString());

        // Act
        storage.write("scans/2026/signed.pdf", new byte[] {1, 2, 3});

        // Assert
        assertTrue(Files.exists(folder.resolve("scans/2026/signed.pdf")));
    }

    @Test
    void write_shouldRefuseToOverwriteAFile() {
        // Arrange -- a stored file is a record, so it is never replaced
        LocalFolderStorage storage = new LocalFolderStorage(folder.toString());
        storage.write("lease.pdf", new byte[] {1});

        // Act / Assert
        assertThrows(IllegalStateException.class, () -> storage.write("lease.pdf", new byte[] {2}));
        assertArrayEquals(new byte[] {1}, storage.read("lease.pdf"));
    }

    @Test
    void write_shouldRefuseAKeyOutsideTheFolder() {
        // Arrange
        LocalFolderStorage storage = new LocalFolderStorage(folder.resolve("files").toString());

        // Act / Assert
        assertThrows(IllegalArgumentException.class, () -> storage.write("../escaped.pdf", new byte[] {1}));
        assertFalse(Files.exists(folder.resolve("escaped.pdf")));
    }

    @Test
    void read_shouldThrow_whenTheFileIsMissing() {
        // Arrange
        LocalFolderStorage storage = new LocalFolderStorage(folder.toString());

        // Act / Assert
        assertThrows(IllegalStateException.class, () -> storage.read("never-saved.pdf"));
    }
}
