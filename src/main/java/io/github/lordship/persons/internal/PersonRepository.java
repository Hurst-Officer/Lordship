package io.github.lordship.persons.internal;


import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;


import java.util.*;

@Repository
public class PersonRepository {

    private static final Set<String> PATCHABLE_COLUMNS = Set.of(
            "name_full", "birthday", "personal_phone",
            "personal_email", "mailing_address",
            "emergency_contact", "social"
    );

    private final JdbcClient jdbc;

    public PersonRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // the default for lordship: the save func defines the minimum data required to insert a row
    public PersonRow save(String nameFull) {
        return jdbc.sql("""
            INSERT INTO person (
                name_full
            ) VALUES (
                :nameFull
            ) RETURNING *
            """)
                .param("nameFull", nameFull)
                .query(PersonRow.class)
                .single();
    }

    public boolean softDelete(UUID uuid) {
        return jdbc.sql("UPDATE person SET deleted_at = now() WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .update() > 0;
    }

    // True when a live agent signs in as this person. agent is the access module's table, read here by name:
    // access already depends on persons, so persons cannot call access.
    public boolean isLiveAgent(UUID personId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM agent WHERE person_id = :personId AND deleted_at IS NULL)")
                .param("personId", personId)
                .query(Boolean.class)
                .single();
    }

    // True while the person is on a tenancy (a tenant, an occupant or an interested party) or has a claim on a home
    // (a secured party), ended stays included. Stays and claims that were deleted do not count.
    // The four tables belong to the tenants and homes modules and are read here by name, like agent above:
    // those modules already depend on persons, so persons cannot call those modules.
    public boolean isInUse(UUID personId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM tenant WHERE person_id = :personId AND deleted_at IS NULL)
                    OR EXISTS (SELECT 1 FROM occupant WHERE person_id = :personId AND deleted_at IS NULL)
                    OR EXISTS (SELECT 1 FROM tenancy_interested_party WHERE person_id = :personId AND deleted_at IS NULL)
                    OR EXISTS (SELECT 1 FROM mobile_home_secured_party WHERE person_id = :personId AND deleted_at IS NULL)
                """)
                .param("personId", personId)
                .query(Boolean.class)
                .single();
    }

    // A person whether the person is deleted or not
    public Optional<PersonRow> findAnyById(UUID uuid) {
        return jdbc.sql("SELECT * FROM person WHERE uuid = :uuid")
                .param("uuid", uuid)
                .query(PersonRow.class)
                .optional();
    }

    // Brings a deleted person back. Empty when the person was not deleted.
    public Optional<PersonRow> restore(UUID uuid) {
        return jdbc.sql("UPDATE person SET deleted_at = NULL WHERE uuid = :uuid AND deleted_at IS NOT NULL RETURNING *")
                .param("uuid", uuid)
                .query(PersonRow.class)
                .optional();
    }

    public Optional<PersonRow> findById(UUID uuid){
        return jdbc.sql("SELECT * FROM person WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .query(PersonRow.class)
                .optional();
    }

    public Optional<PersonRow> findByEmail(String email){
        return jdbc.sql("SELECT * FROM person WHERE LOWER(personal_email) = LOWER(:email) AND deleted_at IS NULL")
                .param("email", email)
                .query(PersonRow.class)
                .optional();
    }

    //NOTE: On this func, the repo gave up type safety in exchange for generality- which is why the social is encrypted at service layer
    public Optional<PersonRow> patch(UUID uuid, Map<String, Object> changes){
        if (changes.isEmpty()) return findById(uuid);

        for (String col : changes.keySet()) {
            if (!PATCHABLE_COLUMNS.contains(col)) {
                throw new IllegalArgumentException("Invalid column: " + col);
            }
        }

        StringBuilder sql = new StringBuilder("UPDATE person SET ");

        changes.forEach((col,val)-> sql.append(col).append("= :").append(col).append(", "));

        // trim trialing comma and space
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *");

        Map<String, Object> params = new HashMap<>(changes);
        params.put("uuid", uuid);

        return jdbc.sql(sql.toString())
                .params(params)
                .query(PersonRow.class)
                .optional();
    }
}