package io.github.lordship.screens.internal;

import io.github.lordship.access.Role;
import io.github.lordship.access.RoleDirectory;
import io.github.lordship.access.RoleService;
import io.github.lordship.access.internal.agents.AgentOwnResponse;
import io.github.lordship.audit.History;
import io.github.lordship.homes.internal.HomeResponse;
import io.github.lordship.lots.LotOffers;
import io.github.lordship.lots.internal.LotResponse;
import io.github.lordship.persons.internal.PersonOwnResponse;
import io.github.lordship.properties.Property;
import io.github.lordship.properties.internal.PropertyResponse;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.termstemplate.TermsTemplate;
import io.github.lordship.termstemplate.internal.TermsTemplateResponse;

import java.util.List;
import java.util.UUID;

// What GET /api/screens/... returns: everything one Glade screen shows, except the map layers.
public final class ScreenResponses {

    private ScreenResponses() { }

    // The property page (X2).
    // lots is null without lots:view. history is null without audit:view.
    public record PropertyScreen(
            PropertyResponse property,
            List<LotResponse> lots,
            History.PageHistory history
    ) { }

    // ---- Roles ----

    // A role, in the same shape as GET /api/roles/{id}, so the role page can save through that endpoint.
    // builtIn: the server finds this role by name, so it keeps its name and cannot be deleted.
    public record RoleRecord(UUID id, String roleName, String roleDescription, boolean builtIn) {
        static RoleRecord from(Role role) {
            return new RoleRecord(role.uuid(), role.roleName(), role.roleDescription(), RoleService.isBuiltIn(role.roleName()));
        }
    }

    // The list of roles (X1): every live role, and the people who hold none
    public record RolesScreen(
            List<RoleDirectory.RoleLine> roles,
            List<RoleDirectory.PersonLine> noRole
    ) { }

    // The role page (X2). permissions is null without role_permissions:view. history is null without audit:view.
    public record RoleScreen(
            RoleRecord role,
            List<RoleDirectory.PermissionMark> permissions,
            List<RoleDirectory.PersonLine> holders,
            History.PageHistory history
    ) { }

    // The person page (X3). permissions is null without role_permissions:view; properties null without
    // assignments:view. allProperties: their roles give them every property. history is null without audit:view.
    // denied: what their roles give but has been taken away from them (POST /api/denied-permissions/deny),
    // in the same shape as permissions; null without role_permissions:view.
    public record PersonScreen(
            RoleDirectory.PersonLine person,
            List<RoleDirectory.HeldRole> roles,
            List<RoleDirectory.EffectivePermission> permissions,
            List<RoleDirectory.EffectivePermission> denied,
            boolean allProperties,
            List<RoleDirectory.AssignedProperty> properties,
            History.PageHistory history
    ) { }

    // ---- Rates and deals ----

    // A property as the Rates and deals screens name the property: enough for a header, a row of the list,
    // and the property's place on the map of every property.
    // titleLink is the link the office set for the property's name (in Properties), or null: the name at the
    // top of the page opens the link.
    public record RatesPark(UUID id, String propertyCode, String propertyName, String propertyStreet,
                            String propertyCity, String propertyState, String propertyZip,
                            Double latitude, Double longitude, String titleLink) {
        static RatesPark from(Property property) {
            return new RatesPark(property.uuid(), property.propertyCode(), property.propertyName(),
                    property.propertyStreet(), property.propertyCity(), property.propertyState(),
                    property.propertyZip(), property.latitude(), property.longitude(), property.titleLink());
        }
    }

    // The two standard rates of one of a property's deals, as text ("650.00") or null when the deal has none.
    // A lot with no rate of the lot's own for the kind of agreement uses these. Sent to anyone who may see lots,
    // because a lot's rate cannot be shown without them; the rest of a deal needs terms_template:view.
    public record RatesStandard(AgreementType agreementType, String targetRate, String askingRate) {
        static RatesStandard from(TermsTemplate deal) {
            return new RatesStandard(deal.agreementType(),
                    deal.targetRate() == null ? null : deal.targetRate().toPlainString(),
                    deal.askingRate() == null ? null : deal.askingRate().toPlainString());
        }
    }

    // The list of every property (X1): each property in the agent's scope, and one line for each kind of
    // agreement a property has a deal for or one of the property's lots offers
    public record RatesScreen(
            List<RatesPark> properties,
            List<LotOffers.ParkLine> lines
    ) { }

    // One property (X2). standards has one entry for each deal. deals is null without terms_template:view,
    // in the same shape as GET /api/terms-templates/{id}, so the page can save through that endpoint.
    // history is null without audit:view.
    public record RatesPropertyScreen(
            RatesPark property,
            List<LotOffers.LotLine> lots,
            List<RatesStandard> standards,
            List<TermsTemplateResponse> deals,
            History.PageHistory history
    ) { }

    // One lot (X3). property is the lot's property, for the page's header.
    // deals is null without terms_template:view. history is null without audit:view.
    public record RatesLotScreen(
            LotOffers.LotLine lot,
            RatesPark property,
            List<RatesStandard> standards,
            List<TermsTemplateResponse> deals,
            History.PageHistory history
    ) { }

    // The lot page (X3).
    // property is the lot's property, for the page's header. homes is null without homes:view.
    // history is null without audit:view.
    public record LotScreen(
            LotResponse lot,
            PropertyResponse property,
            List<HomeResponse> homes,
            History.PageHistory history
    ) { }

    // The signed-in agent's own page. agentId and personId are always there, for presence and the history bars.
    // agent is null without agents:view_own, person without persons:view_own,
    // history without audit:view_own (or audit:view).
    public record SelfScreen(
            java.util.UUID agentId,
            java.util.UUID personId,
            AgentOwnResponse agent,
            PersonOwnResponse person,
            History.PageHistory history
    ) { }
}
