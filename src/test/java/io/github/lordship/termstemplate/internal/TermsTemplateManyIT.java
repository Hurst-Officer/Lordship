package io.github.lordship.termstemplate.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.audit.AuditContext;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.FeeMethod;
import io.github.lordship.shared.RuleConflict;
import io.github.lordship.shared.SystemPrincipal;
import io.github.lordship.shared.UserType;
import io.github.lordship.termstemplate.TermsTemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Changing several terms templates at once is all or nothing (TermsTemplateService.patchTermsTemplates):
 * when the changes are refused for one template, the templates changed before that one are put back,
 * and no history is kept of the call.
 *
 * <p>Not @Transactional: inside a test's own transaction the service call only joins that transaction, and
 * nothing is undone until the test ends, so the undo could not be seen. Here the service call is the whole
 * transaction, as in a real request. The two templates this test writes are removed again at the end.
 */
public class TermsTemplateManyIT extends IntegrationTest {

    @Autowired
    TermsTemplateService termsTemplateService;

    @Autowired
    TermsTemplateRepository termsTemplateRepository;

    @Autowired
    AuditContext auditContext;

    @Autowired
    JdbcClient jdbc;

    @Test
    void patchTermsTemplates_shouldChangeNoTemplate_whenTheChangesAreRefusedForOne() {
        // Arrange -- written by the repository outside any transaction, so both rows are committed at once
        // and no history is written for them
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID first = termsTemplateRepository.save(new TermsTemplateRow(
                null, "IT Many First " + suffix, AgreementType.LAND, SystemPrincipal.AGENT_UUID)).uuid();
        UUID second = termsTemplateRepository.save(new TermsTemplateRow(
                null, "IT Many Second " + suffix, AgreementType.LAND, SystemPrincipal.AGENT_UUID)).uuid();
        try {
            // the second template charges no late fee, so "flat" with no amount can only be refused for the second
            termsTemplateRepository.patch(second, Map.of(
                    "late_fee_method", "NONE",
                    "late_fee_amount", BigDecimal.ZERO));
            actAsAnAgent();

            // Act -- the first template takes both changes, then the second refuses the late fee
            Map<String, Object> changes = new HashMap<>();
            changes.put("grace_period_days", 5);
            changes.put("late_fee_method", "FLAT");
            assertThrows(IllegalArgumentException.class,
                    () -> termsTemplateService.patchTermsTemplates(List.of(first, second), changes));

            // Assert -- the first template is as before
            assertEquals(7, termsTemplateRepository.findById(first).orElseThrow().gracePeriodDays());
            assertEquals(FeeMethod.NONE, termsTemplateRepository.findById(second).orElseThrow().lateFeeMethod());

            // Act -- the same when the second template is one that does not exist
            Map<String, Object> graceOnly = new HashMap<>();
            graceOnly.put("grace_period_days", 5);
            RuleConflict gone = assertThrows(RuleConflict.class,
                    () -> termsTemplateService.patchTermsTemplates(List.of(first, UUID.randomUUID()), graceOnly));

            // Assert
            assertEquals("terms_template.gone", gone.problem().code());
            assertEquals(7, termsTemplateRepository.findById(first).orElseThrow().gracePeriodDays());

            // the history of a change is written in the same transaction, so a refused call leaves none
            assertEquals(0, historyRowsOf(first, second));
        } finally {
            RequestContextHolder.resetRequestAttributes();
            // no history to remove: a refused call leaves none, which the test has just checked
            jdbc.sql("DELETE FROM terms_template WHERE uuid IN (:first, :second)")
                    .param("first", first)
                    .param("second", second)
                    .update();
        }
    }

    // The service writes history for a change, and history needs someone to name. In a real request the login
    // filter fills AuditContext; this test calls the service with no login, so the test fills AuditContext.
    private void actAsAnAgent() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        auditContext.setUserType(UserType.AGENT);
        auditContext.setActingUserId(SystemPrincipal.AGENT_UUID);
        auditContext.setIpAddress("127.0.0.1");
    }

    private int historyRowsOf(UUID first, UUID second) {
        return jdbc.sql("""
                        SELECT count(*) FROM audit_log
                        WHERE table_name = 'terms_template' AND record_id IN (:first, :second)
                        """)
                .param("first", first.toString())
                .param("second", second.toString())
                .query(Integer.class)
                .single();
    }
}
