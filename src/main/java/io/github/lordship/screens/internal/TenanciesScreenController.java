package io.github.lordship.screens.internal;

import io.github.lordship.audit.AuditService;
import io.github.lordship.audit.History;
import io.github.lordship.documenttemplate.PropertyDocumentAssignmentService;
import io.github.lordship.identity.AgentPrincipal;
import io.github.lordship.instruments.Instrument;
import io.github.lordship.instruments.InstrumentService;
import io.github.lordship.instruments.InstrumentStatus;
import io.github.lordship.instruments.MoveInFinish;
import io.github.lordship.instruments.OnExpiry;
import io.github.lordship.instruments.SignedScans;
import io.github.lordship.lots.LotOffers;
import io.github.lordship.properties.Property;
import io.github.lordship.properties.PropertyService;
import io.github.lordship.screens.internal.ScreenResponses.RatesPark;
import io.github.lordship.screens.internal.ScreenResponses.RatesStandard;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;
import io.github.lordship.shared.PropertyScope;
import io.github.lordship.shared.TenancyStanding;
import io.github.lordship.tenancy.MoveInCheck;
import io.github.lordship.tenancy.MoveInChecks;
import io.github.lordship.tenancy.MoveInLine;
import io.github.lordship.tenancy.MoveIns;
import io.github.lordship.tenancy.TenancyRoll;
import io.github.lordship.tenancy.TenancyService;
import io.github.lordship.tenancyterms.TenancyChargeTermResponse;
import io.github.lordship.tenancyterms.TenancyChargeTermService;
import io.github.lordship.termstemplate.TermsTemplate;
import io.github.lordship.termstemplate.TermsTemplateService;
import io.github.lordship.termstemplate.internal.TermsTemplateResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One call per screen of Glade's Move-In concern (the code keeps the name Tenancies, the data type a move-in is):
 * every property, one property, one lot's move-in. A move-in is a tenancy opened through Move-In and not
 * finished yet (tenancy MoveIns); each screen carries the move-ins as MoveInLine, with the stage each stands at.
 * The screens also still carry each tenancy's standing, which the lot maps and the bar's search read.
 *
 * <p>Every screen needs tenancy:view. The people on a tenancy also need tenants:view (members is null without
 * that permission), their phone and email persons:view, the rent tenancy_term:view (terms and leaseTerms are
 * null), a property's deals terms_template:view (deals is null), and a tenancy's documents instrument:view
 * (papers is null). history is null without audit:view.
 * zone is the viewer's time zone, like America/Los_Angeles.
 * A property outside the agent's scope (see PropertyScope) is treated as if the property did not exist: 404.
 */
@RestController
@RequestMapping("/api/screens/tenancies")
public class TenanciesScreenController {

    // The list of every property (X1): each property in the agent's scope with the property's standing, and every
    // move-in in process on those properties, the oldest first. today is the server's date.
    public record TenanciesScreen(
            List<RatesPark> properties,
            List<TenancyRoll.ParkLine> lines,
            List<MoveInLine> moveIns,
            LocalDate today
    ) { }

    // One property (X2): the lots (with what each offers and whether each is taken), every tenancy the lots have
    // had (ended ones included; Glade keeps the ended ones back until asked), and the two standard rates of each
    // of the property's deals. today is the server's date, the date "ended" and "in force" were worked out with.
    public record TenanciesPropertyScreen(
            RatesPark property,
            List<LotOffers.LotLine> lots,
            List<TenancyRoll.Line> tenancies,
            List<RatesStandard> standards,
            List<MoveInLine> moveIns,
            LocalDate today,
            History.PageHistory history
    ) { }

