package io.github.lordship.tenancyterms;

import io.github.lordship.shared.DocumentToken;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One distinct shape of deal in force somewhere, and how many tenancies have
 * it. Only the columns a document clause can branch on -- the figures differ
 * from tenant to tenant and never decide whether a paragraph prints, so a park
 * with six thousand lots still resolves to a handful of configurations.
 *
 * <p>Everything is a String rather than the Java enums so JdbcClient can map
 * the row without a RowMapper, and because what the caller does with it is
 * compare against {@code DocumentToken.allowedValues()}, which is strings.
 */
public record ChargeTermConfiguration(
        String agreementType,
        String lateFeeMethod,
        String nsfFeeMethod,
        String ruleViolationFeeMethod,
        String waterMethod,
        String powerMethod,
        String sewerMethod,
        String trashMethod,
        String securityDepositMethod,
        int tenancyCount
) {

    /** Keyed by token name, which is the shape {@code DocumentTemplate.preview} takes. */
    public Map<String, String> methodValues() {
        Map<String, String> values = new LinkedHashMap<>();
        put(values, DocumentToken.AGREEMENT_TYPE, agreementType);
        put(values, DocumentToken.LATE_FEE_METHOD, lateFeeMethod);
        put(values, DocumentToken.NSF_FEE_METHOD, nsfFeeMethod);
        put(values, DocumentToken.RULE_VIOLATION_FEE_METHOD, ruleViolationFeeMethod);
        put(values, DocumentToken.WATER_METHOD, waterMethod);
        put(values, DocumentToken.POWER_METHOD, powerMethod);
        put(values, DocumentToken.SEWER_METHOD, sewerMethod);
        put(values, DocumentToken.TRASH_METHOD, trashMethod);
        put(values, DocumentToken.SECURITY_DEPOSIT_METHOD, securityDepositMethod);
        return values;
    }

    private static void put(Map<String, String> values, DocumentToken token, String value) {
        if (value != null) {
            values.put(token.token(), value);
        }
    }
}