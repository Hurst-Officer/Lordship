package io.github.lordship.tenancy;

import io.github.lordship.shared.AgreementType;
import io.github.lordship.tenancy.internal.TenancyRollRepository;
import io.github.lordship.tenancy.internal.TenancyRollRepository.HomeRow;
import io.github.lordship.tenancy.internal.TenancyRollRepository.LineRow;
import io.github.lordship.tenancy.internal.TenancyRollRepository.MemberRow;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What Glade's Tenancies screens show: each property's standing, a property's roll of tenancies, and one lot's
 * tenancies with the people on each. Read-only. Changes go through TenancyService and the tenants module.
 *
 * <p>A tenancy has not ended while the tenancy has no end date, or an end date of today or later.
 * A tenancy has "no lease on file" while no ACTIVE charge term of the tenancy is in force today: the count the
 * office works down before a property can be billed with every charge backed by paper.
 *
 * <p>Every amount is text, like "650.00", so every digit reaches Glade.
 */
@Service
public class TenancyRoll {

    // The three kinds of people on a tenancy, as the screens name them
    public static final String TENANT = "TENANT";
    public static final String OCCUPANT = "OCCUPANT";
    public static final String INTERESTED = "INTERESTED";

    // The tables a stay of each kind is kept in, which is also the name the stay's history is kept under
    private static final Set<String> MEMBER_TABLES = Set.of("tenant", "occupant", "tenancy_interested_party");

    // A name search answers at most this many people
    private static final int MAX_PEOPLE = 20;

    /**
     * One property's standing, for the list of every property.
     * occupied: lots with a tenancy that has not ended. open: rentable lots that are not occupied and offer at
     * least one kind of agreement. current: tenancies that have not ended. ending: those among them with an end
     * date. noLease: those among them with no charge term in force today.
     */
    public record ParkLine(UUID propertyId, int lots, int rentable, int occupied, int open,
                           int current, int ending, int noLease) { }

    /**
     * One tenancy as a row of a list, and as the record the tenancy page edits (the same names PATCH
     * /api/tenancy/{id} answers with, so a save's answer can be laid over a line).
     * anniversarySource MIGRATED marks a tenancy that arrived through the data migration.
     * agreementType, rent, termStatus, termSource and termValidAt describe the one charge term the row shows
     * (TenancyRollRepository.SHOWN_TERM), all null for a tenancy with no charge term.
     * tenants: the names of the tenants still on the tenancy, or of every tenant the tenancy had when none is.
     * occupants: how many occupants are still on the tenancy.
     * deletedAt is only ever set on the lot page's list, which may include deleted tenancies.
     */
    public record Line(UUID id, UUID lotId, UUID propertyId, LocalDate startDate, LocalDate endDate,
                       LocalDate anniversaryOn, String anniversarySource,
                       boolean noPersonalChecks, boolean noPartialPayments, boolean acceptPayments,
                       boolean exemptFromLateFees, String notes, OffsetDateTime createdAt, OffsetDateTime deletedAt,
                       AgreementType agreementType, String rent, String termStatus, String termSource,
                       LocalDate termValidAt, List<String> tenants, int occupants) { }

    /**
     * One person's stay on a tenancy. role is TENANT, OCCUPANT or INTERESTED; id is the row of that kind's table.
     * notificationReason, acceptPayments and notes belong to an interested party and are null for the others.
     * deletedAt is set for a stay that was deleted (entered on the wrong tenancy).
     */
    public record Member(UUID id, UUID tenancyId, String role, UUID personId, String nameFull,
                         String personalPhone, String personalEmail, LocalDate startDate, LocalDate endDate,
                         String notificationReason, Boolean acceptPayments, String notes,
                         OffsetDateTime deletedAt) {

        // The same stay without the person's phone and email, for a viewer who may not see people's details
        public Member withoutContact() {
            return new Member(id, tenancyId, role, personId, nameFull, null, null, startDate, endDate,
                    notificationReason, acceptPayments, notes, deletedAt);
        }
    }

