package io.github.lordship.tenancyterms;

import java.math.BigDecimal;
import java.time.LocalDate;

// One dated rate in a scheduled lease.
public record RentStep(LocalDate validAt, BigDecimal rate) {}