package io.github.lordship.tenancy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * One move-in as a row of Glade's Move-In lists and as the head of the move-in's own page: the tenancy, the lot,
 * the people, the lease, the Move-In facts, and the stage worked out from them.
 *
 * @param tenants          the tenants' names, the first on the tenancy first (empty for a viewer who may not see
 *                         who is on a tenancy)
 * @param tenantCount      the tenants on the tenancy, counted whether or not the viewer may see the names
 * @param leaseId          the tenancy's lease: the newest lease that was not abandoned, or null
 * @param leaseStatus      that lease's status (DRAFT, GENERATED, SENT, SERVED, APPROVED), or null
 * @param agreementType    the kind of agreement of that lease, or null
 * @param rentAccepted     a rent step of that lease is in force (ACTIVE)
 * @param scanUploadedAt   when the newest signed scan of that lease was uploaded, or null with none on file
 * @param checksFailed     items of the office's list marked FAILED (they send the move-in back to the park)
 * @param requestsOpen     requests from the office the park has not answered yet
 * @param stageSince       the day the move-in reached the stage it stands at, as far as the facts tell: the day
 *                         the move-in started (HOUSEHOLD, LEASE), the day the lease was generated (APPOINTMENT),
 *                         the day the signing is booked for (SIGNING), the later of the day the lease was signed
 *                         and the day the signed scan was uploaded (REVIEW), the day the move-in was finished
 *                         (MOVED_IN). Null when the fact is missing.
 */
public record MoveInLine(
        UUID tenancyId,
        UUID lotId,
        UUID propertyId,
        String propertyCode,
        String lotNumber,
        LocalDate startDate,
        List<String> tenants,
        int tenantCount,
        UUID leaseId,
        String leaseStatus,
        String leaseSerial,
        OffsetDateTime leaseGeneratedAt,
        String agreementType,
        boolean rentAccepted,
        OffsetDateTime moveInStartedAt,
        LocalDate moveInSigningOn,
        LocalDate moveInSignedOn,
        boolean moveInCopyGiven,
        OffsetDateTime moveInDoneAt,
        OffsetDateTime deletedAt,
        OffsetDateTime scanUploadedAt,
        int checksFailed,
        int requestsOpen,
        MoveInStage stage,
        MoveInStage.WaitingOn waitingOn,
        LocalDate stageSince
) {

    /** The same move-in without the tenants' names, for a viewer who may not see who is on a tenancy. */
    public MoveInLine withoutNames() {
        return new MoveInLine(tenancyId, lotId, propertyId, propertyCode, lotNumber, startDate, List.of(),
                tenantCount, leaseId, leaseStatus, leaseSerial, leaseGeneratedAt, agreementType, rentAccepted,
                moveInStartedAt, moveInSigningOn, moveInSignedOn, moveInCopyGiven, moveInDoneAt, deletedAt,
                scanUploadedAt, checksFailed, requestsOpen, stage, waitingOn, stageSince);
    }

    /** The day a moment falls on in the office's calendar (the server's zone, see LORDSHIP_ZONE). */
    static LocalDate dayOf(OffsetDateTime moment) {
        return moment == null ? null : moment.atZoneSameInstant(ZoneId.systemDefault()).toLocalDate();
    }
}
