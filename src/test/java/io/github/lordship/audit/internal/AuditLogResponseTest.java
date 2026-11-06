package io.github.lordship.audit.internal;

import io.github.lordship.audit.AuditLog;
import io.github.lordship.audit.OperationType;
import io.github.lordship.shared.UserType;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

// A social security number in the audit log shows in full only to a reader with persons_ssn:view.
public class AuditLogResponseTest {

    private static AuditLog socialChange(String before, String after) {
        return new AuditLog(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UserType.AGENT, "127.0.0.1",
                "person", UUID.randomUUID().toString(), OperationType.UPDATE, before, after, OffsetDateTime.now());
    }

    private static Authentication readerWith(String... permissions) {
        return new UsernamePasswordAuthenticationToken("reader", null,
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList());
    }

    @Test
    void masksTheSocial_forAReaderWithoutPersonsSsnView() {
        AuditLog log = socialChange("{\"social\":null}", "{\"social\":\"547-25-6371\"}");

        AuditLogResponse response = AuditLogResponse.from(log, readerWith("audit:view"));

        assertEquals("{\"social\":null}", response.valueBefore());
        assertEquals("{\"social\":\"***-**-6371\"}", response.valueAfter());
    }

    @Test
    void showsTheWholeSocial_forAReaderWithPersonsSsnView() {
        AuditLog log = socialChange(null, "{\"social\":\"547-25-6371\"}");

        AuditLogResponse response = AuditLogResponse.from(log, readerWith("audit:view", "persons_ssn:view"));

        assertEquals("{\"social\":\"547-25-6371\"}", response.valueAfter());
    }

    @Test
    void leavesOtherFieldsAlone_forAReaderWithoutPersonsSsnView() {
        AuditLog log = socialChange(null, "{\"birthday\":\"1990-05-12\"}");

        AuditLogResponse response = AuditLogResponse.from(log, readerWith("audit:view"));

        assertEquals("{\"birthday\":\"1990-05-12\"}", response.valueAfter());
    }
}
