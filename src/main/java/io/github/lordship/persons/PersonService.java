package io.github.lordship.persons;

import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.persons.internal.PersonRepository;
import io.github.lordship.persons.internal.PersonRow;
import io.github.lordship.shared.EncryptionService;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.RuleConflict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
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

    // What a person may change on their own page: column -> its name in the request, and the longest it may be
    // (person in V2). The birthday is checked on its own. No social security number, no emergency contact.
    private static final Map<String, String> OWN_TEXT_FIELDS = Map.of(
            "name_full", "nameFull",
            "personal_phone", "personalPhone",
            "personal_email", "personalEmail",
            "mailing_address", "mailingAddress");
    private static final Map<String, Integer> OWN_TEXT_MAX = Map.of(
            "name_full", 120,
            "personal_phone", 120,
            "personal_email", 120,
            "mailing_address", 1000);

    /**
     * A person corrects their own details. changes is keyed by column; only the columns in OWN_TEXT_FIELDS
     * and birthday are taken. Text is stripped and blank text clears the value, except the name, which must
     * stay. Empty for an unknown or removed person.
     * Throws InvalidRequest (400) on the field at fault: person.name_blank, person.too_long, person.not_text,
     * person.email_invalid (no @), person.birthday_invalid (not a date, or not in the past).
     */
    @Transactional
    public Optional<Person> patchOwn(UUID uuid, Map<String, Object> changes) {
        Map<String, Object> clean = new HashMap<>();
        for (Map.Entry<String, String> field : OWN_TEXT_FIELDS.entrySet()) {
            String column = field.getKey();
            if (!changes.containsKey(column)) continue;
            Object value = changes.get(column);
            if (value != null && !(value instanceof String)) {
                throw InvalidRequest.onField(field.getValue(), "person.not_text");
            }
            String text = value == null ? null : ((String) value).strip();
            if (text != null && text.isEmpty()) text = null;
            if (text == null && column.equals("name_full")) {
                throw InvalidRequest.onField(field.getValue(), "person.name_blank");
            }
            if (text != null && text.length() > OWN_TEXT_MAX.get(column)) {
                throw InvalidRequest.onField(field.getValue(), "person.too_long", OWN_TEXT_MAX.get(column));
            }
            if (text != null && column.equals("personal_email") && !text.contains("@")) {
                throw InvalidRequest.onField(field.getValue(), "person.email_invalid");
            }
            clean.put(column, text);
        }

        if (changes.containsKey("birthday")) {
            Object value = changes.get("birthday");
            if (value != null && !(value instanceof String)) {
                throw InvalidRequest.onField("birthday", "person.birthday_invalid");
            }
            String text = value == null ? "" : ((String) value).strip();
            if (!text.isEmpty()) {
                try {
                    if (!LocalDate.parse(text).isBefore(LocalDate.now())) {
                        throw InvalidRequest.onField("birthday", "person.birthday_invalid");
                    }
                } catch (DateTimeParseException e) {
                    throw InvalidRequest.onField("birthday", "person.birthday_invalid");
                }
            }
            // patchPerson turns the text into a date, and blank into null
            clean.put("birthday", text);
        }

        return patchPerson(uuid, clean);
    }

    @Transactional
    public boolean deletePerson(UUID uuid) {
        return personRepository.findById(uuid).map(person -> {
            // Sign-in and session refresh read the agent's person, so deleting the person of a live agent would lock
            // the agent out while the last-admin and last-key guards still counted the agent. The agent is removed
            // first (DELETE /api/agents/{id}), which runs those guards.
            if (personRepository.isLiveAgent(uuid)) {
                throw RuleConflict.of("person.is_agent");
            }
            if (!personRepository.softDelete(uuid)) {
                return false;
            }
            auditService.recordDelete("person", uuid, AuditMapper.toMap(person));
            return true;
        }).orElse(false);
    }
}