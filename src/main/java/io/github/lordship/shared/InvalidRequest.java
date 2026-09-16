package io.github.lordship.shared;

import java.util.List;

public class InvalidRequest  extends IllegalArgumentException  implements DomainProblem {
    private final Problem problem;
    private final List<Problem> details;

    private InvalidRequest(Problem problem, List<Problem> details) {
        super(DomainProblem.describe(problem, details));
        this.problem = problem;
        this.details = List.copyOf(details);
    }

    public static InvalidRequest of(String code, Object... args) {
        return new InvalidRequest(Problem.of(code, args), List.of());
    }

    /** Names the input at fault, so a form can highlight it rather than printing a sentence. */
    public static InvalidRequest onField(String field, String code, Object... args) {
        return new InvalidRequest(Problem.onField(field, code, args), List.of());
    }

    /** Several rules refused at once, collected under one umbrella. */
    public static InvalidRequest withDetails(String code, List<Problem> details) {
        return new InvalidRequest(Problem.of(code), details);
    }

    @Override
    public Problem problem() {
        return problem;
    }

    @Override
    public List<Problem> details() {
        return details;
    }
}
