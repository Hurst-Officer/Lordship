package io.github.lordship.tenancy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The Move-In facts of one tenancy: the tenancy's move_in_ columns. GET and PATCH /api/move-in/{tenancyId} answer
 * with this record. The names are the camelCase columns, the same names the audit log keeps the changes under.
 *
 * @param moveInStartedAt when the tenancy was opened through Move-In (never null here: a tenancy without the
 *                        stamp is not a move-in, and has no record of this kind)
 * @param moveInSigningOn the day the signing is booked for
 * @param moveInSignedOn  the day the lease was actually signed
 * @param moveInCopyGiven the household was given a copy of the signed packet
 * @param moveInDoneAt    when the move-in was finished, or null while the move-in is in process
 * @param moveInDoneBy    the agent who finished the move-in
 */
public record MoveIn(
        UUID tenancyId,
        OffsetDateTime moveInStartedAt,
        LocalDate moveInSigningOn,
        LocalDate moveInSignedOn,
        boolean moveInCopyGiven,
        OffsetDateTime moveInDoneAt,
        UUID moveInDoneBy
) {
    public boolean isDone() {
        return moveInDoneAt != null;
    }
}
