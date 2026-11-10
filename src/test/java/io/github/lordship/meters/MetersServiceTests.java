package io.github.lordship.meters;

import io.github.lordship.audit.AuditService;
import io.github.lordship.meters.internal.MeterLinkRepository;
import io.github.lordship.meters.internal.MeterRepository;
import io.github.lordship.meters.internal.MeterSettingRepository;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.RuleConflict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class MetersServiceTests {

    private static final LocalDate JAN = LocalDate.of(2026, 1, 1);

    private MeterRepository meters;
    private MeterLinkRepository links;
    private MeterSettingRepository settings;
    private MeterService meterService;

    private final UUID propertyId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        meters = mock(MeterRepository.class);
        links = mock(MeterLinkRepository.class);
        settings = mock(MeterSettingRepository.class);
        meterService = new MeterService(meters, links, settings, mock(AuditService.class));
    }

    // ── Units ──

    @Test
    void hundredCubicFeet_isExactly748point051948Gallons() {
        assertEquals(new BigDecimal("748.051948"), MeterUnit.CCF.toBase(BigDecimal.ONE));
        assertEquals(new BigDecimal("1000.000000"), MeterUnit.KGAL.toBase(BigDecimal.ONE));
        assertTrue(MeterUnit.KWH.suits(MeterType.POWER));
        assertFalse(MeterUnit.GAL.suits(MeterType.POWER));
    }

    // ── Usage and warnings from a chain of reads ──

    private static List<MeterRead> reads(Object... kindDayReading) {
        List<MeterRead> out = new ArrayList<>();
        for (int i = 0; i < kindDayReading.length; i += 3) {
            out.add(new MeterRead(UUID.randomUUID(), null, UUID.randomUUID(),
                    JAN.plusDays((Integer) kindDayReading[i + 1]), ((Number) kindDayReading[i + 2]).longValue(),
                    (MeterReadKind) kindDayReading[i], MeterReadSource.STAFF, false, null, null, null, null));
        }
        return out;
    }

    private static MeterSetting setting(LocalDate from, MeterUnit unit, String multiplier, long dialMax) {
        return new MeterSetting(UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(), MeterType.WATER, from,
                unit, new BigDecimal(multiplier), dialMax, null, null, null);
    }

    private static List<MeterSetting> gallons(String multiplier) {
        return List.of(setting(null, MeterUnit.GAL, multiplier, 99999));
    }

    private static final MeterReadKind READ = MeterReadKind.READ;

    @Test
    void usage_isTheDialDifference_timesTheMultiplier_inGallons() {
        // a lot meter counting tens of gallons, as on the rent rolls: (new - old) x 10
        List<ReadInterval> chain = ReadChain.evaluate(reads(READ, 0, 1200, READ, 30, 1350), gallons("10"));

        assertEquals(List.of(ReadFlag.NO_PREVIOUS), chain.get(0).flags());
        assertNull(chain.get(0).usage());
        assertEquals(0, new BigDecimal("1500").compareTo(chain.get(1).baseUsage()));
        assertTrue(chain.get(1).flags().isEmpty());
    }

    @Test
    void aLowerRead_isFlaggedBackwards_andCountsNothing() {
        List<ReadInterval> chain = ReadChain.evaluate(reads(READ, 0, 1200, READ, 30, 1100), gallons("1"));

        assertEquals(List.of(ReadFlag.BACKWARDS), chain.get(1).flags());
        assertNull(chain.get(1).baseUsage());
    }

    @Test
    void aConfirmedRollover_countsPastTheTopOfTheDial() {
        // 99,950 → 0 → 30 is 50 + 30 = 80
        List<ReadInterval> chain = ReadChain.evaluate(
                reads(READ, 0, 99950, MeterReadKind.ROLLOVER, 30, 30), gallons("1"));

        assertEquals(0, new BigDecimal("80").compareTo(chain.get(1).baseUsage()));
        assertTrue(chain.get(1).flags().isEmpty());
    }

    @Test
    void aStart_neverCountsAcrossAReplacedMeter() {
        List<ReadInterval> chain = ReadChain.evaluate(
                reads(READ, 0, 5000, MeterReadKind.START, 10, 3, READ, 40, 103), gallons("1"));

        assertNull(chain.get(1).usage());
        assertTrue(chain.get(1).flags().isEmpty());
        assertEquals(0, new BigDecimal("100").compareTo(chain.get(2).baseUsage()));
    }

    @Test
    void aMeterThatDidNotMove_andAJump_areFlagged() {
        List<ReadInterval> chain = ReadChain.evaluate(reads(
                READ, 0, 1000, READ, 30, 1100, READ, 60, 1200, READ, 90, 1300,
                READ, 120, 1300,   // stuck
                READ, 150, 2300),  // ten times the usual: a leak or a misread
                gallons("1"));

        assertEquals(List.of(ReadFlag.NO_CHANGE), chain.get(4).flags());
        assertTrue(chain.get(5).flags().contains(ReadFlag.HIGH_USAGE));
    }

    @Test
    void aSettingFromADayOn_leavesEarlierUsageAlone() {
        // the dial was really in tens of gallons from day 40 on: the first interval keeps x1
        List<MeterSetting> dated = List.of(
                setting(null, MeterUnit.GAL, "1", 99999),
                setting(JAN.plusDays(40), MeterUnit.GAL, "10", 99999));
        List<ReadInterval> chain = ReadChain.evaluate(reads(READ, 0, 100, READ, 30, 150, READ, 60, 170), dated);

        assertEquals(0, new BigDecimal("50").compareTo(chain.get(1).baseUsage()));
        assertEquals(0, new BigDecimal("200").compareTo(chain.get(2).baseUsage()));
    }

    @Test
    void aRolloverFromAboveASmallerDial_countsNothing_neverNegative() {
        // read at 50,000 under a 99,999 dial; the dial is then set to 9,999 and a rollover is entered
        List<MeterSetting> dated = List.of(
                setting(null, MeterUnit.GAL, "1", 99999),
                setting(JAN.plusDays(10), MeterUnit.GAL, "1", 9999));
        List<ReadInterval> chain = ReadChain.evaluate(reads(READ, 0, 50000, MeterReadKind.ROLLOVER, 30, 100), dated);

        assertNull(chain.get(1).baseUsage());
        assertTrue(chain.get(1).flags().contains(ReadFlag.ABOVE_DIAL));
    }

    @Test
    void usageBetweenTwoReads_addsTheReadsInBetween_andIsNullAcrossABackwardsRead() {
        List<ReadInterval> chain = ReadChain.evaluate(reads(READ, 0, 100, READ, 30, 150, READ, 60, 220), gallons("1"));
        UUID first = chain.get(0).read().uuid();
        UUID last = chain.get(2).read().uuid();
        assertEquals(0, new BigDecimal("120").compareTo(ReadChain.baseUsageBetween(chain, first, last)));

        List<ReadInterval> broken = ReadChain.evaluate(reads(READ, 0, 100, READ, 30, 90, READ, 60, 220), gallons("1"));
        assertNull(ReadChain.baseUsageBetween(broken, broken.get(0).read().uuid(), broken.get(2).read().uuid()));
    }

    // ── The tree ──

    private MeterMain main(UUID id, MeterType type) {
        return new MeterMain(id, propertyId, type, null, "Main", null, null, null, null, null, null, null, null,
                OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    private Submeter submeter(UUID id, MeterType type) {
        return new Submeter(id, propertyId, type, null, null, null, null, null, null, null,
                OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    @Test
    void createMain_refusesAUnitThatDoesNotSuitTheUtility() {
        InvalidRequest refused = assertThrows(InvalidRequest.class, () -> meterService.createMain(
                propertyId, MeterType.POWER, MeterUnit.GAL, "PSE main", null, null, null, null, null, null));
        assertEquals("meter.unit_does_not_suit", refused.problem().code());
        verify(meters, never()).insertMain(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void addSetting_isRefused_whenABillAlreadyUsesReadsFromThatDayOn() {
        UUID mainId = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 9, 1);
        when(meters.findMainForUpdate(mainId)).thenReturn(Optional.of(main(mainId, MeterType.WATER)));
        when(settings.ofMain(mainId)).thenReturn(List.of(setting(null, MeterUnit.CCF, "1", 99999)));
        when(settings.billedReadBetween(mainId, day, null)).thenReturn(true);

        RuleConflict refused = assertThrows(RuleConflict.class, () -> meterService.addSetting(
                mainId, null, day, MeterUnit.CCF, BigDecimal.TEN, null, "dial counts tens"));
        assertEquals("meter_setting.covers_billed_reads", refused.problem().code());
        verify(settings, never()).insert(any(), any(), any(), any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void linkToMain_movesASubmeter_byEndingItsOpenLink() {
        UUID subId = UUID.randomUUID();
        UUID oldMain = UUID.randomUUID();
        UUID newMain = UUID.randomUUID();
        LocalDate moved = LocalDate.of(2026, 6, 1);
        SubmeterMainLink open = new SubmeterMainLink(UUID.randomUUID(), subId, oldMain, propertyId, MeterType.WATER,
                JAN, null, null, null);
        SubmeterMainLink ended = new SubmeterMainLink(open.uuid(), subId, oldMain, propertyId, MeterType.WATER,
                JAN, moved, null, null);

        when(meters.findSubmeterForUpdate(subId)).thenReturn(Optional.of(submeter(subId, MeterType.WATER)));
        when(meters.findMainForShare(newMain)).thenReturn(Optional.of(main(newMain, MeterType.WATER)));
        when(links.mainLinksOf(subId)).thenReturn(List.of(open));
        when(links.setMainLinkDates(open.uuid(), JAN, moved)).thenReturn(Optional.of(ended));
        when(links.insertMainLink(subId, newMain, propertyId, MeterType.WATER, moved))
                .thenReturn(new SubmeterMainLink(UUID.randomUUID(), subId, newMain, propertyId, MeterType.WATER, moved, null, null, null));

        SubmeterMainLink made = meterService.linkToMain(subId, newMain, moved);

        assertEquals(newMain, made.mainId());
        verify(links).setMainLinkDates(open.uuid(), JAN, moved);
    }

    @Test
    void linkToMain_refusesAMainOfAnotherUtility() {
        UUID subId = UUID.randomUUID();
        UUID powerMain = UUID.randomUUID();
        when(meters.findSubmeterForUpdate(subId)).thenReturn(Optional.of(submeter(subId, MeterType.WATER)));
        when(meters.findMainForShare(powerMain)).thenReturn(Optional.of(main(powerMain, MeterType.POWER)));

        InvalidRequest refused = assertThrows(InvalidRequest.class, () -> meterService.linkToMain(subId, powerMain, JAN));
        assertEquals("submeter.main_mismatch", refused.problem().code());
    }

    @Test
    void deleteMain_isRefused_whileSubmetersHangOffIt() {
        UUID mainId = UUID.randomUUID();
        when(meters.findMainForUpdate(mainId)).thenReturn(Optional.of(main(mainId, MeterType.WATER)));
        when(meters.mainHasOpenLinks(mainId)).thenReturn(true);

        RuleConflict refused = assertThrows(RuleConflict.class, () -> meterService.deleteMain(mainId));
        assertEquals("meter_main.has_submeters", refused.problem().code());
        verify(meters, never()).softDelete(eq(MeterRepository.MAIN), any());
    }
}
