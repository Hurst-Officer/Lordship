package io.github.lordship.meterbilling;

import io.github.lordship.audit.AuditService;
import io.github.lordship.meterbills.BillDetail;
import io.github.lordship.meterbills.UtilityBill;
import io.github.lordship.meterbills.UtilityBillLine;
import io.github.lordship.meterbills.UtilityBillMeter;
import io.github.lordship.meterbills.UtilityBillService;
import io.github.lordship.meterbills.UtilityCycle;
import io.github.lordship.meterbills.UtilityCycleService;
import io.github.lordship.meterbills.UtilityService;
import io.github.lordship.meterbills.internal.UtilityBillRepository;
import io.github.lordship.meters.MeterMain;
import io.github.lordship.meters.MeterRead;
import io.github.lordship.meters.MeterReadKind;
import io.github.lordship.meters.MeterReadService;
import io.github.lordship.meters.MeterReadSource;
import io.github.lordship.meters.MeterService;
import io.github.lordship.meters.MeterSetting;
import io.github.lordship.meters.MeterType;
import io.github.lordship.meters.MeterUnit;
import io.github.lordship.meters.ReadChain;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.RuleConflict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class MeterBillsServiceTests {

    private UtilityBillRepository bills;
    private MeterService meterService;
    private MeterReadService readService;
    private UtilityBillService billService;

    private final UUID propertyId = UUID.randomUUID();
    private final UUID billId = UUID.randomUUID();
    private final UUID mainId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        bills = mock(UtilityBillRepository.class);
        meterService = mock(MeterService.class);
        readService = mock(MeterReadService.class);
        billService = new UtilityBillService(bills, mock(UtilityCycleService.class), meterService, readService,
                mock(AuditService.class));
    }

    private UtilityBill bill(BigDecimal printedTotal) {
        return new UtilityBill(billId, propertyId, null, "City", null, null, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 10, 1), printedTotal, null, null,
                OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    private UtilityBillLine line(String description, String amount, boolean passedOn) {
        return new UtilityBillLine(UUID.randomUUID(), billId, UtilityService.WATER, description, new BigDecimal(amount),
                null, null, passedOn, LocalDate.of(2026, 11, 1), 1, OffsetDateTime.now(), null);
    }

    private MeterMain waterMain() {
        return new MeterMain(mainId, propertyId, MeterType.WATER, null, "City main", null, null, null, null, null,
                null, null, null, null, null, null);
    }

    // ── The park's cycle decides the billing month ──

    private static UtilityCycle cycle(int months, int anchor, int lag) {
        return new UtilityCycle(UUID.randomUUID(), UUID.randomUUID(), null, UtilityService.WATER, months, anchor, lag, null,
                LocalDate.of(2026, 1, 1), null, null, null);
    }

    @Test
    void aMonthlyCycle_landsTheLagAfterTheServicePeriod() {
        // reads "09 to 10" billed in November... a period ending 15 Sept, two months on
        assertEquals(LocalDate.of(2026, 11, 1), UtilityCycleService.billingMonth(cycle(1, 1, 2), LocalDate.of(2026, 9, 15)));
    }

    @Test
    void aTwoMonthCycle_movesOnToTheNextChargeMonth() {
        // billed even months: a period ending in August with a lag of 1 lands in October, not September
        assertEquals(LocalDate.of(2026, 10, 1), UtilityCycleService.billingMonth(cycle(2, 2, 1), LocalDate.of(2026, 8, 20)));
        assertFalse(UtilityCycleService.carriesCharge(cycle(2, 2, 1), 9));
    }

    @Test
    void aThreeMonthCycle_crossesTheYear() {
        // charges in Jan, Apr, Jul, Oct
        assertEquals(LocalDate.of(2027, 1, 1), UtilityCycleService.billingMonth(cycle(3, 1, 1), LocalDate.of(2026, 10, 31)));
    }

    // ── A bill's checks ──

    @Test
    void theLinesAreCheckedAgainstThePrintedTotal_andOnlyPassedOnLinesCountForTenants() {
        when(bills.findBill(billId)).thenReturn(Optional.of(bill(new BigDecimal("1848.44"))));
        when(bills.linesOf(billId, false)).thenReturn(List.of(
                line("Water usage", "1810.44", true),
                line("Ambulance fee", "25.00", true),
                line("Late fee", "13.00", false)));
        when(bills.metersOf(billId)).thenReturn(List.of());

        BillDetail detail = billService.find(billId).orElseThrow();

        assertEquals(0, new BigDecimal("1848.44").compareTo(detail.linesTotal()));
        assertEquals(0, new BigDecimal("1835.44").compareTo(detail.passedOnTotal()));
        assertTrue(detail.totalMatches());
    }

    @Test
    void createBill_refusesALookAlike_untilThePersonConfirms() {
        when(bills.lookAlikes(any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of(bill(null)));

        RuleConflict refused = assertThrows(RuleConflict.class, () -> billService.createBill(propertyId, null, "City",
                null, null, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 1), null, null, false));
        assertEquals("utility_bill.possible_duplicate", refused.problem().code());
        verify(bills, never()).insertBill(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anAmountWithMoreThanTwoDecimals_isRefused_notRounded() {
        InvalidRequest refused = assertThrows(InvalidRequest.class, () -> billService.createBill(propertyId, null, "City",
                null, null, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 1), new BigDecimal("12.345"), null, true));
        assertEquals("value.too_many_decimals", refused.problem().code());
    }

    @Test
    void addMeter_wantsBothReadsOrNeither() {
        when(bills.findBillForUpdate(billId)).thenReturn(Optional.of(bill(null)));
        when(meterService.findMain(mainId)).thenReturn(Optional.of(waterMain()));
        when(meterService.lockMain(mainId)).thenReturn(Optional.of(waterMain()));

        InvalidRequest refused = assertThrows(InvalidRequest.class, () -> billService.addMeter(billId, mainId,
                LocalDate.of(2026, 8, 1), 4410L, false, null, null, false, false, null, null, false));
        assertEquals("utility_bill.reads_in_pairs", refused.problem().code());
    }

    @Test
    void addMeter_passesOnARefusal_whenThePrintedReadDisagreesWithTheOneOnFile() {
        when(bills.findBillForUpdate(billId)).thenReturn(Optional.of(bill(null)));
        when(meterService.findMain(mainId)).thenReturn(Optional.of(waterMain()));
        when(meterService.lockMain(mainId)).thenReturn(Optional.of(waterMain()));
        when(readService.providerRead(any(), any(), any(), anyBoolean(), any()))
                .thenThrow(RuleConflict.of("meter_read.provider_differs", LocalDate.of(2026, 8, 1), 4400L, 4410L));

        RuleConflict refused = assertThrows(RuleConflict.class, () -> billService.addMeter(billId, mainId,
                LocalDate.of(2026, 8, 1), 4410L, false, LocalDate.of(2026, 10, 1), 4822L, false, false, null, null, false));
        assertEquals("meter_read.provider_differs", refused.problem().code());
        verify(bills, never()).insertMeter(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void addMeter_refusesPrintedUsageInAUnitThatDoesNotSuitTheMain() {
        when(bills.findBillForUpdate(billId)).thenReturn(Optional.of(bill(null)));
        when(meterService.findMain(mainId)).thenReturn(Optional.of(waterMain()));
        when(meterService.lockMain(mainId)).thenReturn(Optional.of(waterMain()));

        InvalidRequest refused = assertThrows(InvalidRequest.class, () -> billService.addMeter(billId, mainId,
                null, null, false, null, null, false, false, new BigDecimal("412"), MeterUnit.KWH, false));
        assertEquals("meter.unit_does_not_suit", refused.problem().code());
    }

    @Test
    void addMeter_toABillThatIsGone_findsNothing() {
        when(bills.findBillForUpdate(billId)).thenReturn(Optional.empty());

        assertTrue(billService.addMeter(billId, mainId, null, null, false, null, null, false, false,
                new BigDecimal("412"), MeterUnit.CCF, false).isEmpty());
    }

    // ── A bill's usage for a main can always be counted ──

    private static final LocalDate AUG = LocalDate.of(2026, 8, 1);
    private static final LocalDate OCT = LocalDate.of(2026, 10, 1);

    private MeterRead read(LocalDate day, long reading, MeterReadKind kind) {
        return new MeterRead(UUID.randomUUID(), mainId, null, day, reading, kind, MeterReadSource.PROVIDER, false,
                null, null, null, null);
    }

    // The bill's two reads as the read service would hand them back, and the main's history holding just those two
    private void theMainsReadsAre(MeterRead prior, MeterRead current) {
        when(bills.findBillForUpdate(billId)).thenReturn(Optional.of(bill(null)));
        when(meterService.findMain(mainId)).thenReturn(Optional.of(waterMain()));
        when(meterService.lockMain(mainId)).thenReturn(Optional.of(waterMain()));
        when(readService.providerRead(mainId, prior.readOn(), prior.reading(), false, null)).thenReturn(prior);
        when(readService.providerRead(mainId, current.readOn(), current.reading(), false, current.kind())).thenReturn(current);
        when(readService.history(mainId, null)).thenReturn(ReadChain.evaluate(List.of(prior, current), List.of(
                new MeterSetting(UUID.randomUUID(), mainId, null, propertyId, MeterType.WATER, null, MeterUnit.CCF,
                        BigDecimal.ONE, 99999L, null, null, null))));
    }

    @Test
    void addMeter_refusesReadsThatCannotBeCounted_whenNoUsageIsPrinted() {
        // the new read is lower and nobody said the dial went round: there is nothing for the rate to divide by
        theMainsReadsAre(read(AUG, 99950, MeterReadKind.READ), read(OCT, 30, MeterReadKind.READ));

        RuleConflict refused = assertThrows(RuleConflict.class, () -> billService.addMeter(billId, mainId,
                AUG, 99950L, false, OCT, 30L, false, false, null, null, false));
        assertEquals("utility_bill.reads_not_countable", refused.problem().code());
        verify(bills, never()).insertMeter(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void addMeter_countsARolloverMarkedOnTheBill_fromAnOldReadOfAnyKind() {
        // a new main: its START read is the first bill's old read, and the dial passed 99,999 during the bill
        MeterRead prior = read(AUG, 99950, MeterReadKind.START);
        MeterRead current = read(OCT, 30, MeterReadKind.ROLLOVER);
        theMainsReadsAre(prior, current);
        when(bills.insertMeter(billId, mainId, propertyId, prior.uuid(), current.uuid(), null, null))
                .thenReturn(new UtilityBillMeter(UUID.randomUUID(), billId, mainId, propertyId, prior.uuid(),
                        current.uuid(), null, null, OffsetDateTime.now(), null));

        assertTrue(billService.addMeter(billId, mainId,
                AUG, 99950L, false, OCT, 30L, false, true, null, null, false).isPresent());

        // the old read only lends its number (no kind asked of it); the new one is asked for as a rollover
        verify(readService).providerRead(mainId, AUG, 99950L, false, null);
        verify(readService).providerRead(mainId, OCT, 30L, false, MeterReadKind.ROLLOVER);
        verify(bills).insertMeter(billId, mainId, propertyId, prior.uuid(), current.uuid(), null, null);
    }
}
