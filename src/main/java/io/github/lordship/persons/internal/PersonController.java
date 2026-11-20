package io.github.lordship.persons.internal;

import io.github.lordship.identity.LordshipPrincipal;
import io.github.lordship.persons.Person;
import io.github.lordship.persons.PersonService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/persons")
public class PersonController {

    public record PersonCreateRequest(
            @NotBlank
            String nameFull
    ) { }

    private final PersonService personService;

    public PersonController(PersonService personService) {
        this.personService = personService;
    }

    @PreAuthorize("hasAuthority('persons:create')")
    @PostMapping("/create")
    public ResponseEntity<PersonResponse> createPerson(@Valid @RequestBody PersonCreateRequest createPersonRequest) {
        Person person = personService.createPersonFromName(createPersonRequest.nameFull());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(PersonResponse.from(person, true));
    }

    @PreAuthorize("hasAuthority('persons:view')")
    @GetMapping("/{uuid}")
    public ResponseEntity<PersonResponse> getPerson(@PathVariable UUID uuid, Authentication authentication) {

        boolean canViewSsn = authentication.getAuthorities()
                .stream()
                .anyMatch(a -> a.getAuthority().equals("persons_ssn:view"));

        return personService.findByID(uuid)
                .map(person -> ResponseEntity.ok(PersonResponse.from(person, canViewSsn)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('persons:view_own')")
    @GetMapping("/self")
    public ResponseEntity<PersonResponse> getSelf(Authentication authentication) {
        LordshipPrincipal principal = (LordshipPrincipal) authentication.getPrincipal();
        return personService.findByID(principal.personUuid())
                .map(person -> ResponseEntity.ok(PersonResponse.from(person, true)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * The signed-in person corrects their own details from their own page.
     * Body: any of nameFull, birthday (YYYY-MM-DD), personalPhone, personalEmail, mailingAddress.
     * A missing key is left alone; blank or null clears it (except the name). The social security number and the
     * emergency contact cannot be changed here.
     * 200 with PersonOwnResponse. 400 person.name_blank, person.too_long, person.birthday_invalid,
     * person.email_invalid or person.not_text, each on its field. Audited on the person table.
     */
    @PreAuthorize("hasAuthority('persons:edit_own')")
    @PatchMapping("/self")
    public ResponseEntity<PersonOwnResponse> editSelf(@RequestBody Map<String, Object> request, Authentication authentication) {
        LordshipPrincipal principal = (LordshipPrincipal) authentication.getPrincipal();
        Map<String, Object> changes = new HashMap<>();

        if (request.containsKey("nameFull")) changes.put("name_full", request.get("nameFull"));
        if (request.containsKey("birthday")) changes.put("birthday", request.get("birthday"));
        if (request.containsKey("personalPhone")) changes.put("personal_phone", request.get("personalPhone"));
        if (request.containsKey("personalEmail")) changes.put("personal_email", request.get("personalEmail"));
        if (request.containsKey("mailingAddress")) changes.put("mailing_address", request.get("mailingAddress"));

        return personService.patchOwn(principal.personUuid(), changes)
                .map(person -> ResponseEntity.ok(PersonOwnResponse.from(person)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('persons:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<PersonResponse> editPerson(
            @PathVariable UUID uuid,
            @RequestBody Map<String, Object> request, //Note: @Valid will not work here
            Authentication authentication)
    {
        Map<String, Object> changes = new HashMap<>();

        if (request.containsKey("nameFull")) changes.put("name_full", request.get("nameFull"));
        if (request.containsKey("birthday")) changes.put("birthday", request.get("birthday"));
        if (request.containsKey("personalEmail")) changes.put("personal_email", request.get("personalEmail"));
        if (request.containsKey("personalPhone")) changes.put("personal_phone", request.get("personalPhone"));
        if (request.containsKey("mailingAddress")) changes.put("mailing_address", request.get("mailingAddress"));
        if (request.containsKey("emergencyContactId")) changes.put("emergency_contact", request.get("emergencyContactId"));
        if (request.containsKey("social")) changes.put("social", request.get("social"));

        boolean canViewSsn = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("persons_ssn:view"));

        return personService.patchPerson(uuid, changes)
                .map(person -> ResponseEntity.ok(PersonResponse.from(person, canViewSsn)))
                .orElse(ResponseEntity.notFound().build());
    }

    // A soft delete. 409 person.is_agent while the person signs in as an agent, 409 person.in_use while the person
    // is on a tenancy or has a claim on a home.
    // Without persons:delete, the agent who added the person may still delete the person as own recent work
    // (audit OwnWork): 403 when the person is not the agent's to take back, 409 own_work.* when the days have
    // passed or someone else has touched the person since.
    @PreAuthorize("hasAuthority('persons:delete') or @ownWork.madeRecently('person', #uuid, authentication)")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> deletePerson(@PathVariable UUID uuid) {
        return personService.deletePerson(uuid) ?
                ResponseEntity.noContent().build() :
                ResponseEntity.notFound().build();
    }

    // Brings a deleted person back. A person who is not deleted is left as the person is, so sending the restore
    // twice is safe. 404 for an unknown person. The same permission check as the delete.
    // Answers the person to a caller who holds persons:view, and 204 with no body to a caller who does not:
    // the restore answers a live person too, so without this a caller could read any person through the restore.
    @PreAuthorize("hasAuthority('persons:delete') or @ownWork.madeRecently('person', #uuid, authentication)")
    @PostMapping("/{uuid}/restore")
    public ResponseEntity<PersonResponse> restorePerson(@PathVariable UUID uuid, Authentication authentication) {
        boolean canView = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("persons:view"));
        boolean canViewSsn = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("persons_ssn:view"));

        return personService.restorePerson(uuid)
                .map(person -> canView
                        ? ResponseEntity.ok(PersonResponse.from(person, canViewSsn))
                        : ResponseEntity.noContent().<PersonResponse>build())
                .orElse(ResponseEntity.notFound().build());
    }
}