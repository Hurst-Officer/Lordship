package io.github.lordship;

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

    private TestDataSupport(PropertyRepository propertyRepository,
                            LotRepository lotRepository,
                            TenancyRepository tenancyRepository,
                            AccountRepository accountRepository,
                            MeterRepository meterRepository,
                            PersonRepository personRepository,
                            TenantRepository tenantRepository,
                            InterestedPartyRepository interestedPartyRepository) {
        this.propertyRepository = propertyRepository;
        this.lotRepository = lotRepository;
        this.tenancyRepository = tenancyRepository;
        this.accountRepository = accountRepository;
        this.meterRepository = meterRepository;
        this.personRepository = personRepository;
        this.tenantRepository = tenantRepository;
        this.interestedPartyRepository = interestedPartyRepository;
    }

    public PropertyRow insertProperty(String propertyName, String propertyAddress, String propertyCode) {
        return propertyRepository.save(propertyName, propertyAddress, propertyCode);
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

    public TenancyRow insertChainToTenancy(){
        PropertyRow pr = insertProperty("TP");
        LotRow lr = insertLot(pr.uuid(), "1");
        TenancyRow tr = tenancyRepository.save(lr.uuid());
        accountRepository.save(new AccountRow(tr.uuid(), null));
        return tr;
    }

    public MeterRow insertMeter(UUID lotId) {
        return meterRepository.createDefault(lotId);
    }

    public MeterRow insertChainToMeters() {
        PropertyRow pr = insertProperty("TP");
        LotRow lr = insertLot(pr.uuid(), "1");
        return meterRepository.createDefault(lr.uuid());
    }

    public InterestedParty insertInterestedParty(UUID tenancyId, UUID personId, LocalDate startDate){
        return interestedPartyRepository.save(tenancyId, personId, startDate).toInterestedParty();
    }

    // Repositories, not services: a service call pulls in the audit write, which
    // has no principal to attribute to outside an authenticated request.
    public PersonRow insertPerson(String nameFull) {
        return personRepository.save(nameFull);
    }

    public TenantRow insertTenant(UUID tenancyId, UUID personId, LocalDate startDate) {
        return tenantRepository.save(tenancyId, personId, startDate);
    }
}