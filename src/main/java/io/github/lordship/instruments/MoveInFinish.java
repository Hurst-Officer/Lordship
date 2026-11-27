package io.github.lordship.instruments;

import io.github.lordship.audit.ActingAgent;
import io.github.lordship.audit.AuditContext;
import io.github.lordship.shared.RuleConflict;
import io.github.lordship.shared.SecurityDepositMethod;
import io.github.lordship.tenancy.MoveInChecks;
import io.github.lordship.tenancy.MoveInLine;
import io.github.lordship.tenancy.MoveInStage;
import io.github.lordship.tenancy.MoveIns;
import io.github.lordship.tenancy.TenancyService;
import io.github.lordship.tenancyterms.TenancyChargeTerm;
import io.github.lordship.tenancyterms.TenancyChargeTermService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/**
 * Finishes a move-in (Glade's Move-In concern): the office's last step, after the move-in passed every stage
 * before Office review and every item of the office's list that applies is settled (MoveInChecks: passed, waived
 * or not needed), whoever presses Finish. In one transaction, all or nothing:
 * <ol>
 *   <li>the lease's rent goes into force: every pending rent step of the lease becomes active from the step's own
 *   day (InstrumentService.accept);</li>
 *   <li>the lease becomes APPROVED, with the newest signed scan as the signed copy on file (returned_file), the day
 *   the scan came in (returned_on) and the day the household signed (signed_on) (InstrumentService.approve);</li>
 *   <li>the move-in is stamped finished, by the agent who finished the move-in (MoveIns.markDone).</li>
 * </ol>
 * The tenancy then belongs to the rent roll and leaves Move-In's lists.
 *
 * <p>Here and not in the tenancy module, because putting rent in force is the instruments module's business, and
 * this module already depends on the tenancy module (the other way round would be a cycle).
 *
 * <p>The security deposit is not written here yet: the payments taken at the signing are not recorded, so the
 * amount actually received is not known. The money slice writes the deposit row (SecurityDepositService) from the
 * recorded payment.
 */
@Service
public class MoveInFinish {

    private final MoveIns moveIns;
    private final MoveInChecks moveInChecks;
    private final SignedScans signedScans;
    private final InstrumentService instrumentService;
    private final TenancyService tenancyService;
    private final TenancyChargeTermService chargeTermService;
    private final AuditContext auditContext;

    public MoveInFinish(MoveIns moveIns, MoveInChecks moveInChecks, SignedScans signedScans,
                        InstrumentService instrumentService, TenancyService tenancyService,
                        TenancyChargeTermService chargeTermService, AuditContext auditContext) {
        this.moveIns = moveIns;
        this.moveInChecks = moveInChecks;
        this.signedScans = signedScans;
        this.instrumentService = instrumentService;
        this.tenancyService = tenancyService;
        this.chargeTermService = chargeTermService;
        this.auditContext = auditContext;
    }

    /**
     * Whether a move-in's lease takes a security deposit (a residential deal): the deposit method of the lease's
     * first rent step is not NONE. The office's DEPOSIT item applies only then. False for a move-in with no lease.
     */
    public boolean takesDeposit(UUID tenancyId) {
        return moveIns.line(tenancyId).map(this::takesDeposit).orElse(false);
    }

    private boolean takesDeposit(MoveInLine line) {
        if (line.leaseId() == null) {
            return false;
        }
        return chargeTermService.findBySource(line.leaseId()).stream()
                .min(Comparator.comparing(TenancyChargeTerm::validAt))
                .map(TenancyChargeTerm::securityDepositMethod)
                .filter(method -> method != SecurityDepositMethod.NONE)
                .isPresent();
    }

    /**
     * Finishes the move-in of a tenancy, and answers with the move-in as it stands after.
     *
     * <p>Empty for an unknown or deleted tenancy. 409 move_in.not_moving_in for a tenancy that did not come through
     * Move-In; 409 move_in.not_ready for a move-in that does not stand at Office review; 409 move_in.checks_open
     * (with how many) while an item of the office's list that applies is not settled. A move-in that is already
     * finished is answered as it is, so sending the finish twice is safe. Refusals of the lease's accept (409
     * instrument.not_generated, term.day_taken) come through as they are, and nothing is finished.
     */
    @Transactional
    public Optional<MoveInLine> finish(UUID tenancyId) {
        Optional<MoveInLine> found = moveIns.line(tenancyId);
        if (found.isEmpty()) {
            if (tenancyService.findTenancyById(tenancyId).isPresent()) {
                throw RuleConflict.of("move_in.not_moving_in");
            }
            return Optional.empty();
        }
        MoveInLine line = found.get();
        if (line.deletedAt() != null) {
            return Optional.empty();
        }
        if (line.stage() == MoveInStage.MOVED_IN) {
            return Optional.of(line);
        }
        if (line.stage() != MoveInStage.REVIEW || line.leaseId() == null) {
            throw RuleConflict.of("move_in.not_ready");
        }
        int open = moveInChecks.unsettled(tenancyId, takesDeposit(line)).size();
        if (open > 0) {
            throw RuleConflict.of("move_in.checks_open", open);
        }

        // the stage says a signed scan is on file; the newest one is the signed copy the lease is approved with
        SignedScans.SignedScan scan = signedScans.latest(tenancyId, line.leaseId())
                .orElseThrow(() -> RuleConflict.of("move_in.not_ready"));

        instrumentService.accept(line.leaseId())
                .orElseThrow(() -> RuleConflict.of("move_in.not_ready"));
        instrumentService.approve(line.leaseId(),
                        scan.createdAt().atZoneSameInstant(ZoneId.systemDefault()).toLocalDate(),
                        scan.file(), line.moveInSignedOn())
                .orElseThrow(() -> RuleConflict.of("move_in.not_ready"));
        moveIns.markDone(tenancyId, ActingAgent.resolve(auditContext))
                // finished by someone else between the read and this write: their finish stands
                .orElseThrow(() -> RuleConflict.of("move_in.finished"));
        return moveIns.line(tenancyId);
    }
}
