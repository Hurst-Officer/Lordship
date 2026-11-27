package io.github.lordship.instruments.internal;

import io.github.lordship.documentfiles.StoredFile;
import io.github.lordship.identity.AgentPrincipal;
import io.github.lordship.instruments.MoveInFinish;
import io.github.lordship.instruments.SignedScans;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.tenancy.MoveIn;
import io.github.lordship.tenancy.MoveInCheck;
import io.github.lordship.tenancy.MoveInCheckState;
import io.github.lordship.tenancy.MoveInChecks;
import io.github.lordship.tenancy.MoveInLine;
import io.github.lordship.tenancy.MoveIns;
import io.github.lordship.tenancy.TenancyRoll;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Glade's Move-In concern: the Move-In facts of one tenancy, the office's list for the signed lease (MoveInChecks),
 * and finishing the move-in. The screens themselves come from /api/screens/tenancies (screens
 * TenanciesScreenController).
 *
 * <p>Every call keeps to the agent's properties (PropertyScope): a tenancy on a property outside the scope is
 * answered as if the tenancy did not exist (404). Refusals come back as { message, code } in the worker's
 * language (config ApiExceptionHandler).
 *
 * <ul>
 *   <li>GET /api/move-in/{tenancyId} (tenancy:view): the Move-In facts (MoveIn). 404 for an unknown or deleted
 *   tenancy, and for one that did not come through Move-In.</li>
 *   <li>PATCH /api/move-in/{tenancyId} (tenancy:edit): moveInSigningOn, moveInSignedOn (YYYY-MM-DD or null),
 *   moveInCopyGiven (true or false). Answers with the facts. 409 move_in.not_moving_in, move_in.finished;
 *   400 tenancy.date_invalid, move_in.yes_or_no.</li>
 *   <li>GET /api/move-in/{tenancyId}/checks (tenancy:view): the office's list (MoveInCheck), every fixed item in
 *   order, then the requests. GET /checks/{key} (tenancy:view): one item, by the item's name ("PAGES_IN_ORDER")
 *   or a request's id; 404 for an unknown key.</li>
 *   <li>PATCH /api/move-in/{tenancyId}/checks/{key}: state, note, answer. A state of WAIVED needs move_in:waive;
 *   any other state, and a note, need move_in:tick (or move_in:waive); an answer to a request needs tenancy:edit.
 *   403 when the body asks for more than the agent may do. 400 move_in.check_state_unknown,
 *   move_in.answer_only_on_request; 409 move_in.not_moving_in, move_in.finished. Safe to send twice.</li>
 *   <li>POST /api/move-in/{tenancyId}/requests { question } (move_in:request): 201 the new request. 400
 *   move_in.question_needed. DELETE /requests/{id} (move_in:request): 204, 404 when unknown or already deleted.
 *   POST /requests/{id}/restore (move_in:request): the request; a live one is answered as the request is.</li>
 *   <li>POST /api/move-in/{tenancyId}/scans (move_in:upload), multipart with the PDF as "file": the signed scan of
 *   the move-in's lease (SignedScans). The pages are checked at once; the answer (201) is the scan with the page
 *   check. 400 move_in.scan_not_pdf, move_in.scan_empty; 409 move_in.no_lease_to_sign, move_in.finished.
 *   Not safe to send twice: a second upload is another scan (the newest counts).</li>
 *   <li>GET /api/move-in/{tenancyId}/scans (instrument:view): the move-in's signed scans, the newest first.
 *   GET /scans/{scanId}/file (instrument:view): the scan's PDF under the office's file name; every read is
 *   recorded.</li>
 *   <li>POST /api/move-in/{tenancyId}/finish (move_in:finish): puts the lease's rent in force and stamps
 *   the move-in finished (MoveInFinish). Answers with the move-in as a row (MoveInLine). Safe to send twice.
 *   409 move_in.not_moving_in, move_in.not_ready, move_in.checks_open, and the lease's own refusals. Finish is a
 *   separate permission, so handing finish to the property managers later is a change in Roles.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/move-in")
