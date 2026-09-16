package io.github.lordship.shared;

import java.util.List;

/**
 * The records exist, and the state they are in forbids this -- a lot that
 * cannot host the agreement, a property with no template for it. Answers 409.
 *
 * <p>Extends {@code IllegalStateException} for the same reason
 * {@link InvalidRequest} extends IllegalArgumentException: nothing that already
 * catches one has to change.
 */
public class RuleConflict extends IllegalStateException implements DomainProblem {

    private final Problem problem;

    private RuleConflict(Problem problem) {
        super(problem.describe());
        this.problem = problem;
    }

    public static RuleConflict of(String code, Object... args) {
        return new RuleConflict(Problem.of(code, args));
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