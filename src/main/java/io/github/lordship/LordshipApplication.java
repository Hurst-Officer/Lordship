package io.github.lordship;

import io.github.lordship.shared.EncryptionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.TimeZone;

@SpringBootApplication
@EnableConfigurationProperties(EncryptionProperties.class)
public class LordshipApplication {

    // The time zone the office keeps its calendar in, unless LORDSHIP_ZONE names another (an IANA name)
    static final String OFFICE_ZONE = "America/Los_Angeles";

    public static void main(String[] args) {
        useOfficeCalendar();
        SpringApplication.run(LordshipApplication.class, args);
    }

    /**
     * One calendar for the whole server: the office's.
     *
     * <p>"Has this tenancy ended?", "which rent is in force today?" and "which month is the office billing?" all
     * compare a DATE column with today, and today is a different day in different time zones for part of every
     * day. A server left on UTC would call a tenancy ended from 4 or 5 pm Pacific on the tenancy's last day.
     *
     * <p>Setting the Java default zone before anything else starts settles the answer in both places a date is
     * worked out: LocalDate.now() in the services, and CURRENT_DATE in the SQL, because the Postgres driver opens
     * every database session in the Java default zone. Moments (TIMESTAMPTZ columns) are not touched: a moment is
     * the same instant in every zone.
     */
    static void useOfficeCalendar() {
        String named = System.getenv("LORDSHIP_ZONE");
        String zone = (named == null || named.isBlank()) ? OFFICE_ZONE : named.strip();
        TimeZone.setDefault(TimeZone.getTimeZone(java.time.ZoneId.of(zone)));
    }

}
