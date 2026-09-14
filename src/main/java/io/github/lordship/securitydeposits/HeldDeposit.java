package io.github.lordship.securitydeposits;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A deposit still held, with the lot and tenancy context the office needs to
 * act on it. {@code daysSinceTenancyEnded} is null while the tenant is still
 * there; once it is a number, the clock is running.
 *
 * <p>Deliberately no statutory deadline and no "overdue" flag. Lordship
 * reports elapsed days and the operator applies their own window, for the
 * same reason notice periods are clause text rather than stored settings.
 */
public record HeldDeposit(
        UUID uuid,
        UUID tenancy,
        UUID lot,
        String lotNumber,
        BigDecimal amount,
        LocalDate collectedOn,
        LocalDate tenancyEndedOn,
        Integer daysSinceTenancyEnded) { }