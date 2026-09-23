package io.github.lordship.persons;

import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.persons.internal.PersonRepository;
import io.github.lordship.persons.internal.PersonRow;
import io.github.lordship.shared.EncryptionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class PersonService {

    // NOTE: spring injects this repository
    private final PersonRepository personRepository;
    private final EncryptionService encryptionService;
    private final AuditService auditService;

    public PersonService(PersonRepository personRepository,
                         EncryptionService encryptionService,
                         AuditService auditService) {
        this.personRepository = personRepository;
        this.encryptionService = encryptionService;
        this.auditService = auditService;
    }

    // Note on design: http request records should not come into the service layer
    @Transactional
    public Person createPersonFromName(String nameFull) {
        PersonRow row = personRepository.save(nameFull);
        auditService.recordInsert("person", row.uuid(), AuditMapper.toMap(row));
        return row.toPerson(encryptionService);
    }

    @Transactional
    public Person systemInsertRootPerson(String nameFull, UUID correlationId) {
        PersonRow row = personRepository.save(nameFull);
        auditService.recordSystemInsert(correlationId, "person", row.uuid(), AuditMapper.toMap(row));
        return row.toPerson(encryptionService);
    }

    public Optional<Person> findByID(UUID uuid) {
        return personRepository.findById(uuid)
                .map(row -> row.toPerson(encryptionService));
    }

    public Optional<Person> findByEmail(String email){
        return personRepository.findByEmail(email)
                .map(row -> row.toPerson(encryptionService));
    }

    @Transactional //TODO: REMEMBER: sensitive fields may need same encryption logic as SSN
    public Optional<Person> patchPerson(UUID uuid, Map<String, Object> changes) {

        Optional<PersonRow> personBeforeOpt = personRepository.findById(uuid);
        if (personBeforeOpt.isEmpty()) {
            return Optional.empty();
        }

        PersonRow personBefore = personBeforeOpt.get();
        // note: this requires special handling to avoid creating unnecessary logs
        if (changes.containsKey("social")) {
            String social = (String) changes.get("social");
            if (social != null && !social.isBlank()) {
                String currentSocial = personBefore.social() != null
                        ? encryptionService.decrypt(personBefore.social())
                        : null;
                if (social.equals(currentSocial)) {
                    changes.remove("social"); // unchanged plaintext — skip write and log
                } else {
                    changes.put("social", encryptionService.encrypt(social));
                }
            } else {
                changes.put("social", null);
            }
        }

        if (changes.containsKey("birthday")) {
            Object birthday = changes.get("birthday");
            if (birthday instanceof String s && !s.isBlank()) {
                changes.put("birthday", LocalDate.parse(s));
            } else {
                changes.put("birthday", null);
            }
        }

        if (changes.containsKey("emergency_contact")) {
            Object ec = changes.get("emergency_contact");
            if (ec instanceof String s && !s.isBlank()) {
                changes.put("emergency_contact", UUID.fromString(s));
            } else {
                changes.put("emergency_contact", null);
            }
        }

        Optional<PersonRow> personAfterOpt = personRepository.patch(uuid, changes);
        if (personAfterOpt.isEmpty()) {
            return Optional.empty();
        }
        PersonRow personAfter = personAfterOpt.get();

        var diff = AuditMapper.diff(personBefore, personAfter);
        if (!diff.before().isEmpty()) {
            auditService.recordUpdate("person", uuid, diff.before(), diff.after());
        }
        return Optional.of(personAfter.toPerson(encryptionService));
    }

    @Transactional
    public boolean deletePerson(UUID uuid) {
        return personRepository.findById(uuid).map(person -> {
            if (!personRepository.softDelete(uuid)) {
                return false;
            }
            auditService.recordDelete("person", uuid, AuditMapper.toMap(person));
            return true;
        }).orElse(false);
    }
}