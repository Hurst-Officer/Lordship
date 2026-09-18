package io.github.lordship.tenancyterms;

import java.math.BigDecimal;

/**
 * The highest rent charged for one lot in one year, across whoever occupied it.
 *
 * <p>What the RCW 59.20 disclosure asks for. Using the highest rate rather than latest or average
 */
public record RentHistoryYear(int year, BigDecimal highestRate) {}
