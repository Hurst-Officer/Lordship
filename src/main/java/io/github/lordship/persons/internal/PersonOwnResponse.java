package io.github.lordship.persons.internal;

import io.github.lordship.persons.Person;
import org.springframework.modulith.NamedInterface;

import java.time.LocalDate;
import java.util.UUID;

// A person's details as they see them on their own page (GET /api/screens/self, PATCH /api/persons/self).
// The social security number and the emergency contact are left out on purpose: the office keeps those.
// Shared with the screens module, so the self screen sends the person in this same shape
@NamedInterface("responses")
public record PersonOwnResponse(
        UUID id,
        String nameFull,
        LocalDate birthday,
        String personalPhone,
        String personalEmail,
        String mailingAddress
) {
    public static PersonOwnResponse from(Person person) {
        return new PersonOwnResponse(
                person.uuid(),
                person.nameFull(),
                person.birthday(),
                person.personalPhone(),
                person.personalEmail(),
                person.mailingAddress()
        );
    }
}
