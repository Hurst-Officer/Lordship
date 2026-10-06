package io.github.lordship.audit.internal;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;
import io.github.lordship.audit.AuditLog;
import io.github.lordship.audit.OperationType;
import io.github.lordship.shared.SensitiveDataMasker;
import io.github.lordship.shared.UserType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.github.lordship.shared.SensitiveDataMasker.defaultMask;

public record AuditLogResponse(
        UUID id,
        UUID correlationId,
        UUID userId,
        UserType userType,
        String ipAddress,
        String tableName,
        String recordId,
        OperationType operation,
        String valueBefore,
        String valueAfter,
        OffsetDateTime changedAt
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Map JSON field → required permission
    private static final Map<String, String> FIELD_PERMISSIONS = Map.of(
            "social", "persons_ssn:view"
            // add more as needed
    );

    // Field → masking function
    private static final Map<String, Function<String, String>> FIELD_MASKERS = Map.of(
            "social", SensitiveDataMasker::maskSsn
    );

    public static AuditLogResponse from(AuditLog row, Authentication authentication) {
        Set<String> authorities = authentication.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        return new AuditLogResponse(
                row.uuid(),
                row.correlationId(),
                row.userId(),
                row.userType(),
                row.ipAddress(),
                row.tableName(),
                row.recordId(),
                row.operation(),
                redactJson(row.valueBefore(), authorities),
                redactJson(row.valueAfter(), authorities),
                row.changedAt()
        );
    }

    private static String redactJson(String json, Set<String> authorities) {
        if (json == null) return null;

        try {
            Map<String, Object> map = MAPPER.readValue(json, new TypeReference<>() {});
            Map<String, Object> result = new HashMap<>();

            for (var entry : map.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                String requiredPermission = FIELD_PERMISSIONS.get(key);

                // If the field requires a permission and the user does NOT have it → redact
                if (requiredPermission != null && !authorities.contains(requiredPermission)) {
                    if (value == null) {
                        result.put(key, null);
                    } else if (value instanceof String stringValue){
                        Function<String, String> masker = FIELD_MASKERS.get(key);
                        result.put(key, masker != null ? masker.apply(stringValue) : defaultMask(stringValue));
                    }
                    // if the value isn't even a string just continue
                    continue;
                }
                result.put(key, value);
            }
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException("Failed to redact audit JSON", e);
        }
    }

}
