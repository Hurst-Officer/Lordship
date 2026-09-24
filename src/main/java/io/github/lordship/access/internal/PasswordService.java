package io.github.lordship.access.internal;

import io.github.lordship.shared.InvalidRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
public class PasswordService {

    // BCrypt reads no further than this, and encode() refuses anything longer
    static final int MAX_BYTES = 72;

    private final PasswordEncoder passwordEncoder;

    // Checked against when there is no real hash, so a missing account costs the
    // same quarter second as a wrong password. Spring's DaoAuthenticationProvider
    // does the same thing.
    private final String standInHash;

    public PasswordService(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
        this.standInHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public String hash(String plaintext) {
        return passwordEncoder.encode(plaintext);
    }

    /** False for a null or empty hash, after doing the same work as a real check. */
    public boolean verify(String plaintext, String hash) {
        if (hash == null || hash.isEmpty()) {
            passwordEncoder.matches(plaintext, standInHash);
            return false;
        }
        return passwordEncoder.matches(plaintext, hash);
    }

    /**
     * 64 characters of accented letters or symbols can pass the DTO and still be
     * over 72 bytes. Refused here by name rather than as the encoder's English text.
     */
    public void requireFits(String plaintext, String field) {
        if (plaintext != null && plaintext.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw InvalidRequest.onField(field, "password.too_long");
        }
    }
}
