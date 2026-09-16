package io.github.lordship.instruments;

import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;
import org.junit.jupiter.api.Test;

import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The number an office worker reads off a piece of paper and types back in.
 * Every test here is really the same question: does it survive that trip.
 */
public class SerialTest {

    /** Walks the alphabet so the random half is predictable: 0123 4567. */
    private static RandomGenerator counting() {
        return new RandomGenerator() {
            private int next = 0;
            @Override public int nextInt(int bound) { return next++ % bound; }
            @Override public long nextLong() { throw new UnsupportedOperationException(); }
        };
    }

    // ---- shape ---------------------------------------------------------------

    @Test
    void generate_shouldReadAsGroupsOfFour() {
        // Act
        String serial = Serial.generate("HS", AgreementType.RESIDENTIAL, InstrumentType.ASSUMPTION, counting());

        // Assert -- groups of four are how people copy characters without
        // losing their place, same reason a card number is not sixteen in a row
        assertEquals("HS-0123-4567-RAS", serial);
    }

    @Test
    void generate_shouldEndWithTheDealAndThePaper() {
        // Arrange + Act
        String lease = Serial.generate("HS", AgreementType.LAND, InstrumentType.LEASE, counting());

        // Assert -- L for a land lease, LE for a lease
        assertTrue(lease.endsWith("-LLE"), lease);
    }

    @Test
    void generate_shouldTellAssumptionAndAddendumApart() {
        // Arrange -- both start with A, which is why the code is two letters
        String assumption = Serial.generate("HS", AgreementType.LAND, InstrumentType.ASSUMPTION, counting());
        String addendum = Serial.generate("HS", AgreementType.LAND, InstrumentType.ADDENDUM, counting());

        // Assert
        assertTrue(assumption.endsWith("-LAS"), assumption);
        assertTrue(addendum.endsWith("-LAD"), addendum);
    }

    @Test
    void generate_shouldNeverUseACharacterThatReadsAsAnother() {
        // Arrange -- Crockford leaves out I, L, O and U
        StringBuilder everything = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            String serial = Serial.generate("HS", AgreementType.LAND, InstrumentType.LEASE);
            everything.append(serial, 3, 12); // the random half only
        }

        // Assert
        String random = everything.toString();
        assertFalse(random.contains("I"), random);
        assertFalse(random.contains("L"), random);
        assertFalse(random.contains("O"), random);
        assertFalse(random.contains("U"), random);
    }

    @Test
    void generate_shouldRejectAPrefixThatIsNotTwoCharacters() {
        // Act + Assert
        assertThrows(IllegalArgumentException.class,
                () -> Serial.generate("HURST", AgreementType.LAND, InstrumentType.LEASE));
        assertThrows(IllegalArgumentException.class,
                () -> Serial.generate(null, AgreementType.LAND, InstrumentType.LEASE));
    }

    @Test
    void generate_shouldNotRepeatItself() {
        // Arrange + Act -- 32^8 is about a thousand billion
        String first = Serial.generate("HS", AgreementType.LAND, InstrumentType.LEASE);
        String second = Serial.generate("HS", AgreementType.LAND, InstrumentType.LEASE);

        // Assert
        assertNotEquals(first, second);
    }

    // ---- typing it back in ---------------------------------------------------

    @Test
    void normalize_shouldLeaveAGeneratedSerialAlone() {
        // Arrange
        String serial = Serial.generate("HS", AgreementType.LAND, InstrumentType.LEASE);

        // Act + Assert -- what is stored is what comes back
        assertEquals(serial, Serial.normalize(serial));
    }

    @Test
    void normalize_shouldForgiveTheCharactersPeopleMisread() {
        // Arrange -- O typed for zero, I and l typed for one
        // Act
        String typed = Serial.normalize("HS-OI23-45l7-RAS");

        // Assert -- this is the other half of Crockford's bargain: those
        // characters are never generated, so they can always be read back
        assertEquals("HS-0123-4517-RAS", typed);
    }

    @Test
    void normalize_shouldForgiveCaseAndMissingHyphens() {
        // Arrange -- somebody typing from a printout in a hurry
        // Act + Assert
        assertEquals("HS-0123-4567-RAS", Serial.normalize("hs01234567ras"));
        assertEquals("HS-0123-4567-RAS", Serial.normalize("  HS 0123 4567 RAS  "));
    }

    @Test
    void normalize_shouldNotTouchTheInstrumentCode() {
        // Arrange -- an increase notice ends IN, and mapping that I to a 1
        // would turn a real serial into one that matches nothing
        // Act
        String typed = Serial.normalize("HS-2345-6789-LIN");

        // Assert
        assertEquals("HS-2345-6789-LIN", typed);
    }

    @Test
    void normalize_shouldNotTouchThePrefix() {
        // Arrange -- Romack's prefix has an O in it
        // Act
        String typed = Serial.normalize("RO-2345-6789-LLE");

        // Assert -- and the lease code has an L
        assertEquals("RO-2345-6789-LLE", typed);
    }

    @Test
    void normalize_shouldAnswerNull_forSomethingThatIsNotASerial() {
        // Arrange + Act + Assert -- a lookup should say "no such document"
        // rather than throwing at whoever typed it
        assertNull(Serial.normalize(null));
        assertNull(Serial.normalize(""));
        assertNull(Serial.normalize("HS-0123"));
        assertNull(Serial.normalize("HS-0123-4567-RAS-EXTRA"));
    }
}