    /**
     * One lot (X3). tenancies is every tenancy the lot has had, the newest start first (with tenancy:delete also
     * the deleted ones, each with deletedAt set). shown is the id of the tenancy the page opens: the one asked
     * for, else the lot's tenancy that has not ended (the newest start when there are two), else null: an open
     * lot, or a lot whose tenancies have all ended.
     * members and terms are the shown tenancy's (empty with none shown; null without the permission).
     * standards and deals are the property's, for the rent and the deal a new tenancy would come under.
     * defaultStart is the start date a new tenancy gets when the worker leaves the date blank.
     * papers is every document written for the shown tenancy, the newest first. leaseTerms is the charge terms of
     * the shown tenancy's lease (the newest lease that was not abandoned), earliest first, with every fee: the
     * rent steps the lease panel edits while the lease is a draft. leaseDocuments says, for each kind of agreement
     * the lot offers, which document the property prints a lease from; a kind with none is left out.
     * moveIns is every move-in the lot has had (deleted or finished included), the oldest first: the lot's
     * tenancies that came through Move-In. A tenancy that is not among them (the data migration) belongs to the
     * rent roll and is only read on this screen.
     * checks is the office's list for the shown tenancy's move-in (MoveInChecks: the fixed items in order, then the
     * requests), empty when the shown tenancy is not a move-in. scans is the shown move-in's signed scans, the
     * newest first, with each scan's page check; null without instrument:view.
     */
    public record TenanciesLotScreen(
            LotOffers.LotLine lot,
            RatesPark property,
            List<RatesStandard> standards,
            List<TermsTemplateResponse> deals,
            List<TenancyRoll.Line> tenancies,
            UUID shown,
            List<TenancyRoll.Member> members,
            List<TenancyRoll.Term> terms,
            List<Paper> papers,
            List<TenancyChargeTermResponse> leaseTerms,
            List<LeaseDocument> leaseDocuments,
            List<MoveInLine> moveIns,
            List<MoveInCheck> checks,
            List<SignedScans.SignedScan> scans,
            LocalDate today,
            LocalDate defaultStart,
            History.PageHistory history
    ) { }

    // One document written for a tenancy, as the lease panel needs the document: the same names the instrument
    // endpoints answer with. lastCoveredDay is the last day of the document's own period, worked out here.
    public record Paper(UUID id, UUID tenancyId, InstrumentType type, AgreementType agreementType,
                        InstrumentStatus status, String serial, LocalDate termStart, Integer termMonths,
                        LocalDate lastCoveredDay, OnExpiry onExpiry, OffsetDateTime generatedAt, String note,
                        OffsetDateTime createdAt) {
        static Paper from(Instrument paper) {
            return new Paper(paper.uuid(), paper.tenancy(), paper.type(), paper.agreementType(), paper.status(),
                    paper.serial(), paper.termStart(), paper.termMonths(), paper.lastCoveredDay().orElse(null),
                    paper.onExpiry(), paper.generatedAt(), paper.note(), paper.createdAt());
        }
    }

    // The document a property prints a lease from, for one kind of agreement
    public record LeaseDocument(AgreementType agreementType, String documentName) { }

    private final PropertyService propertyService;
    private final LotOffers lotOffers;
    private final TenancyRoll tenancyRoll;
    private final TermsTemplateService termsTemplateService;
    private final AuditService auditService;
    private final InstrumentService instrumentService;
    private final TenancyChargeTermService chargeTermService;
    private final PropertyDocumentAssignmentService assignmentService;
    private final MoveIns moveIns;
    private final MoveInChecks moveInChecks;
    private final SignedScans signedScans;
    private final MoveInFinish moveInFinish;

