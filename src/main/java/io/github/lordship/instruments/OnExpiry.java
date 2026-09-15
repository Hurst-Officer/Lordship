package io.github.lordship.instruments;

public enum OnExpiry {
    MONTH_TO_MONTH, AUTO_RENEW, TERMINATE
    // What THIS paper claims happens when its term runs out. Not the system's
    // renewal record -- a lease that says AUTO_RENEW still needs an instrument
    // to actually renew it.
}
