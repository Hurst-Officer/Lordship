package io.github.lordship.access;

import io.github.lordship.shared.DomainProblem;

import java.time.Duration;
import java.util.List;

/**
 * A sign-in that did not go through. 401 for wrong credentials, 429 for too many
 * attempts -- ApiExceptionHandler tells them apart by whether there is a wait.
 *
 * <p>Extends RuntimeException, not IllegalArgumentException or IllegalStateException,
 * so the 400/409 fallback handlers can never catch it.
 */
public class LoginRefused extends RuntimeException implements DomainProblem {

    private final Problem problem;
    private final Duration retryAfter;

    private LoginRefused(Problem problem, Duration retryAfter) {
        super(problem.describe());
        this.problem = problem;
        this.retryAfter = retryAfter;
    }

    // One answer for an unknown email and a wrong password, so neither gives the other away.
    public static LoginRefused badCredentials() {
        return new LoginRefused(Problem.of("auth.bad_credentials"), null);
    }

    public static LoginRefused tooManyAttempts(Duration wait) {
        long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
        long minutes = seconds < 60 ? 0 : (seconds + 59) / 60;
        return new LoginRefused(Problem.of("auth.too_many_attempts", minutes), Duration.ofSeconds(seconds));
    }

    public boolean isThrottled() {
        return retryAfter != null;
    }

    /** Whole seconds, for the Retry-After header. Zero when not throttled. */
    public long retryAfterSeconds() {
        return retryAfter == null ? 0 : retryAfter.toSeconds();
    }

    @Override
    public Problem problem() {
        return problem;
    }

    @Override
    public List<Problem> details() {
        return List.of();
    }
}
