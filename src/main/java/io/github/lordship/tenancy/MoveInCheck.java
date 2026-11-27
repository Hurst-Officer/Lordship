package io.github.lordship.tenancy;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One item of a move-in's office list as Glade shows the item: a fixed item (the server's list, MoveInCheckItem)
 * or a request the office added by hand. GET and PATCH /api/move-in/{tenancyId}/checks/{key} answer with this
 * record, and the lot screen carries the list.
 *
 * @param key           how the item is named in a call: the item's name for a fixed item ("PAGES_IN_ORDER"), the
 *                      request's id for a request
 * @param id            the move_in_check row, or null for a fixed item nobody has decided yet (PENDING)
 * @param applies       whether the item applies to this move-in (DEPOSIT only on a lease that takes a deposit). An
 *                      item that does not apply is shown as not needed, and never holds Finish back.
 * @param state         the decision that counts
 * @param decidedBy     the agent who set the state, or null: a state the page check set has decidedAt and no
 *                      decidedBy
 * @param machineState  what the page check read from the signed scan (PASSED, FAILED, UNSURE), or null
 * @param machineFound  what the page check found, in words the office reads ("Page 7 is missing")
 * @param question      a request's question (null on a fixed item)
 * @param answer        a request's answer, from the park, or null while unanswered
 */
public record MoveInCheck(
        UUID tenancyId,
        String key,
        UUID id,
        MoveInCheckItem item,
        boolean applies,
        MoveInCheckState state,
        String note,
        UUID decidedBy,
        String decidedByName,
        OffsetDateTime decidedAt,
        MoveInCheckState machineState,
        String machineFound,
        OffsetDateTime machineAt,
        String question,
        String answer,
        UUID answeredBy,
        String answeredByName,
        OffsetDateTime answeredAt,
        OffsetDateTime createdAt,
        UUID createdBy,
        String createdByName,
        OffsetDateTime deletedAt
) {
    /** Whether the item lets Finish go ahead: settled, or not applying to this move-in. */
    public boolean settled() {
        return !applies || state.settles();
    }

    /** A request the park has not answered yet, and the office has not settled either. */
    public boolean waitingOnPark() {
        return item == MoveInCheckItem.REQUEST && deletedAt == null && answer == null && !state.settles();
    }
}
