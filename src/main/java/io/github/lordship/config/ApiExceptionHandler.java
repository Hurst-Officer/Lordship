package io.github.lordship.config;

import io.github.lordship.access.LoginRefused;
import io.github.lordship.shared.DomainProblem;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.RuleConflict;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns the framework's own request failures into the {@code {"message": ...}} shape the
 * rest of the API uses. Handling them here means they never reach the /error forward,
 * which is the path that used to strip the real status and report 401.
 *
 * <p>Deliberately no catch-all for Exception: AccessDeniedException from @PreAuthorize is
 * thrown during handler invocation and a broad handler here would swallow it, turning
 * every missing-authority 403 into something else. Leave it to Spring Security.
 *
 * <p>A controller's own @ExceptionHandler still wins over anything here, so the local
 * handlers in LotController and HomeController keep their behaviour.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private final MessageSource messages;

    public ApiExceptionHandler(MessageSource messages) {
        this.messages = messages;
    }

    private static ResponseEntity<Map<String, String>> of(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message));
    }

    /**
     * A rule the domain refused, said as a code and its arguments.
     *
     * <p>Answers with the sentence AND the structure: {@code message} keeps every
     * existing caller working, while {@code problems} lets a form highlight the
     * input at fault instead of printing a paragraph, and lets a frontend render
     * its own wording if it would rather.
     *
     * <p>The sentence is resolved against the request's Accept-Language, so the
     * day {@code messages_es.properties} exists a Spanish-reading property
     * manager gets Spanish here with no other change anywhere.
     *
     * <p>Status comes from which exception it is, which is the same distinction
     * the two handlers below already make: a bad request is 400, a conflict with
     * existing state is 409.
     */
    @ExceptionHandler({InvalidRequest.class, RuleConflict.class})
    ResponseEntity<Map<String, Object>> domainProblem(RuntimeException e) {
        DomainProblem refused = (DomainProblem) e;
        HttpStatus status = (e instanceof RuleConflict) ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", sentence(refused));
        body.put("code", refused.problem().code());
        if (refused.problem().field() != null) {
            body.put("field", refused.problem().field());
        }
        if (!refused.details().isEmpty()) {
            body.put("problems", refused.details().stream().map(ApiExceptionHandler::describe).toList());
        }
        return ResponseEntity.status(status).body(body);
    }

    /**
     * A sign-in that did not go through. 429 with Retry-After when the throttle turned
     * it away; otherwise 401, worded the same for an unknown email and a wrong password.
     */
    @ExceptionHandler(LoginRefused.class)
    ResponseEntity<Map<String, Object>> loginRefused(LoginRefused e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", sentence(e));
        body.put("code", e.problem().code());

        if (e.isThrottled()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                    .body(body);
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(body);
    }

    /** The umbrella, then whichever details failed -- one sentence to read and fix from. */
    private String sentence(DomainProblem refused) {
        String headline = resolve(refused.problem());
        if (refused.details().isEmpty()) {
            return headline;
        }
        return headline + " -- " + refused.details().stream()
                .map(this::resolve)
                .collect(Collectors.joining("; "));
    }

    /**
     * A detail names its own field, because "must be greater than zero" is no
     * use without knowing which box. The field is the API's own name for it, so
     * a form can match it without translating anything.
     */
    private String resolve(DomainProblem.Problem problem) {
        String text = messages.getMessage(
                problem.code(), problem.args().toArray(), problem.code(),
                LocaleContextHolder.getLocale());
        return problem.field() == null ? text : problem.field() + ": " + text;
    }

    private static Map<String, Object> describe(DomainProblem.Problem problem) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", problem.code());
        if (problem.field() != null) {
            out.put("field", problem.field());
        }
        out.put("args", List.copyOf(problem.args()));
        return out;
    }

    // @Valid on a request body failed
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalidBody(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return of(HttpStatus.BAD_REQUEST, message.isBlank() ? "Request body is not valid" : message);
    }

    // malformed JSON, or a value the body cannot be read into
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadableBody(HttpMessageNotReadableException e) {
        return of(HttpStatus.BAD_REQUEST, "Request body could not be read");
    }

    // a path variable or query parameter of the wrong type, e.g. a uuid that is not one
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, String>> badParameterType(MethodArgumentTypeMismatchException e) {
        return of(HttpStatus.BAD_REQUEST, e.getName() + " is not a valid " +
                (e.getRequiredType() == null ? "value" : e.getRequiredType().getSimpleName()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<Map<String, String>> missingParameter(MissingServletRequestParameterException e) {
        return of(HttpStatus.BAD_REQUEST, "Missing required parameter: " + e.getParameterName());
    }

    // Fallback for the throw sites not yet converted to InvalidRequest. Spring
    // picks the closest match in the hierarchy, so a converted throw lands on
    // the handler above rather than here.
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return of(HttpStatus.BAD_REQUEST, String.valueOf(e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return of(HttpStatus.CONFLICT, String.valueOf(e.getMessage()));
    }

    /**
     * A rule the database refused. Without this the request reaches /error and reports
     * 500, which reads as a server fault when it is usually the caller asking for
     * something the data will not allow -- a second structure on an occupied lot, say.
     *
     * <p>23514 covers both a CHECK constraint and a RAISE from one of our triggers.
     * Postgres words its own constraint failures with "violates check constraint", and
     * that means a bad value (400). A trigger message is one we wrote for a person to
     * read, and means the request conflicts with existing state (409).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> dataIntegrity(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        String state = cause instanceof SQLException sql ? sql.getSQLState() : null;
        String detail = firstLine(cause.getMessage());

        if ("23514".equals(state) && detail != null && !detail.isBlank()) {
            return detail.contains("violates check constraint")
                    ? of(HttpStatus.BAD_REQUEST, detail)
                    : of(HttpStatus.CONFLICT, detail);
        }
        if ("23505".equals(state)) {
            return of(HttpStatus.CONFLICT, "That record already exists");
        }
        if ("23503".equals(state)) {
            return of(HttpStatus.BAD_REQUEST, "That change refers to a record that does not exist");
        }
        return of(HttpStatus.CONFLICT, "That change conflicts with existing data");
    }

    // Postgres puts CONTEXT and WHERE lines under the message; only the first line is
    // meant for anyone but a developer reading a stack trace.
    private static String firstLine(String message) {
        if (message == null) return null;
        int newline = message.indexOf('\n');
        String line = (newline < 0 ? message : message.substring(0, newline)).trim();
        return line.startsWith("ERROR: ") ? line.substring(7).trim() : line;
    }
}