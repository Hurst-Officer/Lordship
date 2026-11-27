package io.github.lordship.tenancy;

import java.util.Arrays;
import java.util.Optional;

/**
 * Where one item of a move-in's office list stands (move_in_check.state).
 *
 * <p>PASSED, WAIVED and NOT_NEEDED settle the item: Finish is refused while any item that applies is not settled,
 * whoever presses Finish. FAILED sends the move-in back to the park with the reason (the note). UNSURE is a reading
 * that needs a person, or a rescan.
 */
public enum MoveInCheckState {
    PENDING,
    PASSED,
    FAILED,
    UNSURE,
    /** Accepted although not met, by someone who may waive (move_in:waive), with the reason in the note. */
    WAIVED,
    /** The item does not apply to this move-in (no assistance animal, no deposit on the deal). */
    NOT_NEEDED;

    /** Whether the item is settled: PASSED, WAIVED or NOT_NEEDED. */
    public boolean settles() {
        return this == PASSED || this == WAIVED || this == NOT_NEEDED;
    }

    /** The state a request names ("PASSED"), or empty for any other name. */
    public static Optional<MoveInCheckState> named(Object raw) {
        String name = raw == null ? "" : raw.toString().trim();
        return Arrays.stream(values()).filter(state -> state.name().equals(name)).findFirst();
    }
}