    /**
     * One charge term of a tenancy: the rent from validAt on. status is PROPOSED, PENDING, ACTIVE or CANCELLED.
     * source says what kind of document the term came from; serial and instrumentStatus are that document's,
     * null for a MIGRATION or CORRECTION term.
     */
    public record Term(UUID id, LocalDate validAt, AgreementType agreementType, String rate, String status,
                       String source, UUID sourceUuid, String serial, String instrumentStatus) { }

    // Where a person found by name lives or lived. ended: the stay or the stay's tenancy has ended.
    public record Home(String role, UUID tenancyId, UUID lotId, UUID propertyId, String propertyCode,
                       String lotNumber, boolean ended) { }

    // One person found by name, with the homes the viewer may see
    public record Found(UUID id, String nameFull, String personalPhone, String personalEmail, List<Home> homes) { }

    private final TenancyRollRepository repository;

    public TenancyRoll(TenancyRollRepository repository) {
        this.repository = repository;
    }

    // Every live property's standing
    public List<ParkLine> parkLines() {
        return repository.findParkRows().stream()
                .map(row -> new ParkLine(row.propertyId(), row.lots(), row.rentable(), row.occupied(), row.open(),
                        row.current(), row.ending(), row.noLease()))
                .toList();
    }

    // Every tenancy on a property's live lots, ended ones included, the newest start first
    public List<Line> tenanciesOf(UUID propertyId) {
        Map<UUID, List<MemberRow>> members = repository.findMembersByProperty(propertyId).stream()
                .collect(Collectors.groupingBy(MemberRow::tenancyId));
        return repository.findLinesByProperty(propertyId).stream()
                .map(row -> line(row, members.getOrDefault(row.id(), List.of())))
                .toList();
    }

    // Every tenancy one lot has had, the newest start first. withDeleted adds the deleted ones.
    public List<Line> tenanciesOfLot(UUID lotId, boolean withDeleted) {
        Map<UUID, List<MemberRow>> members = repository.findMembersByLot(lotId).stream()
                .filter(member -> member.deletedAt() == null)
                .collect(Collectors.groupingBy(MemberRow::tenancyId));
        return repository.findLinesByLot(lotId, withDeleted).stream()
                .map(row -> line(row, members.getOrDefault(row.id(), List.of())))
                .toList();
    }

    // Every stay on one tenancy: tenants first, then occupants, then interested parties, each by start date.
    // Deleted stays are included, with deletedAt set.
    public List<Member> membersOf(UUID lotId, UUID tenancyId) {
        return repository.findMembersByLot(lotId).stream()
                .filter(row -> row.tenancyId().equals(tenancyId))
                .map(TenancyRoll::member)
                .toList();
    }

    // A tenancy's charge terms, the newest first: the rent in force and the rent history
    public List<Term> termsOf(UUID tenancyId) {
        return repository.findTerms(tenancyId).stream()
                .map(row -> new Term(row.id(), row.validAt(), AgreementType.valueOf(row.agreementType()),
                        text(row.rate()), row.status(), row.source(), row.sourceUuid(), row.serial(),
                        row.instrumentStatus()))
                .toList();
    }

    // The property of a tenancy's lot, for checking an agent's property scope. Empty for an unknown tenancy.
    public Optional<UUID> propertyOfTenancy(UUID tenancyId) {
        return repository.findPropertyOfTenancy(tenancyId);
    }

    // The property of the tenancy a stay belongs to. table is tenant, occupant or tenancy_interested_party;
    // empty for any other table and for an unknown stay.
    public Optional<UUID> propertyOfMember(String table, UUID memberId) {
        if (!MEMBER_TABLES.contains(table)) {
            return Optional.empty();
        }
        return repository.findPropertyOfMember(table, memberId);
    }

