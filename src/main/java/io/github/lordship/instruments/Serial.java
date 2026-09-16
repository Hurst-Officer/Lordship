package io.github.lordship.instruments;

import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

/**
 * The number printed on a document and typed back in to find it.
 *
 * <p>{@code HS-41JB-5F32-RAS} -- the company arm, eight random characters in
 * two groups of four, then the kind of deal and the kind of paper. A residential
 * assumption at Hurst and Son.
 *
 * <p>Built to survive a human reading it off paper, which is the only way it is
 * ever entered. The random half uses Crockford's base-32 alphabet, which leaves
 * out I, L, O and U -- so there is no character that could be read as another
 * one, and no accidental words. {@link #normalize} then does the other half of
 * Crockford's bargain: someone who types I for 1 or O for 0 anyway still finds
 * their lease.
 *
 * <p>Groups of four because that is how people copy digits without losing their
 * place, and the same reason a card number is not sixteen characters in a row.
 *
 * <p>The prefix is passed in rather than derived. Romack sells lease-to-buy
 * contracts that are not modelled yet, and a constant here would be one more
 * place to remember when they are.
 */
public final class Serial {

    /** Crockford base 32: no I, L, O or U, so nothing reads as something else. */
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private static final int RANDOM_LENGTH = 8;
    private static final int GROUP = 4;
    private static final int PREFIX_LENGTH = 2;
    private static final int SUFFIX_LENGTH = 3;
    private static final int BARE_LENGTH = PREFIX_LENGTH + RANDOM_LENGTH + SUFFIX_LENGTH;

    private static final RandomGenerator DEFAULT_RANDOM = new SecureRandom();

    private Serial() {}

    public static String generate(String prefix, AgreementType agreementType, InstrumentType instrumentType) {
        return generate(prefix, agreementType, instrumentType, DEFAULT_RANDOM);
    }

    /**
     * The random generator is an argument so a test can pin the characters. In
     * production it is a SecureRandom: the serial is printed on a legal document
     * and a predictable one invites a guessed lookup.
     *
     * <p>32^8 is about a thousand billion, so a collision is not a practical
     * worry -- and the unique index catches one anyway.
     */
    public static String generate(String prefix,
                                  AgreementType agreementType,
                                  InstrumentType instrumentType,
                                  RandomGenerator random) {
        if (prefix == null || prefix.length() != PREFIX_LENGTH) {
            throw new IllegalArgumentException("Serial prefix must be " + PREFIX_LENGTH + " characters");
        }

        StringBuilder out = new StringBuilder(prefix.toUpperCase());
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            if (i % GROUP == 0) {
                out.append('-');
            }
            out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return out.append('-')
                .append(letterFor(agreementType))
                .append(codeFor(instrumentType))
                .toString();
    }

    /**
     * What someone typed, turned into what is stored.
     *
     * <p>Hyphens and spaces are dropped and put back, case is raised, and in the
     * random half only, I and L become 1 and O becomes 0. Only in the random
     * half: the suffix of an addendum is AD and of a lease is LE, and mapping
     * those would turn a real serial into one that matches nothing.
     *
     * <p>Returns null when the input cannot be a serial at all, so a lookup
     * answers "no such document" rather than throwing at whoever typed it.
     */
    public static String normalize(String typed) {
        if (typed == null) {
            return null;
        }
        String bare = typed.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (bare.length() != BARE_LENGTH) {
            return null;
        }

        String prefix = bare.substring(0, PREFIX_LENGTH);
        String random = bare.substring(PREFIX_LENGTH, PREFIX_LENGTH + RANDOM_LENGTH)
                .replace('I', '1')
                .replace('L', '1')
                .replace('O', '0');
        String suffix = bare.substring(PREFIX_LENGTH + RANDOM_LENGTH);

        return prefix
                + "-" + random.substring(0, GROUP)
                + "-" + random.substring(GROUP)
                + "-" + suffix;
    }

    /**
     * Every agreement type starts with a different letter, so one is enough.
     * Written out rather than taking charAt(0) so that adding a type whose
     * initial collides is a decision here instead of a silent duplicate.
     */
    static char letterFor(AgreementType agreementType) {
        return switch (agreementType) {
            case RESIDENTIAL -> 'R';
            case LAND -> 'L';
            case TRANSIENT -> 'T';
            case COMMERCIAL -> 'C';
            case STORAGE -> 'S';
            case UTILITY_SERVICE -> 'U';
        };
    }

    /** Two letters because ASSUMPTION and ADDENDUM both start with A. */
    static String codeFor(InstrumentType instrumentType) {
        return switch (instrumentType) {
            case LEASE -> "LE";
            case INCREASE_NOTICE -> "IN";
            case ASSUMPTION -> "AS";
            case ADDENDUM -> "AD";
            case WAIVER -> "WA";
            case PAY_OR_VACATE -> "PV";
        };
    }
}