public class MoveInController {

    // What POST /requests takes: the office's question for the park
    public record AskRequest(String question) { }

    private final MoveIns moveIns;
    private final MoveInChecks moveInChecks;
    private final MoveInFinish moveInFinish;
    private final SignedScans signedScans;
    private final TenancyRoll tenancyRoll;

    public MoveInController(MoveIns moveIns, MoveInChecks moveInChecks, MoveInFinish moveInFinish,
                            SignedScans signedScans, TenancyRoll tenancyRoll) {
        this.moveIns = moveIns;
        this.moveInChecks = moveInChecks;
        this.moveInFinish = moveInFinish;
        this.signedScans = signedScans;
        this.tenancyRoll = tenancyRoll;
    }

    @PreAuthorize("hasAuthority('tenancy:view')")
    @GetMapping("/{tenancyId}")
    public ResponseEntity<MoveIn> get(@PathVariable UUID tenancyId, Authentication authentication) {
        if (!inScope(tenancyId, authentication)) {
            return ResponseEntity.notFound().build();
        }
        return moveIns.find(tenancyId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('tenancy:edit')")
    @PatchMapping("/{tenancyId}")
    public ResponseEntity<MoveIn> patch(@PathVariable UUID tenancyId, @RequestBody Map<String, Object> request,
                                        Authentication authentication) {
        if (!inScope(tenancyId, authentication)) {
            return ResponseEntity.notFound().build();
        }
        return moveIns.patch(tenancyId, request)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('tenancy:view')")
    @GetMapping("/{tenancyId}/checks")
    public ResponseEntity<List<MoveInCheck>> checks(@PathVariable UUID tenancyId, Authentication authentication) {
        if (!inScope(tenancyId, authentication)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(moveInChecks.of(tenancyId, moveInFinish.takesDeposit(tenancyId)));
    }

    @PreAuthorize("hasAuthority('tenancy:view')")
    @GetMapping("/{tenancyId}/checks/{key}")
    public ResponseEntity<MoveInCheck> check(@PathVariable UUID tenancyId, @PathVariable String key,
                                             Authentication authentication) {
        if (!inScope(tenancyId, authentication)) {
            return ResponseEntity.notFound().build();
        }
        return moveInChecks.find(tenancyId, key, moveInFinish.takesDeposit(tenancyId))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAnyAuthority('move_in:tick', 'move_in:waive', 'tenancy:edit')")
    @PatchMapping("/{tenancyId}/checks/{key}")
    public ResponseEntity<MoveInCheck> decide(@PathVariable UUID tenancyId, @PathVariable String key,
                                              @RequestBody Map<String, Object> request,
                                              Authentication authentication) {
        if (!inScope(tenancyId, authentication)) {
            return ResponseEntity.notFound().build();
        }
        if (!mayDecide(request, authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return moveInChecks.decide(tenancyId, key, request, moveInFinish.takesDeposit(tenancyId))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('move_in:request')")
    @PostMapping("/{tenancyId}/requests")
    public ResponseEntity<MoveInCheck> ask(@PathVariable UUID tenancyId, @RequestBody AskRequest request,
                                           Authentication authentication) {
        if (!inScope(tenancyId, authentication)) {
            return ResponseEntity.notFound().build();
        }
        Optional<MoveInCheck> asked = moveInChecks.ask(tenancyId, request.question(),
                moveInFinish.takesDeposit(tenancyId));
        return asked.map(check -> ResponseEntity.status(HttpStatus.CREATED).body(check))
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('move_in:request')")
    @DeleteMapping("/{tenancyId}/requests/{requestId}")
    public ResponseEntity<Void> removeRequest(@PathVariable UUID tenancyId, @PathVariable UUID requestId,
                                              Authentication authentication) {
        if (!inScope(tenancyId, authentication)) {
            return ResponseEntity.notFound().build();
        }
        return moveInChecks.removeRequest(tenancyId, requestId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PreAuthorize("hasAuthority('move_in:request')")
    @PostMapping("/{tenancyId}/requests/{requestId}/restore")
    public ResponseEntity<MoveInCheck> restoreRequest(@PathVariable UUID tenancyId, @PathVariable UUID requestId,
                                                      Authentication authentication) {
        if (!inScope(tenancyId, authentication)) {
            return ResponseEntity.notFound().build();
        }
        return moveInChecks.restoreRequest(tenancyId, requestId, moveInFinish.takesDeposit(tenancyId))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('move_in:upload')")
    @PostMapping("/{tenancyId}/scans")
    public ResponseEntity<SignedScans.SignedScan> upload(@PathVariable UUID tenancyId,
                                                         @RequestPart("file") MultipartFile file,
                                                         Authentication authentication) {
        if (!inScope(tenancyId, authentication)) {
            return ResponseEntity.notFound().build();
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw InvalidRequest.onField("file", "move_in.scan_not_pdf");
        }
        return signedScans.upload(tenancyId, content)
                .map(scan -> ResponseEntity.status(HttpStatus.CREATED).body(scan))
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('instrument:view')")
    @GetMapping("/{tenancyId}/scans")
    public ResponseEntity<List<SignedScans.SignedScan>> scans(@PathVariable UUID tenancyId,
                                                              Authentication authentication) {
        if (!inScope(tenancyId, authentication)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(signedScans.of(tenancyId));
    }

    @PreAuthorize("hasAuthority('instrument:view')")
    @GetMapping("/{tenancyId}/scans/{scanId}/file")
    public ResponseEntity<byte[]> scanFile(@PathVariable UUID tenancyId, @PathVariable UUID scanId,
                                           Authentication authentication) {
        if (!inScope(tenancyId, authentication)) {
            return ResponseEntity.notFound().build();
        }
        return signedScans.read(tenancyId, scanId)
                .map(MoveInController::fileResponse)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('move_in:finish')")
    @PostMapping("/{tenancyId}/finish")
    public ResponseEntity<MoveInLine> finish(@PathVariable UUID tenancyId, Authentication authentication) {
        if (!inScope(tenancyId, authentication)) {
            return ResponseEntity.notFound().build();
        }
        return moveInFinish.finish(tenancyId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // What a PATCH of a check may ask: WAIVED needs move_in:waive; any other state and a note need move_in:tick or
    // move_in:waive; an answer needs tenancy:edit (the park answers the office's requests)
    private static boolean mayDecide(Map<String, Object> request, Authentication authentication) {
        boolean tick = has(authentication, "move_in:tick");
        boolean waive = has(authentication, "move_in:waive");
        if (request.containsKey("state")) {
            boolean waiving = MoveInCheckState.named(request.get("state")).orElse(null) == MoveInCheckState.WAIVED;
            if (waiving ? !waive : !(tick || waive)) {
                return false;
            }
        }
        if (request.containsKey("note") && !(tick || waive)) {
            return false;
        }
        return !request.containsKey("answer") || has(authentication, "tenancy:edit");
    }

    private static boolean has(Authentication authentication, String permission) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(permission::equals);
    }

    // A stored file as a download under the file's own name. UTF-8, so a name such as "Peña" arrives whole.
    private static ResponseEntity<byte[]> fileResponse(StoredFile stored) {
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(stored.file().fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stored.file().contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(stored.content());
    }

    // The tenancy's lot is on a property the agent may see. An unknown tenancy is not in scope.
    private boolean inScope(UUID tenancyId, Authentication authentication) {
        return tenancyRoll.propertyOfTenancy(tenancyId)
                .map(propertyId -> AgentPrincipal.scopeOf(authentication).allows(propertyId))
                .orElse(false);
    }
}
