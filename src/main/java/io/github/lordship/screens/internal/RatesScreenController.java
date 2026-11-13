package io.github.lordship.screens.internal;

import io.github.lordship.audit.AuditService;
import io.github.lordship.audit.History;
import io.github.lordship.identity.AgentPrincipal;
import io.github.lordship.lots.LotOffers;
import io.github.lordship.properties.Property;
import io.github.lordship.properties.PropertyService;
import io.github.lordship.screens.internal.ScreenResponses.RatesLotScreen;
import io.github.lordship.screens.internal.ScreenResponses.RatesPark;
import io.github.lordship.screens.internal.ScreenResponses.RatesPropertyScreen;
import io.github.lordship.screens.internal.ScreenResponses.RatesScreen;
import io.github.lordship.screens.internal.ScreenResponses.RatesStandard;
import io.github.lordship.shared.PropertyScope;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One call per screen of Glade's Rates and deals concern: every property, one property, one lot.
 * The screens answer "what do we offer, where, at what rate, and is any of it open?".
 *
 * <p>Every screen needs lots:view. A property's deals (its terms templates) also need terms_template:view;
 * without that permission deals is null, and the screen still gets each deal's two standard rates (standards),
 * because a lot with no rate of the lot's own shows the standard rate.
 * history is null without audit:view. zone is the viewer's time zone, like America/Los_Angeles.
 * A property outside the agent's scope (see PropertyScope) is treated as if the property did not exist: 404.
 */
@RestController
@RequestMapping("/api/screens/rates")
public class RatesScreenController {

    private final PropertyService propertyService;
    private final LotOffers lotOffers;
    private final TermsTemplateService termsTemplateService;
    private final AuditService auditService;

    public RatesScreenController(PropertyService propertyService, LotOffers lotOffers,
                                 TermsTemplateService termsTemplateService, AuditService auditService) {
        this.propertyService = propertyService;
        this.lotOffers = lotOffers;
        this.termsTemplateService = termsTemplateService;
        this.auditService = auditService;
    }

    // Every property in the agent's scope, and for each kind of agreement a property has a deal for or a lot offers:
    // how many lots offer the kind, how many of those are open or taken, and the rates (LotOffers.ParkLine).
    // This is the page for the phone call "do you have any vacancies in CD?".
    @PreAuthorize("hasAuthority('lots:view')")
    @GetMapping
    public ResponseEntity<RatesScreen> everyProperty(Authentication authentication) {
        PropertyScope scope = AgentPrincipal.scopeOf(authentication);
        List<Property> properties = propertyService.findAll(scope);
        Set<UUID> shown = properties.stream().map(Property::uuid).collect(Collectors.toSet());

        return ResponseEntity.ok(new RatesScreen(
                properties.stream().map(RatesPark::from).toList(),
                lotOffers.parkLines().stream().filter(line -> shown.contains(line.propertyId())).toList()));
    }

    // One property: its lots with what each offers and whether each is taken, its deals, and its history.
    // 404 for an unknown property. The history's bars are the deals' own (with terms_template:view).
    // The page's last edit also counts every rate and offer on the property's lots.
    @PreAuthorize("hasAuthority('lots:view')")
    @GetMapping("/property/{propertyId}")
    public ResponseEntity<RatesPropertyScreen> property(@PathVariable UUID propertyId,
                                                        @RequestParam(required = false) String zone,
                                                        Authentication authentication) {
        Optional<Property> found = propertyService.findByPropertyId(propertyId);
        if (found.isEmpty() || !AgentPrincipal.scopeOf(authentication).allows(propertyId)) {
            return ResponseEntity.notFound().build();
        }
        Set<String> can = authorities(authentication);
        List<LotOffers.LotLine> lots = lotOffers.lotsOf(propertyId);
        List<TermsTemplate> deals = termsTemplateService.findByProperty(propertyId);
        boolean mayViewDeals = can.contains("terms_template:view");

        History.PageHistory history = null;
        if (can.contains("audit:view")) {
            List<History.Ref> shown = new ArrayList<>();
            if (mayViewDeals) {
                deals.forEach(deal -> shown.add(new History.Ref("terms_template", deal.uuid())));
            }
            List<History.Ref> counted = new ArrayList<>();
            lots.forEach(lot -> lot.offers().forEach(
                    offer -> counted.add(new History.Ref("lot_permissible_agreement_type", offer.id()))));
            history = auditService.pageHistory(shown, counted, AuditService.zoneOf(zone));
        }

        return ResponseEntity.ok(new RatesPropertyScreen(
                RatesPark.from(found.get()),
                lots,
                deals.stream().map(RatesStandard::from).toList(),
                mayViewDeals ? deals.stream().map(TermsTemplateResponse::from).toList() : null,
                history));
    }

    // One lot: what the lot offers and whether the lot is taken, the lot's property (for the header), the
    // property's deals, and the history of each rate. 404 for an unknown lot, or a lot whose property is deleted.
    // Each offer has its own bars (table lot_permissible_agreement_type, by the offer's id).
    @PreAuthorize("hasAuthority('lots:view')")
    @GetMapping("/lot/{lotId}")
    public ResponseEntity<RatesLotScreen> lot(@PathVariable UUID lotId,
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
        List<TermsTemplate> deals = termsTemplateService.findByProperty(lot.propertyId());

        History.PageHistory history = null;
        if (can.contains("audit:view")) {
            List<History.Ref> shown = lot.offers().stream()
                    .map(offer -> new History.Ref("lot_permissible_agreement_type", offer.id()))
                    .toList();
            history = auditService.pageHistory(shown, List.of(), AuditService.zoneOf(zone));
        }

        return ResponseEntity.ok(new RatesLotScreen(
                lot,
                RatesPark.from(property.get()),
                deals.stream().map(RatesStandard::from).toList(),
                can.contains("terms_template:view") ? deals.stream().map(TermsTemplateResponse::from).toList() : null,
                history));
    }

    // The permissions this request was checked with
    private static Set<String> authorities(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }
}