    public TenanciesScreenController(PropertyService propertyService, LotOffers lotOffers, TenancyRoll tenancyRoll,
                                     TermsTemplateService termsTemplateService, AuditService auditService,
                                     InstrumentService instrumentService,
                                     TenancyChargeTermService chargeTermService,
                                     PropertyDocumentAssignmentService assignmentService,
                                     MoveIns moveIns, MoveInChecks moveInChecks, SignedScans signedScans,
                                     MoveInFinish moveInFinish) {
        this.moveInChecks = moveInChecks;
        this.signedScans = signedScans;
        this.moveInFinish = moveInFinish;
        this.propertyService = propertyService;
        this.lotOffers = lotOffers;
        this.tenancyRoll = tenancyRoll;
        this.termsTemplateService = termsTemplateService;
        this.auditService = auditService;
        this.instrumentService = instrumentService;
        this.chargeTermService = chargeTermService;
        this.assignmentService = assignmentService;
        this.moveIns = moveIns;
    }

    // Every property in the agent's scope with the property's standing (TenancyRoll.ParkLine)
    @PreAuthorize("hasAuthority('tenancy:view')")
    @GetMapping
    public ResponseEntity<TenanciesScreen> everyProperty(Authentication authentication) {
        PropertyScope scope = AgentPrincipal.scopeOf(authentication);
        List<Property> properties = propertyService.findAll(scope);
        Set<UUID> shown = properties.stream().map(Property::uuid).collect(Collectors.toSet());
        boolean mayViewPeople = authorities(authentication).contains("tenants:view");

        return ResponseEntity.ok(new TenanciesScreen(
                properties.stream().map(RatesPark::from).toList(),
                tenancyRoll.parkLines().stream().filter(line -> shown.contains(line.propertyId())).toList(),
                moveIns.inProcess().stream()
                        .filter(moveIn -> shown.contains(moveIn.propertyId()))
                        .map(moveIn -> mayViewPeople ? moveIn : moveIn.withoutNames())
                        .toList(),
                LocalDate.now()));
    }

    // One property's roll. 404 for an unknown property. The page's last edit counts every tenancy on the roll.
    @PreAuthorize("hasAuthority('tenancy:view')")
    @GetMapping("/property/{propertyId}")
    public ResponseEntity<TenanciesPropertyScreen> property(@PathVariable UUID propertyId,
                                                            @RequestParam(required = false) String zone,
                                                            Authentication authentication) {
        Optional<Property> found = propertyService.findByPropertyId(propertyId);
        if (found.isEmpty() || !AgentPrincipal.scopeOf(authentication).allows(propertyId)) {
            return ResponseEntity.notFound().build();
        }
        Set<String> can = authorities(authentication);
        List<TenancyRoll.Line> tenancies = tenancyRoll.tenanciesOf(propertyId);
        if (!can.contains("tenants:view")) {
            tenancies = tenancies.stream().map(TenanciesScreenController::withoutNames).toList();
        }

        History.PageHistory history = null;
        if (can.contains("audit:view")) {
            List<History.Ref> counted = tenancies.stream()
                    .map(tenancy -> new History.Ref("tenancy", tenancy.id()))
                    .toList();
            history = auditService.pageHistory(List.of(), counted, AuditService.zoneOf(zone));
        }

        return ResponseEntity.ok(new TenanciesPropertyScreen(
                RatesPark.from(found.get()),
                lotOffers.lotsOf(propertyId),
                tenancies,
                termsTemplateService.findByProperty(propertyId).stream().map(RatesStandard::from).toList(),
                moveIns.ofProperty(propertyId).stream()
                        .map(moveIn -> can.contains("tenants:view") ? moveIn : moveIn.withoutNames())
                        .toList(),
                LocalDate.now(),
                history));
    }

