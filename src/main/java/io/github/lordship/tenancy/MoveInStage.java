package io.github.lordship.tenancy;

/**
 * Where a move-in stands: the steps from "these people will become tenants" to a tenancy that belongs to the
 * rent roll (Glade's Move-In concern). Worked out from the facts every time, in {@link #of}, and never stored, so
 * the stage cannot drift from the facts. Who the move-in waits on is worked out here too ({@link #waitingOn}).
 *
 * <p>The stages come in this order, and a move-in stands at the first one that is not done:
 * <ol>
 *   <li>HOUSEHOLD: at least one tenant is on the tenancy.</li>
 *   <li>LEASE: the lease is generated (a draft, or no lease at all, is still this stage).</li>
 *   <li>APPOINTMENT: the signing is booked for a day.</li>
 *   <li>SIGNING: the day the lease was actually signed is recorded, the household was given a copy, the signed
 *   scan of the lease is on file, and the scan's page check is settled (passed, or decided by a person).</li>
 *   <li>REVIEW: the office looks the move-in over and finishes the move-in.</li>
 * </ol>
 * MOVED_IN is a finished move-in.
 */
public enum MoveInStage {
    HOUSEHOLD(WaitingOn.MANAGER),
    LEASE(WaitingOn.OFFICE),
    APPOINTMENT(WaitingOn.MANAGER),
    SIGNING(WaitingOn.MANAGER),
    REVIEW(WaitingOn.OFFICE),
    MOVED_IN(null);

    /** Who the next step of a stage is waiting on: the property manager at the park, or the main office. */
    public enum WaitingOn { MANAGER, OFFICE }

    /**
     * The facts a stage is worked out from.
     *
     * @param tenants      the tenants on the tenancy now
     * @param leaseStatus  the status of the tenancy's lease (the newest lease that was not abandoned), or null when
     *                     the tenancy has none: DRAFT, GENERATED, SENT, SERVED or APPROVED
     * @param booked       the signing is booked for a day
     * @param signed       the day the lease was actually signed is recorded
     * @param copyGiven    the household was given a copy of the signed packet
     * @param scanOnFile   a signed scan of the lease is on file (move_in_file, SIGNED_LEASE)
     * @param pagesSettled the scan's page check (the PAGES_IN_ORDER item) is passed, waived or not needed
     * @param checksFailed items of the office's list that a person or the page check marked FAILED
     * @param requestsOpen requests from the office the park has not answered yet
     * @param done         the move-in was finished
     */
    public record Facts(int tenants, String leaseStatus, boolean booked, boolean signed, boolean copyGiven,
                        boolean scanOnFile, boolean pagesSettled, int checksFailed, int requestsOpen,
                        boolean done) { }

    private final WaitingOn waitingOn;

    MoveInStage(WaitingOn waitingOn) {
        this.waitingOn = waitingOn;
    }

    /** Who the stage waits on, before the facts of one move-in are known; null for a finished move-in. */
    public WaitingOn waitingOn() {
        return waitingOn;
    }

    /** The stage a move-in stands at. */
    public static MoveInStage of(Facts facts) {
        if (facts.done()) {
            return MOVED_IN;
        }
        if (facts.tenants() == 0) {
            return HOUSEHOLD;
        }
        String lease = facts.leaseStatus();
        if (lease == null || "DRAFT".equals(lease) || "ABANDONED".equals(lease)) {
            return LEASE;
        }
        if (!facts.booked()) {
            return APPOINTMENT;
        }
        if (!facts.signed() || !facts.copyGiven() || !facts.scanOnFile() || !facts.pagesSettled()) {
            return SIGNING;
        }
        return REVIEW;
    }

    /**
     * Who a move-in at this stage waits on. At Office review, an item the office marked FAILED or a request the
     * park has not answered puts the move-in back on the property manager; otherwise the stage's own.
     */
    public static WaitingOn waitingOn(MoveInStage stage, Facts facts) {
        if (stage == REVIEW && (facts.checksFailed() > 0 || facts.requestsOpen() > 0)) {
            return WaitingOn.MANAGER;
        }
        return stage.waitingOn();
    }
}
