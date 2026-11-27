package io.github.lordship.instruments;

import io.github.lordship.shared.InstrumentType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The name a document's PDF is saved and downloaded under, the way the office names its files:
 * the property code, "Lot" and the lot number, the kind of document, "Copy" for a blank copy (or "Draft" for a
 * draft), and the surname of the first tenant the document names.
 * <pre>
 *   CD Lot 14 Lease Copy Lopez.pdf      a generated lease, not signed yet
 *   CD Lot 14 Lease Draft Lopez.pdf     a draft's preview
 *   CD Lot 14 Lease Lopez.pdf           the signed lease, once Move-In takes the upload
 * </pre>
 * A part that is not known (no property code, nobody on the tenancy yet) is left out. Characters Windows cannot
 * have in a file name are dropped. Only the name changes: a generated file is still stored under the serial
 * (DocumentFileService's storage key), because the storage key must be new and never depends on a name.
 */
public final class PaperName {

    /** What the file is a copy of: a blank copy for signing, a draft's preview, or the signed paper. */
    public enum Copy { BLANK, DRAFT, SIGNED }

    // Suffixes that come after a surname and are not the surname
    private static final Set<String> SUFFIXES = Set.of("jr", "jr.", "sr", "sr.", "ii", "iii", "iv");

    private PaperName() { }

    /**
     * The file name, with ".pdf".
     *
     * @param propertyCode the property's code ("CD"), or null
     * @param lotNumber    the lot's number ("14"), or null
     * @param type         the kind of document
     * @param copy         a blank copy, a draft, or the signed paper
     * @param tenantNames  the tenants' full names in the order the document names them
     */
    public static String of(String propertyCode, String lotNumber, InstrumentType type, Copy copy,
                            List<String> tenantNames) {
        List<String> parts = new ArrayList<>();
        add(parts, propertyCode);
        if (lotNumber != null && !lotNumber.isBlank()) {
            parts.add("Lot " + lotNumber.trim());
        }
        parts.add(typeWords(type));
        switch (copy) {
            case BLANK -> parts.add("Copy");
            case DRAFT -> parts.add("Draft");
            case SIGNED -> { }
        }
        String firstTenant = tenantNames == null ? null
                : tenantNames.stream().filter(name -> name != null && !name.isBlank()).findFirst().orElse(null);
        add(parts, surnameOf(firstTenant));
        return clean(String.join(" ", parts)) + ".pdf";
    }

    /**
     * The surname in a full name: the part before a comma ("Lopez, Maria"), else the last word, leaving out a
     * suffix such as Jr or III. The same rule as Glade's surname under a lot's number. Null for no name.
     */
    static String surnameOf(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String text = name.trim();
        if (text.contains(",")) {
            String before = text.substring(0, text.indexOf(',')).trim();
            return before.isEmpty() ? null : before;
        }
        String[] words = text.split("\\s+");
        for (int i = words.length - 1; i >= 0; i--) {
            if (!SUFFIXES.contains(words[i].toLowerCase(Locale.ROOT))) {
                return words[i];
            }
        }
        return words[words.length - 1];
    }

    // The kind of document as the office names the kind in a file name
    private static String typeWords(InstrumentType type) {
        return switch (type) {
            case LEASE -> "Lease";
            case INCREASE_NOTICE -> "Rent Increase";
            case ASSUMPTION -> "Assumption";
            case ADDENDUM -> "Addendum";
            case WAIVER -> "Waiver";
            case PAY_OR_VACATE -> "Pay or Vacate";
        };
    }

    private static void add(List<String> parts, String part) {
        if (part != null && !part.isBlank()) {
            parts.add(part.trim());
        }
    }

    // Drops the characters Windows refuses in a file name, and any run of spaces left behind
    private static String clean(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").replaceAll("\\s+", " ").trim();
    }
}
