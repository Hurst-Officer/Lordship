package io.github.lordship.audit;

import io.github.lordship.audit.internal.AuditLogRow;
import io.github.lordship.audit.internal.AuditRepository;
import io.github.lordship.shared.PageRequest;
import io.github.lordship.shared.PageResult;
import io.github.lordship.shared.UserType;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AuditService {

    private final ObjectMapper objectMapper;
    private final AuditContext auditContext;
    private final AuditRepository auditRepository;

    public AuditService(AuditContext auditContext, ObjectMapper objectMapper, AuditRepository auditRepository) {
        this.auditContext = auditContext;
        this.objectMapper = objectMapper;
        this.auditRepository = auditRepository;
    }

    private static final Set<String> INSERT_EXCLUDED_KEYS = Set.of("createdAt", "uuid");
    private static final Set<String> DELETE_EXCLUDED_KEYS = Set.of("uuid");

    // Null, blank text and empty maps carry no data, so insert and delete logs leave them out.
    private static boolean hasNoData(Object value) {
        return value == null
                || (value instanceof String s && s.isBlank())
                || (value instanceof Map<?, ?> m && m.isEmpty());
    }

    private static Map<String, Object> sanitizeForInsertLog(Map<String, Object> map) {
        return map.entrySet().stream()
                .filter(e -> !hasNoData(e.getValue()))
                .filter(e -> !INSERT_EXCLUDED_KEYS.contains(e.getKey()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
    }

    private static Map<String, Object> sanitizeForDeleteLog(Map<String, Object> map) {
        return map.entrySet().stream()
                .filter(e -> !hasNoData(e.getValue()))
                .filter(e -> !DELETE_EXCLUDED_KEYS.contains(e.getKey()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
    }

    public void recordInsert(String tableName, UUID recordId, Map<String, Object> after) {
        if (auditContext.getUserType() == null) {
            throw new IllegalStateException(
                    "No acting principal for an audit row on " + tableName
                            + " -- use recordSystemInsert for system-authored rows");
        }
        var row = new AuditLogRow(
                auditContext.getCorrelationId(),
                auditContext.getActingUserId(),
                auditContext.getUserType(),
                auditContext.getIpAddress(),
                tableName,
                recordId.toString(),
                OperationType.INSERT,
                null,
                toJson(sanitizeForInsertLog(after))
        );
        auditRepository.save(row);
    }

    public void recordSystemInsert(UUID correlationId, String tableName, UUID recordId, Map<String, Object> after) {
        AuditLogRow row = new AuditLogRow(
                correlationId,
                null,
                UserType.SYSTEM,
                "system",
                tableName,
                recordId.toString(),
                OperationType.INSERT,
                null,
                toJson(sanitizeForInsertLog(after))
        );
        auditRepository.save(row);
    }

    public void recordUpdate(String tableName, UUID recordId, Map<String, Object> before, Map<String, Object> after) {
        var row = new AuditLogRow(
                auditContext.getCorrelationId(),
                auditContext.getActingUserId(),
                auditContext.getUserType(),
                auditContext.getIpAddress(),
                tableName,
                recordId.toString(),
                OperationType.UPDATE,
                toJson(before),
                toJson(after)
        );
        auditRepository.save(row);
    }

    public void recordDelete(String tableName, UUID recordId, Map<String, Object> before) {
        var row = new AuditLogRow(
                auditContext.getCorrelationId(),
                auditContext.getActingUserId(),
                auditContext.getUserType(),
                auditContext.getIpAddress(),
                tableName,
                recordId.toString(),
                OperationType.DELETE,
                toJson(sanitizeForDeleteLog(before)),
                null
        );
        auditRepository.save(row);
    }

    public PageResult<AuditLog> findAgentAuditLogs(UUID agentId, Integer page, Integer pageSize, String sortBy, boolean ascending) {
        PageRequest pageRequest = PageRequest.of(page, pageSize, sortBy, ascending);
        return auditRepository.findAllAgentAuditLogs(agentId, pageRequest);
    }

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        return objectMapper.writeValueAsString(map);
    }
}