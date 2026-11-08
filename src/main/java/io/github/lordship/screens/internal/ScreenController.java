package io.github.lordship.screens.internal;

import io.github.lordship.access.Agent;
import io.github.lordship.access.AgentService;
import io.github.lordship.access.internal.agents.AgentOwnResponse;
import io.github.lordship.audit.AuditService;
import io.github.lordship.audit.History;
import io.github.lordship.homes.Home;
import io.github.lordship.homes.HomeService;
import io.github.lordship.homes.internal.HomeResponse;
import io.github.lordship.identity.AgentPrincipal;
import io.github.lordship.lots.Lot;
import io.github.lordship.lots.LotService;
import io.github.lordship.lots.internal.LotResponse;
import io.github.lordship.persons.PersonService;
import io.github.lordship.persons.internal.PersonOwnResponse;
import io.github.lordship.properties.Property;
import io.github.lordship.properties.PropertyService;
import io.github.lordship.properties.internal.PropertyResponse;
import io.github.lordship.screens.internal.ScreenResponses.LotScreen;
import io.github.lordship.screens.internal.ScreenResponses.PropertyScreen;
import io.github.lordship.screens.internal.ScreenResponses.SelfScreen;
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
 * One call per Glade screen. Each answer holds everything the screen shows except the map layers,
 * which have their own calls because they are large and rarely change (see SiteMapController).
 * Records come in the same shapes as their own endpoints, so a screen can save through those.
 * Each part needs the same permission its own endpoint needs. Without it, that part is null.
 *
 * zone is the viewer's time zone, like America/Los_Angeles. The history's days follow it.
 * A property outside the agent's scope (see PropertyScope) is treated as if it did not exist: 404.
 */
@RestController
@RequestMapping("/api/screens")
public class ScreenController {

    private final PropertyService propertyService;
    private final LotService lotService;
    private final HomeService homeService;
    private final AuditService auditService;
    private final AgentService agentService;
    private final PersonService personService;

    public ScreenController(PropertyService propertyService, LotService lotService,
                            HomeService homeService, AuditService auditService,
                            AgentService agentService, PersonService personService) {
        this.propertyService = propertyService;
        this.lotService = lotService;
        this.homeService = homeService;
        this.auditService = auditService;
        this.agentService = agentService;
        this.personService = personService;
    }

    // The property page: the property, its lots, and its history. 404 for an unknown property.
    // The history's bars are the property's own. Its last edit also counts the lots and their homes,
    // each only with the permission to view them.
    @PreAuthorize("hasAuthority('properties:view')")
    @GetMapping("/property/{propertyId}")
    public ResponseEntity<PropertyScreen> property(@PathVariable UUID propertyId,
                                                   @RequestParam(required = false) String zone,
                                                   Authentication authentication) {
        Optional<Property> found = propertyService.findByPropertyId(propertyId);
        if (found.isEmpty() || !AgentPrincipal.scopeOf(authentication).allows(propertyId)) {
            return ResponseEntity.notFound().build();
        }
        Property property = found.get();
        Set<String> can = authorities(authentication);
        String code = property.propertyCode();

        List<Lot> lots = null;
        if (can.contains("lots:view")) {
            lots = code == null ? List.of() : lotService.findByProperty(code);
        }

        History.PageHistory history = null;
        if (can.contains("audit:view")) {
            List<History.Ref> counted = new ArrayList<>();
            if (lots != null) {
                lots.forEach(lot -> counted.add(new History.Ref("lot", lot.uuid())));
            }
            if (code != null && can.contains("homes:view")) {
                homeService.findByProperty(code).forEach(home -> counted.add(new History.Ref("mobile_home", home.uuid())));
            }
            history = auditService.pageHistory(
                    List.of(new History.Ref("property", propertyId)), counted, AuditService.zoneOf(zone));
        }

        return ResponseEntity.ok(new PropertyScreen(
                PropertyResponse.from(property),
                lots == null ? null : lots.stream().map(LotResponse::from).toList(),
                history));
    }

    // The lot page: the lot, its property (for the header), the homes on it, and its history.
    // 404 for an unknown lot. The lot and each home have their own bars.
    // property is null without properties:view, like homes without homes:view.
    @PreAuthorize("hasAuthority('lots:view')")
    @GetMapping("/lot/{lotId}")
    public ResponseEntity<LotScreen> lot(@PathVariable UUID lotId,
                                         @RequestParam(required = false) String zone,
                                         Authentication authentication) {
        Optional<Lot> found = lotService.findById(lotId);
        if (found.isEmpty() || !AgentPrincipal.scopeOf(authentication).allows(found.get().propertyId())) {
            return ResponseEntity.notFound().build();
        }
        Lot lot = found.get();
        Set<String> can = authorities(authentication);

        Property property = can.contains("properties:view")
                ? propertyService.findByPropertyId(lot.propertyId()).orElse(null)
                : null;
        List<Home> homes = can.contains("homes:view") ? homeService.findByLot(lotId) : null;

        History.PageHistory history = null;
        if (can.contains("audit:view")) {
            List<History.Ref> shown = new ArrayList<>();
            shown.add(new History.Ref("lot", lotId));
            if (homes != null) {
                homes.forEach(home -> shown.add(new History.Ref("mobile_home", home.uuid())));
            }
            history = auditService.pageHistory(shown, List.of(), AuditService.zoneOf(zone));
        }

        return ResponseEntity.ok(new LotScreen(
                LotResponse.from(lot),
                property == null ? null : PropertyResponse.from(property),
                homes == null ? null : homes.stream().map(HomeResponse::from).toList(),
                history));
    }

    // The signed-in agent's own page: their work details, their personal details, and the history of both.
    // Any signed-in agent may open it; each part needs its _own permission, otherwise it is null.
    // 404 when the caller is not an agent (a tenant), or the agent is gone.
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/self")
    public ResponseEntity<SelfScreen> self(@RequestParam(required = false) String zone, Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof AgentPrincipal principal)) {
            return ResponseEntity.notFound().build();
        }
        Optional<Agent> found = agentService.findById(principal.agentUuid());
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Agent agent = found.get();
        Set<String> can = authorities(authentication);

        AgentOwnResponse agentPart = can.contains("agents:view_own") ? AgentOwnResponse.from(agent) : null;
        PersonOwnResponse personPart = can.contains("persons:view_own")
                ? personService.findByID(agent.personId()).map(PersonOwnResponse::from).orElse(null)
                : null;

        History.PageHistory history = null;
        if (can.contains("audit:view_own") || can.contains("audit:view")) {
            history = auditService.pageHistory(
                    List.of(new History.Ref("person", agent.personId()), new History.Ref("agent", agent.uuid())),
                    List.of(), AuditService.zoneOf(zone));
        }

        return ResponseEntity.ok(new SelfScreen(agent.uuid(), agent.personId(), agentPart, personPart, history));
    }

    // The permissions this request was checked with
    private static Set<String> authorities(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }
}
