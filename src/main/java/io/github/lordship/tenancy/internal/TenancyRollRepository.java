package io.github.lordship.tenancy.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only joins for Glade's Tenancies screens (TenancyRoll). Writes go through TenancyService and the
 * tenants module's services.
 *
 * <p>These read the lot, property, person, tenant, occupant, tenancy_interested_party, tenancy_charge_term,
 * instrument, lot_permissible_agreement_type and "transaction" tables by name on purpose: who is on a tenancy and
 * which rent the tenancy is under are facts a tenancy's row on a screen cannot be shown without, and the modules
 * that own those tables already depend on this one.
 *
 * <p>Every query leaves out deleted tenancies, deleted lots and deleted properties unless the query says otherwise.
 */
@Repository
public class TenancyRollRepository {

    // sql: the one charge term a tenancy's row shows. The term in force today comes first (ACTIVE, valid_at today
    // or earlier, newest valid_at); with none in force, the newest ACTIVE term that starts later, then the newest
    // PENDING term, then the newest PROPOSED term. CANCELLED and deleted terms are never shown.
    private static final String SHOWN_TERM = """
            LEFT JOIN LATERAL (
                SELECT c.agreement_type::text AS agreement_type, c.rate, c.status, c.source, c.valid_at
                FROM tenancy_charge_term c
                WHERE c.tenancy = t.uuid
                  AND c.deleted_at IS NULL
                  AND c.status <> 'CANCELLED'
                ORDER BY (c.status = 'ACTIVE' AND c.valid_at <= CURRENT_DATE) DESC,
                         CASE c.status WHEN 'ACTIVE' THEN 1 WHEN 'PENDING' THEN 2 ELSE 3 END,
                         c.valid_at DESC
                LIMIT 1
            ) term ON TRUE""";

    // sql: the columns of one tenancy's row on a screen
    private static final String LINE_COLUMNS = """
            t.uuid AS id, t.lot_id, l.property_id, t.start_date, t.end_date, t.anniversary_on, t.anniversary_source,
            t.no_personal_checks, t.no_partial_payments, t.accept_payments, t.exempt_from_late_fees,
            t.notes, t.created_at, t.deleted_at,
            term.agreement_type AS term_agreement_type, term.rate AS term_rate, term.status AS term_status,
            term.source AS term_source, term.valid_at AS term_valid_at""";

    private final JdbcClient jdbc;

    public TenancyRollRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // One property's standing: the lots, and the tenancies that have not ended
    public record ParkRow(UUID propertyId, int lots, int rentable, int occupied, int open,
                          int current, int ending, int noLease) { }

    // One tenancy, with the charge term the tenancy's row shows (null columns when the tenancy has none)
    public record LineRow(UUID id, UUID lotId, UUID propertyId, LocalDate startDate, LocalDate endDate,
                          LocalDate anniversaryOn, String anniversarySource,
                          boolean noPersonalChecks, boolean noPartialPayments, boolean acceptPayments,
                          boolean exemptFromLateFees, String notes, OffsetDateTime createdAt, OffsetDateTime deletedAt,
                          String termAgreementType, BigDecimal termRate, String termStatus, String termSource,
                          LocalDate termValidAt) { }

    // One person's stay on one tenancy. role is TENANT, OCCUPANT or INTERESTED.
    // notificationReason, acceptPayments and notes are an interested party's own columns, null for the other two.
    public record MemberRow(UUID id, UUID tenancyId, String role, UUID personId, String nameFull,
                            String personalPhone, String personalEmail, LocalDate startDate, LocalDate endDate,
                            String notificationReason, Boolean acceptPayments, String notes,
                            OffsetDateTime deletedAt) { }

    // One charge term of a tenancy, with the serial and status of the document the term came from (null for a
    // MIGRATION or CORRECTION term, which has no document)
    public record TermRow(UUID id, LocalDate validAt, String agreementType, BigDecimal rate, String status,
                          String source, UUID sourceUuid, String serial, String instrumentStatus) { }

