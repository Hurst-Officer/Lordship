package io.github.lordship.instruments;

import io.github.lordship.tenancyterms.TenancyChargeTerm;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * What a document says, written into its own PDF so nobody has to read it to
 * find out.
 *
 * <p>Hurst and Son has a OneDrive of old leases that have to be read by an AI
 * before Lordship can use them. Our own PDFs will end up in the same folders.
 * Every PDF Lordship makes carries this record, so the reader can skip it.
 *
 * <p>Three places in the PDF, cheapest to read first:
 * <ol>
 *   <li>Custom fields in the document info: {@code LordshipFormat},
 *       {@code LordshipSerial}, {@code LordshipStatus}. Reading these does not
 *       touch any page.
 *   <li>Title, Subject and Keywords, which Acrobat and Windows search show.
 *   <li>An attached file, {@code lordship-instrument.json}, with the whole deal.
 * </ol>
 *
 * <p>The JSON is a contract. The lease reader we have not built yet should
 * write the same shape onto the leases it reads, with {@code source} set to
 * something other than LORDSHIP, so in the end every PDF we have says what it
 * is in the same words. Add fields freely. Renaming or removing one means
 * bumping {@link #FORMAT_VERSION}.
 *
 * <p>Pure. Facts in, strings out.
 */
public record InstrumentMetadata(
        String title,
        String subject,
        String keywords,
        String serial,
        Status status,
        byte[] json
) {

    /** The attached file's name. Readers look for it by this name. */
    public static final String FILE_NAME = "lordship-instrument.json";

    public static final String FORMAT = "lordship.instrument";
    public static final int FORMAT_VERSION = 1;

    /** Lordship made this file. A reader that fills the record in itself uses its own value. */
    public static final String SOURCE = "LORDSHIP";

    /**
     * GENERATED is the copy that goes to the tenant. PREVIEW is a draft that
     * was never generated, and must never be filed as a lease.
     */
    public enum Status { GENERATED, PREVIEW }

    /**
     * @param serial the printed serial, or null for a preview
     */
    public static InstrumentMetadata of(TokenResolver.LeaseFacts facts,
                                        LeasePreview preview,
                                        String serial,
                                        Status status,
                                        OffsetDateTime writtenAt) {
        Instrument instrument = facts.instrument();
        String lotNumber = facts.lot().lotNumber();
        String parkName = facts.property().propertyName();

        String title = instrument.type() + " - " + parkName + " lot " + lotNumber
                + (serial == null ? " (preview)" : " - " + serial);
        String subject = String.join(", ", facts.tenantNames());
        String keywords = String.join(", ",
                serial == null ? "PREVIEW" : serial,
                instrument.type().name(),
                String.valueOf(instrument.agreementType()),
                parkName,
                "lot " + lotNumber);

        return new InstrumentMetadata(title, subject, keywords, serial, status,
                json(facts, preview, serial, status, writtenAt).getBytes(StandardCharsets.UTF_8));
    }

    // ---- the JSON ------------------------------------------------------------

    private static String json(TokenResolver.LeaseFacts facts, LeasePreview preview, String serial,
                               Status status, OffsetDateTime writtenAt) {
        Instrument instrument = facts.instrument();
        Json out = new Json();

        out.open();
        out.field("format", FORMAT);
        out.field("format_version", FORMAT_VERSION);
        out.field("source", SOURCE);
        out.field("status", status);
        out.field("written_at", writtenAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        out.name("instrument").open();
        out.field("uuid", instrument.uuid());
        out.field("serial", serial);
        out.field("type", instrument.type());
        out.field("agreement_type", instrument.agreementType());
        out.field("term_start", instrument.termStart());
        out.field("term_months", instrument.termMonths());
        out.field("last_covered_day", instrument.lastCoveredDay().orElse(null));
        out.field("on_expiry", instrument.onExpiry());
        out.field("amends", instrument.amends());
        out.close();

        out.name("document").open();
        out.field("template", preview.documentTemplate());
        out.field("name", preview.documentName());
        out.field("version", preview.documentVersion());
        out.close();

        out.name("property").open();
        out.field("uuid", facts.property().uuid());
        out.field("code", facts.property().propertyCode());
        out.field("name", facts.property().propertyName());
        out.field("street", facts.property().propertyStreet());
        out.field("city", facts.property().propertyCity());
        out.field("state", facts.property().propertyState());
        out.field("zip", facts.property().propertyZip());
        out.close();

        out.name("lot").open();
        out.field("uuid", facts.lot().uuid());
        out.field("number", facts.lot().lotNumber());
        out.field("address", facts.lot().lotAddress());
        out.close();

        out.name("tenancy").open();
        out.field("uuid", facts.tenancy().uuid());
        out.field("start_date", facts.tenancy().startDate());
        out.name("tenants").openList();
        for (String name : facts.tenantNames()) {
            out.item(name);
        }
        out.closeList();
        out.close();

        // The deal: one entry per step of the rent schedule, earliest first.
        out.name("charge_terms").openList();
        for (TenancyChargeTerm term : facts.schedule()) {
            chargeTerm(out, term);
        }
        out.closeList();

        out.close();
        return out.toString();
    }

    // The field names are the API's own snake_case names for these columns.
    private static void chargeTerm(Json out, TenancyChargeTerm term) {
        out.open();
        out.field("uuid", term.uuid());
        out.field("valid_at", term.validAt());
        out.field("agreement_type", term.agreementType());
        out.field("rate", term.rate());

        out.field("car_fee", term.carFee());
        out.field("allowed_cars", term.allowedCars());
        out.field("cars_max", term.carsMax());
        out.field("pet_fee", term.petFee());
        out.field("allowed_pets", term.allowedPets());

        out.field("payment_due_day", term.paymentDueDay());
        out.field("grace_period_days", term.gracePeriodDays());

        out.field("rule_violation_fee_method", term.ruleViolationFeeMethod());
        out.field("rule_violation_fee_amount", term.ruleViolationFeeAmount());
        out.field("nsf_fee_method", term.nsfFeeMethod());
        out.field("nsf_fee_amount", term.nsfFeeAmount());
        out.field("late_fee_method", term.lateFeeMethod());
        out.field("late_fee_amount", term.lateFeeAmount());

        out.field("water_method", term.waterMethod());
        out.field("water_flat_amount", term.waterFlatAmount());
        out.field("power_method", term.powerMethod());
        out.field("power_flat_amount", term.powerFlatAmount());
        out.field("sewer_method", term.sewerMethod());
        out.field("sewer_flat_amount", term.sewerFlatAmount());
        out.field("trash_method", term.trashMethod());
        out.field("trash_flat_amount", term.trashFlatAmount());

        out.field("security_deposit_method", term.securityDepositMethod());
        out.field("security_deposit_amount", term.securityDepositAmount());
        out.close();
    }

    /**
     * A very small JSON writer, so this class needs no library. Numbers are
     * written as numbers, null as null, and everything else as a string.
     */
    static final class Json {
        private final StringBuilder text = new StringBuilder(2048);
        private boolean needsComma = false;
        private boolean afterName = false;
        private int depth = 0;

        Json open() {
            startItem();
            text.append('{');
            depth++;
            needsComma = false;
            return this;
        }

        /**
         * An object or list inside a list starts on its own line, after a
         * comma if it is not the first. After a field name it stays on the
         * name's line.
         */
        private void startItem() {
            if (needsComma) {
                text.append(',');
            }
            if (!afterName && depth > 0) {
                newLine();
            }
            afterName = false;
        }

        Json close() {
            depth--;
            newLine();
            text.append('}');
            needsComma = true;
            return this;
        }

        Json openList() {
            startItem();
            text.append('[');
            depth++;
            needsComma = false;
            return this;
        }

        Json closeList() {
            depth--;
            newLine();
            text.append(']');
            needsComma = true;
            return this;
        }

        /** Starts a field whose value comes next: an object, a list or a value. */
        Json name(String name) {
            if (needsComma) {
                text.append(',');
            }
            newLine();
            text.append(quote(name)).append(": ");
            needsComma = false;
            afterName = true;
            return this;
        }

        void field(String name, Object value) {
            name(name);
            value(value);
        }

        /** One entry in a list. */
        void item(Object value) {
            if (needsComma) {
                text.append(',');
            }
            newLine();
            value(value);
        }

        private void value(Object value) {
            if (value == null) {
                text.append("null");
            } else if (value instanceof BigDecimal number) {
                text.append(number.toPlainString());
            } else if (value instanceof Integer || value instanceof Long) {
                text.append(value);
            } else {
                text.append(quote(value.toString()));
            }
            needsComma = true;
            afterName = false;
        }

        private void newLine() {
            text.append('\n').append("  ".repeat(Math.max(depth, 0)));
        }

        private static String quote(String raw) {
            StringBuilder quoted = new StringBuilder(raw.length() + 2).append('"');
            for (char c : raw.toCharArray()) {
                switch (c) {
                    case '"' -> quoted.append("\\\"");
                    case '\\' -> quoted.append("\\\\");
                    case '\n' -> quoted.append("\\n");
                    case '\r' -> quoted.append("\\r");
                    case '\t' -> quoted.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            quoted.append(String.format("\\u%04x", (int) c));
                        } else {
                            quoted.append(c);
                        }
                    }
                }
            }
            return quoted.append('"').toString();
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
