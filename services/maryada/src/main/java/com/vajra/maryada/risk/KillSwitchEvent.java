package com.vajra.maryada.risk;

import java.time.Instant;

public record KillSwitchEvent(long id, String action, String reason, Boolean flattened, Instant at) {}