    // One stay of a person found by name: where the person lives or lived
    public record HomeRow(UUID personId, String role, UUID tenancyId, UUID lotId, UUID propertyId,
                          String propertyCode, String lotNumber, LocalDate endDate, LocalDate tenancyEndDate) { }

    // One person found by name
    public record PersonRow(UUID id, String nameFull, String personalPhone, String personalEmail) { }

    /**
     * One row for each live property. A lot is occupied while a tenancy on the lot has not ended (the same test
     * LotOffersRepository calls taken). A lot is open when the lot is rentable, not occupied, and offers at least
     * one kind of agreement. current counts the tenancies that have not ended; ending counts those among them with
     * an end date; noLease counts those among them with no ACTIVE charge term in force today.
     */
    public List<ParkRow> findParkRows() {
        return jdbc.sql("""
                WITH live AS (
                    SELECT t.uuid, t.lot_id, t.end_date
                    FROM tenancy t
                    WHERE t.deleted_at IS NULL
                      AND (t.end_date IS NULL OR t.end_date >= CURRENT_DATE)
                ),
                lots AS (
                    SELECT l.property_id,
                           count(*) AS lots,
                           count(*) FILTER (WHERE l.is_rentable) AS rentable,
                           count(*) FILTER (WHERE EXISTS (SELECT 1 FROM live v WHERE v.lot_id = l.uuid)) AS occupied,
                           count(*) FILTER (WHERE l.is_rentable
                                              AND NOT EXISTS (SELECT 1 FROM live v WHERE v.lot_id = l.uuid)
                                              AND EXISTS (SELECT 1 FROM lot_permissible_agreement_type a
                                                          WHERE a.lot_id = l.uuid)) AS open
                    FROM lot l
                    WHERE l.deleted_at IS NULL
                    GROUP BY l.property_id
                ),
                tenancies AS (
                    SELECT l.property_id,
                           count(*) AS current,
                           count(*) FILTER (WHERE v.end_date IS NOT NULL) AS ending,
                           count(*) FILTER (WHERE NOT EXISTS (
                               SELECT 1 FROM tenancy_charge_term c
                               WHERE c.tenancy = v.uuid AND c.deleted_at IS NULL
                                 AND c.status = 'ACTIVE' AND c.valid_at <= CURRENT_DATE)) AS no_lease
                    FROM live v
                    JOIN lot l ON l.uuid = v.lot_id AND l.deleted_at IS NULL
                    GROUP BY l.property_id
                )
                SELECT p.uuid AS property_id,
                       COALESCE(lots.lots, 0)::int AS lots,
                       COALESCE(lots.rentable, 0)::int AS rentable,
                       COALESCE(lots.occupied, 0)::int AS occupied,
                       COALESCE(lots.open, 0)::int AS open,
                       COALESCE(tenancies.current, 0)::int AS current,
                       COALESCE(tenancies.ending, 0)::int AS ending,
                       COALESCE(tenancies.no_lease, 0)::int AS no_lease
                FROM property p
                LEFT JOIN lots ON lots.property_id = p.uuid
                LEFT JOIN tenancies ON tenancies.property_id = p.uuid
                WHERE p.deleted_at IS NULL
                ORDER BY p.uuid
                """)
                .query(ParkRow.class)
                .list();
    }

    // Every tenancy on a property's live lots, ended ones included, the newest start first
    public List<LineRow> findLinesByProperty(UUID propertyId) {
        return jdbc.sql("""
                SELECT %s
                FROM tenancy t
                JOIN lot l ON l.uuid = t.lot_id AND l.deleted_at IS NULL
                %s
                WHERE l.property_id = :propertyId
                  AND t.deleted_at IS NULL
                ORDER BY t.start_date DESC NULLS LAST, t.created_at DESC
                """.formatted(LINE_COLUMNS, SHOWN_TERM))
                .param("propertyId", propertyId)
                .query(LineRow.class)
                .list();
    }

