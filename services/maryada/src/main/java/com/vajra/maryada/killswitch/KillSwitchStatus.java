package com.vajra.maryada.killswitch;

/** {@code flattened} is null when no kill/flatten outcome applies (status, resume). */
public record KillSwitchStatus(boolean halted, Boolean flattened, String detail) {}
