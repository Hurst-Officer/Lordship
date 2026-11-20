package io.github.lordship.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// An endpoint asks OwnWork inside a text: @PreAuthorize("... or @ownWork.madeRecently('lot', #uuid, authentication)").
// The compiler does not read that text. A misspelled table there would answer 403 for ever and raise no error,
// so this test reads every controller's checks and compares the table names with the kinds OwnWork knows.
// No database and no server: the test only reads the annotations.
class OwnWorkAnnotationsTest {

    // the table name inside @ownWork.madeRecently('...') or @ownWork.madeAllRecently('...')
    private static final Pattern ASKS_OWN_WORK = Pattern.compile("@ownWork\\.made(?:All)?Recently\\('([^']*)'");

    @Test
    void everyTableAnEndpointNames_isAKindOwnWorkKnows() throws Exception {
        // Arrange
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        // Act
        List<String> wrong = new ArrayList<>();
        int asked = 0;
        for (BeanDefinition found : scanner.findCandidateComponents("io.github.lordship")) {
            Class<?> controller = Class.forName(found.getBeanClassName());
            for (Method endpoint : controller.getDeclaredMethods()) {
                PreAuthorize check = endpoint.getAnnotation(PreAuthorize.class);
                if (check == null || !check.value().contains("@ownWork")) {
                    continue;
                }
                String where = controller.getSimpleName() + "." + endpoint.getName();
                Matcher matcher = ASKS_OWN_WORK.matcher(check.value());
                if (!matcher.find()) {
                    wrong.add(where + " names @ownWork but calls neither madeRecently nor madeAllRecently");
                    continue;
                }
                asked++;
                if (!OwnWork.covers(matcher.group(1))) {
                    wrong.add(where + " names the table '" + matcher.group(1) + "', which is not in OwnWork.KINDS");
                }
            }
        }

        // Assert
        assertTrue(asked > 0, "The scan found no endpoint that asks OwnWork, so the scan itself is broken");
        assertEquals(List.of(), wrong);
    }
}
