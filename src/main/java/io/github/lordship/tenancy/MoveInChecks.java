package io.github.lordship.tenancy;

import io.github.lordship.audit.ActingAgent;
import io.github.lordship.audit.AuditContext;
import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.RuleConflict;
import io.github.lordship.tenancy.internal.MoveInCheckRepository;
import io.github.lordship.tenancy.internal.MoveInCheckRepository.MoveInCheckRow;
import io.github.lordship.tenancy.internal.MoveInRepository;
import io.github.lordship.tenancy.internal.MoveInRepository.MoveInRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The office's list for a move-in's signed lease (Glade's Office review), saved as the worker ticks: the fixed items
 * of MoveInCheckItem and the requests the office adds by hand. Each item has a state (MoveInCheckState), a note,
 * who decided and when, and what the page check of the signed scan read (the machine reading).
 *
 * <p>A fixed item is named by the item's name in a call ("PAGES_IN_ORDER"), a request by the request's id.
 * Whether a lease takes a deposit (and so whether DEPOSIT applies) is the lease's business, so the caller says so
 * (instruments MoveInFinish.takesDeposit): the deal's terms belong to a module that already depends on this one.
 *
 * <p>Every write is recorded in the audit log under move_in_check, with the row's camelCase names. Who may do what
 * is the controller's: ticking (move_in:tick), waiving (move_in:waive), asking (move_in:request), answering
 * (tenancy:edit).
 */
@Service
public class MoveInChecks {

    private final MoveInCheckRepository repository;
    private final MoveInRepository moveInRepository;
    private final AuditService auditService;
    private final AuditContext auditContext;

    public MoveInChecks(MoveInCheckRepository repository, MoveInRepository moveInRepository,
                        AuditService auditService, AuditContext auditContext) {
        this.repository = repository;
        this.moveInRepository = moveInRepository;
        this.auditService = auditService;
        this.auditContext = auditContext;
    }

    // ---- reading -------------------------------------------------------------------------------

    /**
     * A move-in's list: every fixed item in order (an item nobody decided yet is PENDING, with no id), then the live
     * requests, the oldest first. Empty list for a tenancy that is not a move-in.
     */
    public List<MoveInCheck> of(UUID tenancyId, boolean depositTaken) {
        if (!isMoveIn(tenancyId)) {
            return List.of();
        }
        List<MoveInCheckRow> rows = repository.findByTenancy(tenancyId);
        Map<UUID, String> names = repository.names(rows.stream()
                .flatMap(row -> Stream.of(row.decidedBy(), row.answeredBy(), row.createdBy()))
                .toList());
        List<MoveInCheck> checks = new ArrayList<>();
        for (MoveInCheckItem item : MoveInCheckItem.fixed()) {
            MoveInCheckRow row = rows.stream().filter(r -> r.item().equals(item.name())).findFirst().orElse(null);
            checks.add(row == null ? pending(tenancyId, item, depositTaken) : check(row, depositTaken, names));
        }
        rows.stream()
                .filter(row -> MoveInCheckItem.REQUEST.name().equals(row.item()))
                .forEach(row -> checks.add(check(row, depositTaken, names)));
        return checks;
    }

    /** One item of a move-in's list by the item's key. Empty for an unknown key, and for a tenancy that is not a move-in. */
    public Optional<MoveInCheck> find(UUID tenancyId, String key, boolean depositTaken) {
        if (!isMoveIn(tenancyId)) {
            return Optional.empty();
        }
        Optional<MoveInCheckItem> fixed = MoveInCheckItem.fixedNamed(key);
        if (fixed.isPresent()) {
            return Optional.of(repository.findFixed(tenancyId, fixed.get().name())
                    .map(row -> check(row, depositTaken, namesOf(row)))
                    .orElseGet(() -> pending(tenancyId, fixed.get(), depositTaken)));
        }
        return requestRow(tenancyId, key)
                .filter(row -> row.deletedAt() == null)
                .map(row -> check(row, depositTaken, namesOf(row)));
    }

    /** The items that hold Finish back: those that apply and are not settled, and the live unsettled requests. */
    public List<MoveInCheck> unsettled(UUID tenancyId, boolean depositTaken) {
        return of(tenancyId, depositTaken).stream().filter(check -> !check.settled()).toList();
    }

    /** The tenancy a move_in_check row belongs to (for the history bars' scope), deleted or not. */
    public Optional<UUID> tenancyOfCheck(UUID checkId) {
        return repository.tenancyOf(checkId);
    }

    // ---- deciding ------------------------------------------------------------------------------

    /**
     * Changes one item: request is the PATCH body, with state (a MoveInCheckState name), note (text; blank clears
     * the note) and, on a request only, answer (text; blank clears the answer). Other names are ignored. A state
     * set here is a person's decision: decidedBy is the agent, decidedAt now. An answer records who answered.
     *
     * <p>Empty for an unknown key and for a tenancy that is gone. 409 move_in.not_moving_in, move_in.finished.
     * 400 move_in.check_state_unknown, move_in.answer_only_on_request. Sending what the item already holds
     * changes nothing and is answered with the item.
     */
    @Transactional
    public Optional<MoveInCheck> decide(UUID tenancyId, String key, Map<String, Object> request, boolean depositTaken) {
        if (!requireInProcess(tenancyId)) {
            return Optional.empty();
        }
        UUID agent = ActingAgent.resolve(auditContext);

        Optional<MoveInCheckItem> fixed = MoveInCheckItem.fixedNamed(key);
        if (fixed.isEmpty() && requestRow(tenancyId, key).filter(row -> row.deletedAt() == null).isEmpty()) {
            return Optional.empty();
        }
        if (fixed.isPresent() && request.containsKey("answer")) {
            throw InvalidRequest.onField("answer", "move_in.answer_only_on_request");
        }

        Map<String, Object> changes = new LinkedHashMap<>();
        if (request.containsKey("state")) {
            MoveInCheckState state = MoveInCheckState.named(request.get("state"))
                    .orElseThrow(() -> InvalidRequest.onField("state", "move_in.check_state_unknown", request.get("state")));
            changes.put("state", state.name());
        }
        if (request.containsKey("note")) {
            changes.put("note", textOf(request.get("note")));
        }
        if (request.containsKey("answer")) {
            changes.put("answer", textOf(request.get("answer")));
        }

        MoveInCheckRow before;
        if (fixed.isPresent()) {
            MoveInCheckRepository.Ensured ensured = repository.ensureFixed(tenancyId, fixed.get().name(), agent)
                    .orElseThrow(() -> RuleConflict.of("move_in.finished"));
            if (ensured.inserted()) {
                auditService.recordInsert("move_in_check", ensured.row().uuid(), AuditMapper.toMap(ensured.row()));
            }
            before = ensured.row();
        } else {
            before = requestRow(tenancyId, key).orElseThrow();
        }

        // only what differs is written: the same state and note sent again change nothing, not even who decided
        changes.entrySet().removeIf(change -> java.util.Objects.equals(change.getValue(), valueOf(before, change.getKey())));
        if (changes.isEmpty()) {
            return Optional.of(check(before, depositTaken, namesOf(before)));
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (changes.containsKey("state") || changes.containsKey("note")) {
            changes.put("decidedBy", agent);
            changes.put("decidedAt", now);
        }
        if (changes.containsKey("answer")) {
            changes.put("answeredBy", changes.get("answer") == null ? null : agent);
            changes.put("answeredAt", changes.get("answer") == null ? null : now);
        }

        MoveInCheckRow after = repository.update(before.uuid(), changes)
                .orElseThrow(() -> RuleConflict.of("move_in.finished"));
        recordUpdate(before, after);
        return Optional.of(check(after, depositTaken, namesOf(after)));
    }

    /**
     * A request: a check the office adds by hand, with a question for the park. 400 move_in.question_needed for a
     * blank question. Empty for a tenancy that is gone. 409 move_in.not_moving_in, move_in.finished.
     */
    @Transactional
    public Optional<MoveInCheck> ask(UUID tenancyId, String question, boolean depositTaken) {
        String asked = textOf(question);
        if (asked == null) {
            throw InvalidRequest.onField("question", "move_in.question_needed");
        }
        if (!requireInProcess(tenancyId)) {
            return Optional.empty();
        }
        MoveInCheckRow saved = repository.insertRequest(tenancyId, asked, ActingAgent.resolve(auditContext));
        auditService.recordInsert("move_in_check", saved.uuid(), AuditMapper.toMap(saved));
        return Optional.of(check(saved, depositTaken, namesOf(saved)));
    }

    /**
     * Takes a request off the list (a request asked by mistake). True when the request was live and is deleted
     * now; false when the request is unknown or already deleted (a second call is safe).
     * 409 move_in.not_moving_in, move_in.finished.
     */
    @Transactional
    public boolean removeRequest(UUID tenancyId, UUID requestId) {
        if (!requireInProcess(tenancyId)) {
            return false;
        }
        Optional<MoveInCheckRow> before = requestRow(tenancyId, requestId.toString());
        if (before.isEmpty() || before.get().deletedAt() != null) {
            return false;
        }
        return repository.deleteRequest(requestId).map(after -> {
            auditService.recordDelete("move_in_check", requestId, AuditMapper.toMap(before.get()));
            return true;
        }).orElse(false);
    }

    /**
     * Brings a deleted request back, and answers with the request. A live request is answered as the request is.
     * Empty for an unknown request. 409 move_in.not_moving_in, move_in.finished.
     */
    @Transactional
    public Optional<MoveInCheck> restoreRequest(UUID tenancyId, UUID requestId, boolean depositTaken) {
        if (!requireInProcess(tenancyId)) {
            return Optional.empty();
        }
        Optional<MoveInCheckRow> before = requestRow(tenancyId, requestId.toString());
        if (before.isEmpty()) {
            return Optional.empty();
        }
        if (before.get().deletedAt() == null) {
            return Optional.of(check(before.get(), depositTaken, namesOf(before.get())));
        }
        return repository.restoreRequest(requestId).map(after -> {
            recordUpdate(before.get(), after);
            return check(after, depositTaken, namesOf(after));
        });
    }

    /**
     * What the page check of a signed scan read for one item (instruments SignedScans): the machine reading and
     * what was found, set on the item's row (made first when the item has none). An item the page check decides by
     * itself (MoveInCheckItem.machineDecides: PAGES_IN_ORDER) takes the reading as the item's state too, with no
     * agent as the decider: a new scan replaces whatever was decided about the scan before. Any other item keeps a
     * person's decision. Called in the transaction that stores the scan. Does nothing for a finished move-in.
     */
    @Transactional
    public void recordReading(UUID tenancyId, MoveInCheckItem item, MoveInCheckState reading, String found) {
        if (item == MoveInCheckItem.REQUEST || !isInProcess(tenancyId)) {
            return;
        }
        UUID agent = ActingAgent.resolve(auditContext);
        MoveInCheckRepository.Ensured ensured = repository.ensureFixed(tenancyId, item.name(), agent).orElse(null);
        if (ensured == null) {
            return;
        }
        if (ensured.inserted()) {
            auditService.recordInsert("move_in_check", ensured.row().uuid(), AuditMapper.toMap(ensured.row()));
        }
        OffsetDateTime now = OffsetDateTime.now();
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("machineState", reading.name());
        changes.put("machineFound", found);
        changes.put("machineAt", now);
        if (item.machineDecides()) {
            changes.put("state", reading.name());
            changes.put("decidedBy", null);
            changes.put("decidedAt", now);
        }
        repository.update(ensured.row().uuid(), changes)
                .ifPresent(after -> recordUpdate(ensured.row(), after));
    }

    // ---- internals -----------------------------------------------------------------------------

    // A tenancy that is live and came through Move-In
    private boolean isMoveIn(UUID tenancyId) {
        return moveInRepository.findFacts(tenancyId).filter(row -> row.moveInStartedAt() != null).isPresent();
    }

    // A live move-in that is not finished
    private boolean isInProcess(UUID tenancyId) {
        return moveInRepository.findFacts(tenancyId)
                .filter(row -> row.moveInStartedAt() != null && row.moveInDoneAt() == null)
                .isPresent();
    }

    // False for a tenancy that is gone; 409 for one that is not a move-in or is finished
    private boolean requireInProcess(UUID tenancyId) {
        Optional<MoveInRow> facts = moveInRepository.findFacts(tenancyId);
        if (facts.isEmpty()) {
            return false;
        }
        if (facts.get().moveInStartedAt() == null) {
            throw RuleConflict.of("move_in.not_moving_in");
        }
        if (facts.get().moveInDoneAt() != null) {
            throw RuleConflict.of("move_in.finished");
        }
        return true;
    }

    // A request of the move-in by the request's id (deleted or not), or empty for a key that is not one
    private Optional<MoveInCheckRow> requestRow(UUID tenancyId, String key) {
        UUID id;
        try {
            id = UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return repository.findById(id)
                .filter(row -> row.tenancyId().equals(tenancyId))
                .filter(row -> MoveInCheckItem.REQUEST.name().equals(row.item()));
    }

    private void recordUpdate(MoveInCheckRow before, MoveInCheckRow after) {
        AuditMapper.Diff diff = AuditMapper.diff(before, after);
        if (!diff.after().isEmpty()) {
            auditService.recordUpdate("move_in_check", after.uuid(), diff.before(), diff.after());
        }
    }

    // What a row holds under one of the names decide sets
    private static Object valueOf(MoveInCheckRow row, String name) {
        return switch (name) {
            case "state" -> row.state();
            case "note" -> row.note();
            case "answer" -> row.answer();
            default -> null;
        };
    }

    // Text from a request: trimmed, and null for nothing or blank
    private static String textOf(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private Map<UUID, String> namesOf(MoveInCheckRow row) {
        return repository.names(Stream.of(row.decidedBy(), row.answeredBy(), row.createdBy()).toList());
    }

    // A fixed item nobody has decided yet
    private static MoveInCheck pending(UUID tenancyId, MoveInCheckItem item, boolean depositTaken) {
        return new MoveInCheck(tenancyId, item.name(), null, item, item.appliesTo(depositTaken),
                MoveInCheckState.PENDING, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    private static MoveInCheck check(MoveInCheckRow row, boolean depositTaken, Map<UUID, String> names) {
        MoveInCheckItem item = MoveInCheckItem.valueOf(row.item());
        String key = item == MoveInCheckItem.REQUEST ? row.uuid().toString() : item.name();
        return new MoveInCheck(row.tenancyId(), key, row.uuid(), item, item.appliesTo(depositTaken),
                MoveInCheckState.valueOf(row.state()), row.note(), row.decidedBy(), names.get(row.decidedBy()),
                row.decidedAt(), row.machineState() == null ? null : MoveInCheckState.valueOf(row.machineState()),
                row.machineFound(), row.machineAt(), row.question(), row.answer(), row.answeredBy(),
                names.get(row.answeredBy()), row.answeredAt(), row.createdAt(), row.createdBy(),
                names.get(row.createdBy()), row.deletedAt());
    }
}