    // Every tenancy a lot has had, the newest start first. withDeleted adds the deleted ones.
    public List<LineRow> findLinesByLot(UUID lotId, boolean withDeleted) {
        return jdbc.sql("""
                SELECT %s
                FROM tenancy t
                JOIN lot l ON l.uuid = t.lot_id
                %s
                WHERE t.lot_id = :lotId
                  AND (:withDeleted OR t.deleted_at IS NULL)
                ORDER BY t.start_date DESC NULLS LAST, t.created_at DESC
                """.formatted(LINE_COLUMNS, SHOWN_TERM))
                .param("lotId", lotId)
                .param("withDeleted", withDeleted)
                .query(LineRow.class)
                .list();
    }

    // sql: every stay on some tenancies, the three kinds of people as one list. %s is the test that picks the
    // tenancies, written against the alias m (m.tenancy_id).
    private static final String MEMBERS = """
            SELECT m.id, m.tenancy_id, m.role, m.person_id, p.name_full, p.personal_phone, p.personal_email,
                   m.start_date, m.end_date, m.notification_reason, m.accept_payments, m.notes, m.deleted_at
            FROM (
                SELECT uuid AS id, tenancy_id, 'TENANT' AS role, person_id, start_date, end_date,
                       NULL::text AS notification_reason, NULL::boolean AS accept_payments, NULL::text AS notes,
                       deleted_at, created_at
                FROM tenant
                UNION ALL
                SELECT uuid, tenancy_id, 'OCCUPANT', person_id, start_date, end_date,
                       NULL::text, NULL::boolean, NULL::text, deleted_at, created_at
                FROM occupant
                UNION ALL
                SELECT uuid, tenancy_id, 'INTERESTED', person_id, start_date, end_date,
                       notification_reason, accept_payments, notes, deleted_at, created_at
                FROM tenancy_interested_party
            ) m
            JOIN person p ON p.uuid = m.person_id
            WHERE %s
            ORDER BY m.tenancy_id, m.role DESC, m.start_date NULLS LAST, m.created_at
            """;

    // The live stays on every tenancy of a property's lots (deleted stays left out)
    public List<MemberRow> findMembersByProperty(UUID propertyId) {
        return jdbc.sql(MEMBERS.formatted("""
                m.deleted_at IS NULL
                  AND m.tenancy_id IN (SELECT t.uuid FROM tenancy t JOIN lot l ON l.uuid = t.lot_id
                                       WHERE l.property_id = :propertyId AND t.deleted_at IS NULL)"""))
                .param("propertyId", propertyId)
                .query(MemberRow.class)
                .list();
    }

    // The stays on every tenancy of one lot, deleted stays included (deletedAt says which)
    public List<MemberRow> findMembersByLot(UUID lotId) {
        return jdbc.sql(MEMBERS.formatted(
                "m.tenancy_id IN (SELECT t.uuid FROM tenancy t WHERE t.lot_id = :lotId)"))
                .param("lotId", lotId)
                .query(MemberRow.class)
                .list();
    }

    // A tenancy's charge terms, the newest first. Deleted terms are left out; cancelled ones are kept, because a
    // cancelled term is part of the rent history.
    public List<TermRow> findTerms(UUID tenancyId) {
        return jdbc.sql("""
                SELECT c.uuid AS id, c.valid_at, c.agreement_type::text AS agreement_type, c.rate, c.status, c.source,
                       c.source_uuid, i.serial, i.status AS instrument_status
                FROM tenancy_charge_term c
                LEFT JOIN instrument i ON i.uuid = c.source_uuid
                WHERE c.tenancy = :tenancyId
                  AND c.deleted_at IS NULL
                ORDER BY c.valid_at DESC, c.created_at DESC
                """)
                .param("tenancyId", tenancyId)
                .query(TermRow.class)
                .list();
    }

