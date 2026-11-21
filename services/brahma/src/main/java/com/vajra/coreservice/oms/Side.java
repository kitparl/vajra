package com.vajra.coreservice.oms;

/** Order direction. */
public enum Side {
    BUY(1),
    SELL(-1);

    private final int sign;

    Side(int sign) {
        this.sign = sign;
    }

    /** +1 for BUY, -1 for SELL. */
    public int sign() {
        return sign;
    }
}
