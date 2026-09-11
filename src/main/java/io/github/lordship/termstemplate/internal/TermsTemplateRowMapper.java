package io.github.lordship.termstemplate.internal;

import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.FeeMethod;
import io.github.lordship.shared.SecurityDepositMethod;
import io.github.lordship.shared.UtilityMethod;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;


@Component
public class TermsTemplateRowMapper implements RowMapper<TermsTemplateRow> {

    @Override
    public TermsTemplateRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TermsTemplateRow(
                (UUID) rs.getObject("uuid"),
                (UUID) rs.getObject("property"),
                (UUID) rs.getObject("copied_from"),
                rs.getString("name"),
                enumOf(AgreementType.class, rs.getString("agreement_type")),
                rs.getBigDecimal("target_rate"),
                rs.getBigDecimal("asking_rate"),

                rs.getBigDecimal("car_fee"),
                rs.getObject("allowed_cars", Integer.class),
                rs.getObject("cars_max", Integer.class),

                rs.getBigDecimal("pet_fee"),
                rs.getObject("allowed_pets", Integer.class),

                rs.getObject("payment_due_day", Integer.class),
                rs.getObject("grace_period_days", Integer.class),

                enumOf(FeeMethod.class, rs.getString("rule_violation_fee_method")),
                rs.getBigDecimal("rule_violation_fee_amount"),

                enumOf(FeeMethod.class, rs.getString("nsf_fee_method")),
                rs.getBigDecimal("nsf_fee_amount"),

                enumOf(FeeMethod.class, rs.getString("late_fee_method")),
                rs.getBigDecimal("late_fee_amount"),

                enumOf(UtilityMethod.class, rs.getString("water_method")),
                rs.getBigDecimal("water_flat_amount"),

                enumOf(UtilityMethod.class, rs.getString("power_method")),
                rs.getBigDecimal("power_flat_amount"),

                enumOf(UtilityMethod.class, rs.getString("sewer_method")),
                rs.getBigDecimal("sewer_flat_amount"),

                enumOf(UtilityMethod.class, rs.getString("trash_method")),
                rs.getBigDecimal("trash_flat_amount"),

                enumOf(SecurityDepositMethod.class, rs.getString("security_deposit_method")),
                rs.getBigDecimal("security_deposit_amount"),

                rs.getString("note"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                (UUID) rs.getObject("created_by"),
                rs.getObject("deleted_at", OffsetDateTime.class)
        );
    }

    // to get enum value
    private static <E extends Enum<E>> E enumOf(Class<E> type, String value) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "No " + type.getSimpleName() + " constant for database value '" + value + "'", e);
        }
    }
}