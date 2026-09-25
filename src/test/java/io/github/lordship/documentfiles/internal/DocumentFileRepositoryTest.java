package io.github.lordship.documentfiles.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.shared.SystemPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
public class DocumentFileRepositoryTest extends IntegrationTest {

    @Autowired
    DocumentFileRepository documentFileRepository;

    @Test
    void save_shouldRoundTripEveryColumn() {
        // Arrange
        byte[] fingerprint = new byte[32];
        fingerprint[0] = 7;
        String key = "test/" + UUID.randomUUID() + ".pdf";

        // Act
        DocumentFileRow saved = documentFileRepository.save(new DocumentFileRow(
                null, "lease.pdf", "application/pdf", 1234L, fingerprint, key, null,
                SystemPrincipal.AGENT_UUID));
        DocumentFileRow read = documentFileRepository.findById(saved.uuid()).orElseThrow();

        // Assert
        assertNotNull(read.uuid());
        assertNotNull(read.uploadedAt());
        assertEquals("lease.pdf", read.fileName());
        assertEquals("application/pdf", read.contentType());
        assertEquals(1234L, read.byteSize());
        assertArrayEquals(fingerprint, read.sha256());
        assertEquals(key, read.storageKey());
        assertEquals(SystemPrincipal.AGENT_UUID, read.uploadedBy());
        assertTrue(read.toDocumentFile().sha256().startsWith("07"));
    }

    @Test
    void findById_shouldBeEmpty_forAnUnknownFile() {
        // Act / Assert
        assertTrue(documentFileRepository.findById(UUID.randomUUID()).isEmpty());
    }
}