    // The property of a tenancy's lot. The tenancy and the lot may be deleted: the tenancy's history is still
    // the property's.
    public Optional<UUID> findPropertyOfTenancy(UUID tenancyId) {
        return jdbc.sql("""
                SELECT l.property_id
                FROM tenancy t
                JOIN lot l ON l.uuid = t.lot_id
                WHERE t.uuid = :tenancyId
                """)
                .param("tenancyId", tenancyId)
                .query(UUID.class)
                .optional();
    }

    // The property of the tenancy a stay belongs to. table is tenant, occupant or tenancy_interested_party;
    // the caller checks the name, which is why the name can be written into the sql.
    public Optional<UUID> findPropertyOfMember(String table, UUID memberId) {
        return jdbc.sql("""
                SELECT l.property_id
                FROM %s m
                JOIN tenancy t ON t.uuid = m.tenancy_id
                JOIN lot l ON l.uuid = t.lot_id
                WHERE m.uuid = :memberId
                """.formatted(table))
                .param("memberId", memberId)
                .query(UUID.class)
                .optional();
    }

    /**
     * Live people whose name holds every one of the words, at most limit, by name.
     * Each word is matched anywhere in the name, without regard to capitals.
     */
    public List<PersonRow> findPeopleNamed(List<String> words, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.uuid AS id, p.name_full, p.personal_phone, p.personal_email
                FROM person p
                WHERE p.deleted_at IS NULL
                  AND p.name_full IS NOT NULL
                """);
        for (int i = 0; i < words.size(); i++) {
            sql.append("  AND LOWER(p.name_full) LIKE :word").append(i).append(" ESCAPE '\\'\n");
        }
        sql.append("ORDER BY LOWER(p.name_full), p.uuid\nLIMIT :limit");

        JdbcClient.StatementSpec query = jdbc.sql(sql.toString()).param("limit", limit);
        for (int i = 0; i < words.size(); i++) {
            query = query.param("word" + i, "%" + escapeLike(words.get(i).toLowerCase()) + "%");
        }
        return query.query(PersonRow.class).list();
    }

    // Where these people live or lived: every live stay on a live tenancy of a live lot of a live property
    public List<HomeRow> findHomesOf(List<UUID> personIds) {
        if (personIds.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT m.person_id, m.role, t.uuid AS tenancy_id, l.uuid AS lot_id, pr.uuid AS property_id,
                       pr.property_code, l.lot_number, m.end_date, t.end_date AS tenancy_end_date
                FROM (
                    SELECT person_id, tenancy_id, 'TENANT' AS role, end_date, start_date FROM tenant WHERE deleted_at IS NULL
                    UNION ALL
                    SELECT person_id, tenancy_id, 'OCCUPANT', end_date, start_date FROM occupant WHERE deleted_at IS NULL
                    UNION ALL
                    SELECT person_id, tenancy_id, 'INTERESTED', end_date, start_date
                    FROM tenancy_interested_party WHERE deleted_at IS NULL
                ) m
                JOIN tenancy t ON t.uuid = m.tenancy_id AND t.deleted_at IS NULL
                JOIN lot l ON l.uuid = t.lot_id AND l.deleted_at IS NULL
                JOIN property pr ON pr.uuid = l.property_id AND pr.deleted_at IS NULL
                WHERE m.person_id IN (:personIds)
                ORDER BY m.person_id, m.start_date DESC NULLS LAST
                """)
                .param("personIds", personIds)
                .query(HomeRow.class)
                .list();
    }

    // The people among these who sign in as a live agent (office staff). agent is the access module's table,
    // read here by name: access depends on persons and tenancy does not depend on access.
    public List<UUID> findAgentsAmong(List<UUID> personIds) {
        if (personIds.isEmpty()) return List.of();
        return jdbc.sql("SELECT person_id FROM agent WHERE person_id IN (:personIds) AND deleted_at IS NULL")
                .param("personIds", personIds)
                .query(UUID.class)
                .list();
    }

    // % and _ mean "anything" to LIKE; a name typed with one of them is looked for as typed
    private static String escapeLike(String word) {
        return word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
