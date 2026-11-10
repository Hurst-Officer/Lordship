package io.github.lordship.meterbills;

/**
 * What a bill line is for, and so which rate it joins. Water and sewer are
 * passed on by water usage; a fee billed through the water bill (an ambulance
 * fee, a base charge) is a WATER line. Trash never has a meter.
 */
public enum UtilityService {
    WATER, SEWER, POWER, TRASH
}
