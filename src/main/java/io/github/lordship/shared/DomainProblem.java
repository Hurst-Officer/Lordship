package io.github.lordship.shared;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A rule the domain refused, said in a form that is not tied to a language.
 *
 * <p>The problem this solves is narrow and worth naming. A hand-built sentence
 * -- {@code "Lot " + lot + " does not permit " + type + " agreements"} -- is
 * English assembled in Java, so a property manager who reads Spanish gets
 * English, and a frontend that wants to highlight the offending field has to
 * parse prose to find it. A code and its arguments carry the same facts without
 * choosing the words.
 *
 * <p>Implemented by {@link InvalidRequest} and {@link RuleConflict}, which
 * extend {@code IllegalArgumentException} and {@code IllegalStateException} so
 * that every existing catch, handler and test keeps working while services are
 * converted one throw at a time.
 *
 * <p>The words live in {@code messages.properties}, resolved at the edge against
 * the request's Accept-Language. Adding {@code messages_es.properties} is the
 * whole of Spanish.
 */
public interface DomainProblem {

    /** What went wrong. For a collected failure, the umbrella under which the details sit. */
    Problem problem();

    /**
     * The contributing problems, or empty when there is only one.
     *
     * <p>Non-empty is the case worth having: a term that is not ready to submit
     * can fail seven rules at once, and an office worker should fix the whole
     * form in one pass rather than one field per round trip.
     */
    List<Problem> details();

    /**
     * One refused rule.
     *
     * @param code  names the message; the only thing a translation has to match
     * @param field the input at fault, as the API names it, or null when the
     *              problem is about the request rather than one value
     * @param args  substituted into the message in order
     */
    record Problem(String code, String field, List<Object> args) {

        public Problem {
            args = (args == null) ? List.of() : Collections.unmodifiableList(new ArrayList<>(args));
        }

        public static Problem of(String code, Object... args) {
            return new Problem(code, null, Arrays.stream(args).toList());
        }

        public static Problem onField(String field, String code, Object... args) {
            return new Problem(code, field, Arrays.stream(args).toList());
        }

        /**
         * The diagnostic form, for logs and stack traces -- never for a tenant
         * or an office worker. Deliberately shows the code rather than prose, so
         * that a developer reading a log can find the message it resolves to.
         */
        public String describe() {
            StringBuilder out = new StringBuilder();
            if (field != null) {
                out.append(field).append(": ");
            }
            out.append(code);
            if (!args.isEmpty()) {
                out.append(' ').append(args);
            }
            return out.toString();
        }
    }

    /** The diagnostic form of a whole failure, used as the exception's own message. */
    static String describe(Problem problem, List<Problem> details) {
        if (details.isEmpty()) {
            return problem.describe();
        }
        return problem.describe() + " -- "
                + details.stream().map(Problem::describe).collect(Collectors.joining("; "));
    }
}