    /**
     * People on file whose name holds every typed word, at most twenty, by name, each with the homes on
     * properties that pass mayView. Fewer than two typed letters find nobody.
     *
     * <p>everyProperty says the viewer may see every property (PropertyScope.All). A viewer who may not is
     * shown a person only when the person has a home on a property the viewer may see, or when the person is on
     * no tenancy anywhere and is not office staff: someone just put on file, about to be put on a tenancy.
     * A person whose every home is on a property outside the viewer's scope is not found at all, name included.
     */
    public List<Found> peopleNamed(String typed, java.util.function.Predicate<UUID> mayView, boolean everyProperty) {
        List<String> words = typed == null ? List.of() : List.of(typed.strip().split("\\s+")).stream()
                .filter(word -> !word.isEmpty())
                .toList();
        if (words.stream().mapToInt(String::length).sum() < 2) {
            return List.of();
        }
        // a viewer with some properties has people taken out below, so more are asked for than are answered
        List<TenancyRollRepository.PersonRow> people =
                repository.findPeopleNamed(words, everyProperty ? MAX_PEOPLE : MAX_PEOPLE * 5);
        List<UUID> personIds = people.stream().map(TenancyRollRepository.PersonRow::id).toList();
        Map<UUID, List<Home>> homes = new LinkedHashMap<>();
        // the people with a home on a property the viewer may not see
        Set<UUID> elsewhere = new java.util.HashSet<>();
        for (HomeRow row : repository.findHomesOf(personIds)) {
            if (!mayView.test(row.propertyId())) {
                elsewhere.add(row.personId());
                continue;
            }
            LocalDate today = LocalDate.now();
            boolean ended = (row.endDate() != null && row.endDate().isBefore(today))
                    || (row.tenancyEndDate() != null && row.tenancyEndDate().isBefore(today));
            homes.computeIfAbsent(row.personId(), id -> new ArrayList<>())
                    .add(new Home(row.role(), row.tenancyId(), row.lotId(), row.propertyId(), row.propertyCode(),
                            row.lotNumber(), ended));
        }
        Set<UUID> staff = everyProperty ? Set.of() : Set.copyOf(repository.findAgentsAmong(personIds));
        return people.stream()
                .filter(person -> everyProperty
                        || homes.containsKey(person.id())
                        || (!elsewhere.contains(person.id()) && !staff.contains(person.id())))
                .limit(MAX_PEOPLE)
                .map(person -> new Found(person.id(), person.nameFull(), person.personalPhone(), person.personalEmail(),
                        homes.getOrDefault(person.id(), List.of())))
                .toList();
    }

    private static Line line(LineRow row, List<MemberRow> members) {
        List<String> staying = names(members, true);
        return new Line(row.id(), row.lotId(), row.propertyId(), row.startDate(), row.endDate(),
                row.anniversaryOn(), row.anniversarySource(),
                row.noPersonalChecks(), row.noPartialPayments(), row.acceptPayments(), row.exemptFromLateFees(),
                row.notes(), row.createdAt(), row.deletedAt(),
                row.termAgreementType() == null ? null : AgreementType.valueOf(row.termAgreementType()),
                text(row.termRate()), row.termStatus(), row.termSource(), row.termValidAt(),
                staying.isEmpty() ? names(members, false) : staying,
                (int) members.stream().filter(m -> m.role().equals(OCCUPANT) && m.endDate() == null).count());
    }

    // The tenants' names. stayingOnly keeps the tenants whose stay has no end date.
    private static List<String> names(List<MemberRow> members, boolean stayingOnly) {
        return members.stream()
                .filter(m -> m.role().equals(TENANT))
                .filter(m -> !stayingOnly || m.endDate() == null)
                .map(m -> m.nameFull() == null ? "" : m.nameFull())
                .toList();
    }

    private static Member member(MemberRow row) {
        return new Member(row.id(), row.tenancyId(), row.role(), row.personId(), row.nameFull(),
                row.personalPhone(), row.personalEmail(), row.startDate(), row.endDate(),
                row.notificationReason(), row.acceptPayments(), row.notes(), row.deletedAt());
    }

    // "650.00", or null for no amount
    private static String text(BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }
}
