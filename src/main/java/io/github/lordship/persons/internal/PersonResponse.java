package io.github.lordship.persons.internal;

import io.github.lordship.persons.Person;
import io.github.lordship.shared.SensitiveDataMasker;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PersonResponse(
        UUID id,
        String nameFull,
        LocalDate birthday,
        String personalPhone,
        String personalEmail,
        String mailingAddress,
        UUID emergencyContactId,
        String social,
        OffsetDateTime createdAt
) {

    public static PersonResponse from(Person person, boolean canViewSsn) {
        return new PersonResponse(
                person.uuid(),
                person.nameFull(),
                person.birthday(),
                person.personalPhone(),
                person.personalEmail(),
                person.mailingAddress(),
                person.emergencyContact(),
                canViewSsn ? person.social() : SensitiveDataMasker.maskSsn(person.social()), // Note: maskSSN can accept null values
                person.createdAt()
        );
    }

}
