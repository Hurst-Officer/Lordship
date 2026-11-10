package io.github.lordship.meters;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Works out usage and warnings from one meter's reads. Nothing is stored: a
 * read entered late, corrected or deleted changes the answer the next time it is
 * asked, which a stored running total could not do.
 *
 * <p>Usage of a read is counted from the read before it, with the meter's
 * setting in force on the read's own day:
 * <ul>
 *   <li>READ: this reading minus the one before. Lower means BACKWARDS, and no usage.</li>
 *   <li>ROLLOVER: the dial went past dial_max to 0, so (dial_max + 1 - before) + this.</li>
 *   <li>START: nothing is counted; the next read counts from here.</li>
 * </ul>
 * Then x multiplier for the setting's unit, then into gallons or kWh. Usage is
 * never negative: anything that would be is flagged and counts nothing.
 */
public final class ReadChain {

    private ReadChain() {}

    // A read uses more than this many times the meter's usual use per day: HIGH_USAGE
    static final BigDecimal HIGH_USAGE_FACTOR = BigDecimal.valueOf(3);
    // The usual use is the average of up to this many earlier intervals, once there are at least MIN_HISTORY
    static final int HISTORY = 6;
    static final int MIN_HISTORY = 3;

    /**
     * @param reads    one meter's live reads, oldest first
     * @param settings that meter's live settings (one has no start day)
     */
    public static List<ReadInterval> evaluate(List<MeterRead> reads, List<MeterSetting> settings) {
        List<ReadInterval> out = new ArrayList<>(reads.size());
        List<BigDecimal> perDayHistory = new ArrayList<>();
        MeterRead previous = null;

        for (MeterRead read : reads) {
            MeterSetting setting = MeterSetting.inForce(settings, read.readOn());
            long dialMax = setting.dialMax();

            List<ReadFlag> flags = new ArrayList<>();
            if (Boolean.TRUE.equals(read.isEstimated())) flags.add(ReadFlag.ESTIMATED);
            if (read.reading() > dialMax) flags.add(ReadFlag.ABOVE_DIAL);

            Long dialDifference = null;
            if (read.kind() == MeterReadKind.START) {
                perDayHistory.clear(); // a reset meter starts a new history
            } else if (previous == null) {
                flags.add(ReadFlag.NO_PREVIOUS);
            } else if (read.kind() == MeterReadKind.ROLLOVER) {
                if (read.reading() >= previous.reading()) {
                    flags.add(ReadFlag.ROLLOVER_UNLIKELY); // the dial would have gone round more than once: count nothing
                } else if (previous.reading() > dialMax) {
                    if (!flags.contains(ReadFlag.ABOVE_DIAL)) flags.add(ReadFlag.ABOVE_DIAL); // the dial cannot have started above its top
                } else {
                    dialDifference = (dialMax + 1 - previous.reading()) + read.reading();
                }
            } else if (read.reading() < previous.reading()) {
                flags.add(ReadFlag.BACKWARDS);
            } else {
                dialDifference = read.reading() - previous.reading();
                if (dialDifference == 0) flags.add(ReadFlag.NO_CHANGE);
            }

            BigDecimal usage = null;
            BigDecimal baseUsage = null;
            if (dialDifference != null && dialDifference >= 0) {
                usage = BigDecimal.valueOf(dialDifference).multiply(setting.multiplier());
                baseUsage = setting.unit().toBase(usage);

                long days = ChronoUnit.DAYS.between(previous.readOn(), read.readOn());
                if (days > 0) {
                    BigDecimal perDay = baseUsage.divide(BigDecimal.valueOf(days), MeterUnit.BASE_SCALE, RoundingMode.HALF_UP);
                    if (isHigh(perDay, perDayHistory)) flags.add(ReadFlag.HIGH_USAGE);
                    perDayHistory.add(perDay);
                }
            }

            boolean counted = previous != null && read.kind() != MeterReadKind.START;
            out.add(new ReadInterval(
                    read,
                    counted ? previous.readOn() : null,
                    counted ? previous.reading() : null,
                    setting.unit(),
                    usage,
                    baseUsage,
                    List.copyOf(flags)));
            previous = read;
        }
        return out;
    }

    private static boolean isHigh(BigDecimal perDay, List<BigDecimal> history) {
        if (history.size() < MIN_HISTORY) return false;
        List<BigDecimal> recent = history.subList(Math.max(0, history.size() - HISTORY), history.size());
        BigDecimal average = recent.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(recent.size()), MeterUnit.BASE_SCALE, RoundingMode.HALF_UP);
        return average.signum() > 0 && perDay.compareTo(average.multiply(HIGH_USAGE_FACTOR)) > 0;
    }

    /**
     * The usage between two reads of one meter, adding up every interval after
     * {@code fromReadId} up to and including {@code toReadId}. Null when either
     * read is missing, they are in the wrong order, or any interval in between
     * cannot be counted (BACKWARDS, a START, no previous read).
     */
    public static BigDecimal baseUsageBetween(List<ReadInterval> chain, UUID fromReadId, UUID toReadId) {
        int from = -1;
        int to = -1;
        for (int i = 0; i < chain.size(); i++) {
            UUID id = chain.get(i).read().uuid();
            if (id.equals(fromReadId)) from = i;
            if (id.equals(toReadId)) to = i;
        }
        if (from < 0 || to <= from) return null;

        BigDecimal total = BigDecimal.ZERO;
        for (int i = from + 1; i <= to; i++) {
            BigDecimal step = chain.get(i).baseUsage();
            if (step == null) return null;
            total = total.add(step);
        }
        return total;
    }
}
