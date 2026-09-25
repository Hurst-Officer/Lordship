package io.github.lordship.documentfiles.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Keeps files in a folder on this machine. The folder is lordship.files.root
 * in application.properties.
 *
 * <p>Never overwrites a file. Refuses any key that would land outside the
 * folder, such as "../secrets.txt".
 */
@Component
public class LocalFolderStorage implements FileStorage {

    private final Path root;

    public LocalFolderStorage(@Value("${lordship.files.root}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public void write(String key, byte[] content) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException e) {
            throw new IllegalStateException("A file is already stored at " + key, e);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save " + key, e);
        }
    }

    @Override
    public byte[] read(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (NoSuchFileException e) {
            throw new IllegalStateException("The file for " + key + " is missing from " + root, e);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + key, e);
        }
    }

    private Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("Storage key is outside the storage folder: " + key);
        }
        return target;
    }
}
