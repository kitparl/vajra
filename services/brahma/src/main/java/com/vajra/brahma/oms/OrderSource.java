package com.vajra.brahma.oms;

/** Who originated the order. */
public enum OrderSource {
    MANUAL,
    SIGNAL,
    /** Flatten order placed by the kill switch; reduce-only flattens are the only orders allowed while halted. */
    KILL_SWITCH
}