    // One lot's tenancies, and one of them in full. tenancy picks which; a tenancy of another lot is ignored.
    // 404 for an unknown lot, or a lot whose property is deleted.
    @PreAuthorize("hasAuthority('tenancy:view')")
    @GetMapping("/lot/{lotId}")
    public ResponseEntity<TenanciesLotScreen> lot(@PathVariable UUID lotId,
                                                  @RequestParam(required = false) UUID tenancy,
                                                  @RequestParam(required = false) String zone,
                                                  Authentication authentication) {
        Optional<LotOffers.LotLine> found = lotOffers.lot(lotId);
        if (found.isEmpty() || !AgentPrincipal.scopeOf(authentication).allows(found.get().propertyId())) {
            return ResponseEntity.notFound().build();
        }
        LotOffers.LotLine lot = found.get();
        Optional<Property> property = propertyService.findByPropertyId(lot.propertyId());
        if (property.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Set<String> can = authorities(authentication);
        LocalDate today = LocalDate.now();
        boolean mayViewPeople = can.contains("tenants:view");

        List<TenancyRoll.Line> tenancies = tenancyRoll.tenanciesOfLot(lotId, can.contains("tenancy:delete"));
        if (!mayViewPeople) {
            tenancies = tenancies.stream().map(TenanciesScreenController::withoutNames).toList();
        }

        UUID shown = null;
        if (tenancy != null && tenancies.stream().anyMatch(line -> line.id().equals(tenancy))) {
            shown = tenancy;
        } else {
            // the list is the newest start first, so the first tenancy that has not ended is the current one
            shown = tenancies.stream()
                    .filter(line -> line.deletedAt() == null)
                    .filter(line -> !TenancyStanding.hasEnded(line.endDate(), today))
                    .map(TenancyRoll.Line::id)
                    .findFirst()
                    .orElse(null);
        }

        List<TenancyRoll.Member> members = null;
        if (mayViewPeople) {
            members = shown == null ? List.of() : tenancyRoll.membersOf(lotId, shown);
            if (!can.contains("tenants:delete")) {
                members = members.stream().filter(member -> member.deletedAt() == null).toList();
            }
            if (!can.contains("persons:view")) {
                members = members.stream().map(TenancyRoll.Member::withoutContact).toList();
            }
        }
        List<TenancyRoll.Term> terms = null;
        if (can.contains("tenancy_term:view")) {
            terms = shown == null ? List.of() : tenancyRoll.termsOf(shown);
        }
        List<TermsTemplate> deals = termsTemplateService.findByProperty(lot.propertyId());

        List<Paper> papers = null;
        List<TenancyChargeTermResponse> leaseTerms = can.contains("tenancy_term:view") ? List.of() : null;
        if (can.contains("instrument:view")) {
            List<Instrument> written = shown == null ? List.of() : instrumentService.findByTenancy(shown);
            papers = written.stream().map(Paper::from).toList();
            if (leaseTerms != null) {
                // the list is the newest first, so the first lease that was not abandoned is the tenancy's lease
                leaseTerms = written.stream()
                        .filter(paper -> paper.type() == InstrumentType.LEASE)
                        .filter(paper -> paper.status() != InstrumentStatus.ABANDONED)
                        .findFirst()
                        .map(lease -> chargeTermService.findBySource(lease.uuid()).stream()
                                .map(TenancyChargeTermResponse::from)
                                .toList())
                        .orElse(List.of());
            }
        }
        List<LeaseDocument> leaseDocuments = lot.offers().stream()
                .map(offer -> assignmentService
                        .findForProperty(lot.propertyId(), offer.agreementType(), InstrumentType.LEASE)
                        .map(assignment -> new LeaseDocument(offer.agreementType(), assignment.documentName())))
                .flatMap(Optional::stream)
                .toList();
        List<MoveInLine> lotMoveIns = moveIns.ofLot(lotId).stream()
                .map(line -> mayViewPeople ? line : line.withoutNames())
                .toList();
        UUID shownTenancy = shown;
        boolean movingIn = shown != null && lotMoveIns.stream().anyMatch(line -> line.tenancyId().equals(shownTenancy));
        List<MoveInCheck> checks = movingIn ? moveInChecks.of(shown, moveInFinish.takesDeposit(shown)) : List.of();
        List<SignedScans.SignedScan> scans = null;
        if (can.contains("instrument:view")) {
            scans = movingIn ? signedScans.of(shown) : List.of();
        }

        History.PageHistory history = null;
        if (can.contains("audit:view")) {
            List<History.Ref> bars = new ArrayList<>();
            if (shown != null) {
                bars.add(new History.Ref("tenancy", shown));
            }
            if (papers != null) {
                papers.forEach(paper -> bars.add(new History.Ref("instrument", paper.id())));
            }
            if (leaseTerms != null) {
                leaseTerms.forEach(term -> bars.add(new History.Ref("tenancy_charge_term", term.id())));
            }
            if (members != null) {
                members.forEach(member -> bars.add(new History.Ref(tableOf(member.role()), member.id())));
            }
            checks.stream()
                    .filter(check -> check.id() != null)
                    .forEach(check -> bars.add(new History.Ref("move_in_check", check.id())));
            UUID shownId = shown;
            List<History.Ref> counted = tenancies.stream()
                    .filter(line -> !line.id().equals(shownId))
                    .map(line -> new History.Ref("tenancy", line.id()))
                    .toList();
            history = auditService.pageHistory(bars, counted, AuditService.zoneOf(zone));
        }

        return ResponseEntity.ok(new TenanciesLotScreen(
                lot,
                RatesPark.from(property.get()),
                deals.stream().map(RatesStandard::from).toList(),
                can.contains("terms_template:view") ? deals.stream().map(TermsTemplateResponse::from).toList() : null,
                tenancies,
                shown,
                members,
                terms,
                papers,
                leaseTerms,
                leaseDocuments,
                lotMoveIns,
                checks,
                scans,
                today,
                TenancyService.billingPeriodStart(today),
                history));
    }

    /**
     * People on file by name, for the bar's search and for finding someone before adding a duplicate.
     * q is what the worker typed: every word must be in the name; fewer than two letters find nobody.
     * At most twenty people, by name, each with the homes on properties in the agent's scope.
     * The name is matched without regard to capitals or accents. An agent who may not see every property finds
     * only people something on file ties to a property in the agent's scope, and people nothing on file points
     * at yet (TenancyRoll.peopleNamed). Nobody else's name is answered.
     * Needs tenancy:view and tenants:view. personalPhone and personalEmail are null without persons:view.
     */
    @PreAuthorize("hasAuthority('tenancy:view') and hasAuthority('tenants:view')")
    @GetMapping("/people")
    public ResponseEntity<List<TenancyRoll.Found>> people(@RequestParam(defaultValue = "") String q,
                                                          Authentication authentication) {
        PropertyScope scope = AgentPrincipal.scopeOf(authentication);
        List<TenancyRoll.Found> found = tenancyRoll.peopleNamed(q, scope);
        if (!authorities(authentication).contains("persons:view")) {
            found = found.stream()
                    .map(person -> new TenancyRoll.Found(person.id(), person.nameFull(), null, null, person.homes()))
                    .toList();
        }
        return ResponseEntity.ok(found);
    }

    // The table a stay of each kind is kept in, which the stay's history is kept under
    private static String tableOf(String role) {
        return switch (role) {
            case TenancyRoll.TENANT -> "tenant";
            case TenancyRoll.OCCUPANT -> "occupant";
            default -> "tenancy_interested_party";
        };
    }

    // The same tenancy without the tenants' names, for a viewer who may not see who is on a tenancy
    private static TenancyRoll.Line withoutNames(TenancyRoll.Line line) {
        return new TenancyRoll.Line(line.id(), line.lotId(), line.propertyId(), line.startDate(), line.endDate(),
                line.anniversaryOn(), line.anniversarySource(), line.noPersonalChecks(), line.noPartialPayments(),
                line.acceptPayments(), line.exemptFromLateFees(), line.notes(), line.createdAt(), line.deletedAt(),
                line.agreementType(), line.rent(), line.termStatus(), line.termSource(), line.termValidAt(),
                List.of(), 0);
    }

    // The permissions this request was checked with
    private static Set<String> authorities(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }
}
