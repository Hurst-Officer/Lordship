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
        if (request.containsKey("emergencyContact")) changes.put("emergency_contact", request.get("emergencyContact"));
        if (request.containsKey("social")) changes.put("social", request.get("social"));

        boolean canViewSsn = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("persons_ssn:view"));

        return personService.patchPerson(uuid, changes)
                .map(person -> ResponseEntity.ok(PersonResponse.from(person, canViewSsn)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('persons:delete')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> deletePerson(@PathVariable UUID uuid) {
        return personService.deletePerson(uuid) ?
                ResponseEntity.noContent().build() :
                ResponseEntity.notFound().build();
    }
}