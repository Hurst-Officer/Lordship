package io.github.lordship.meters;

/**
 * READ: an ordinary read.
 * ROLLOVER: the dial passed its largest number and started over since the read
 * before. A person says so; a lower READ is flagged as running backwards instead.
 * START: a meter's first read, or its first after a repair or reset. Usage is
 * never counted across it.
 */
public enum MeterReadKind {
    READ, ROLLOVER, START
}
