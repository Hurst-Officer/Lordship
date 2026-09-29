package io.github.lordship;

import io.github.lordship.access.internal.agents.AgentRepository;
import io.github.lordship.access.internal.agents.AgentRow;
import io.github.lordship.accounts.internal.AccountRepository;
import io.github.lordship.accounts.internal.AccountRow;
import io.github.lordship.lots.internal.LotRepository;
import io.github.lordship.lots.internal.LotRow;
import io.github.lordship.meters.internal.MeterRepository;
import io.github.lordship.meters.internal.MeterRow;
import io.github.lordship.persons.internal.PersonRepository;
import io.github.lordship.persons.internal.PersonRow;
import io.github.lordship.properties.internal.PropertyRepository;
import io.github.lordship.properties.internal.PropertyRow;
import io.github.lordship.tenancy.internal.TenancyRepository;
import io.github.lordship.tenancy.internal.TenancyRow;
import io.github.lordship.tenants.InterestedParty;
import io.github.lordship.tenants.internal.InterestedPartyRepository;
import io.github.lordship.tenants.internal.TenantRepository;
import io.github.lordship.tenants.internal.TenantRow;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDate;
import java.util.UUID;


public final class TestDataSupport {
    private final PropertyRepository propertyRepository;
    private final LotRepository lotRepository;
    private final TenancyRepository tenancyRepository;
    private final AccountRepository accountRepository;
    private final MeterRepository meterRepository;
    private final PersonRepository personRepository;
    private final TenantRepository tenantRepository;
    private final InterestedPartyRepository interestedPartyRepository;
    private final AgentRepository agentRepository;
    private final String rootEmail;

    private TestDataSupport(PropertyRepository propertyRepository,
                            LotRepository lotRepository,
                            TenancyRepository tenancyRepository,
                            AccountRepository accountRepository,
                            MeterRepository meterRepository,
                            PersonRepository personRepository,
                            TenantRepository tenantRepository,
                            InterestedPartyRepository interestedPartyRepository,
                            AgentRepository agentRepository,
                            @Value("${lordship.root.email}") String rootEmail) {
        this.propertyRepository = propertyRepository;
        this.lotRepository = lotRepository;
        this.tenancyRepository = tenancyRepository;
        this.accountRepository = accountRepository;
        this.meterRepository = meterRepository;
        this.personRepository = personRepository;
        this.tenantRepository = tenantRepository;
        this.interestedPartyRepository = interestedPartyRepository;
        this.agentRepository = agentRepository;
        this.rootEmail = rootEmail;
    }

    public PropertyRow insertProperty(String propertyName, String propertyStreet, String propertyCode) {
        return propertyRepository.save(propertyName, propertyStreet, "Testville", "WA", "98000", propertyCode);
    }

    public PropertyRow insertProperty(String propertyCode) {
        return insertProperty("Test Mobile Park", "123 Test Ave", propertyCode);
    }

    public LotRow insertLot(UUID propertyId, String lotNumber) {
        return lotRepository.save(propertyId, lotNumber);
    }

    public TenancyRow insertTenancy(UUID lotId) {
        TenancyRow tr = tenancyRepository.save(lotId);
        accountRepository.save(new AccountRow(tr.uuid(), null));
        return tr;
    }

    // One property ("TP") with one lot ("1"). A test that needs a second property must give
    // it its own code: property codes are unique among live properties.
    public LotRow insertChainToLot() {
        PropertyRow pr = insertProperty("TP");
        return insertLot(pr.uuid(), "1");
    }

    public TenancyRow insertChainToTenancy(){
        return insertTenancy(insertChainToLot().uuid());
    }

    public MeterRow insertMeter(UUID lotId) {
        return meterRepository.createDefault(lotId);
    }

    public MeterRow insertChainToMeters() {
        return insertMeter(insertChainToLot().uuid());
    }

    public InterestedParty insertInterestedParty(UUID tenancyId, UUID personId, LocalDate startDate){
        return interestedPartyRepository.save(tenancyId, personId, startDate).toInterestedParty();
    }

    // Repositories, not services: a service call pulls in the audit write, which
    // has no principal to attribute to outside an authenticated request.
    public PersonRow insertPerson(String nameFull) {
        return personRepository.save(nameFull);
    }

    // The root agent is seeded by the app itself (lordship.root.email), not by a test.
    public AgentRow findRootAgent() {
        return agentRepository.findByWorkEmail(rootEmail)
                .orElseThrow(() -> new IllegalStateException("No root agent found for " + rootEmail));
    }

    // A new agent (and the person behind it) with a unique work email, so a test can
    // create as many as it needs.
    public AgentRow insertAgent() {
        PersonRow person = personRepository.save("Test Agent");
        return agentRepository.save(new AgentRow(person.uuid(), "",
                "agent-" + UUID.randomUUID() + "@lordship.test", "supergoodNicePass123123,"));
    }

    public TenantRow insertTenant(UUID tenancyId, UUID personId, LocalDate startDate) {
        return tenantRepository.save(tenancyId, personId, startDate);
    }
}