package com.vajra.riskservice.killswitch;

import com.vajra.riskservice.api.ApiException;
import com.vajra.riskservice.risk.KillSwitchEvent;
import com.vajra.riskservice.risk.KillSwitchRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Kill-switch. Halted is committed before core is called and no transaction is open during the core call, so
 * approvals are rejected even if core hangs. Any core failure leaves the system halted.
 */
@Service
public class KillSwitchService {

    private static final Logger log = LoggerFactory.getLogger(KillSwitchService.class);

    private final KillSwitchRepository repository;
    private final CoreClient core;
    private final TransactionTemplate tx;
    private final Clock clock;
    /** risk-service is single-instance; the lock orders the core calls of kill and resume. */
    private final ReentrantLock lock = new ReentrantLock();

    public KillSwitchService(KillSwitchRepository repository, CoreClient core, TransactionTemplate tx, Clock clock) {
        this.repository = repository;
        this.core = core;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Every press halts and is audited immediately (before any lock), then finishes the core call under the lock.
     * Safe to call repeatedly.
     */
    public KillSwitchStatus kill(String reason) {
        Instant epoch = clock.instant().truncatedTo(ChronoUnit.MICROS);
        tx.executeWithoutResult(s -> {
            repository.setHalted(true, epoch);
            repository.addEvent("KILL", reason, null, epoch);
        });
        boolean flattened;
        String detail;
        lock.lock();
        try {
            if (supersededByResume(epoch)) {
                return new KillSwitchStatus(false, null, "superseded by a later resume");
            }
            try {
                CoreClient.KillResult result = core.kill(reason);
                flattened = result.flattened();
                detail = "cancelled " + result.cancelledOrders() + " orders, flatten orders " + result.flattenOrders();
            } catch (RuntimeException e) {
                log.error("core kill failed; staying halted", e);
                flattened = false;
                detail = "core unreachable; halted, flatten not confirmed";
            }
            repository.addEvent("FLATTEN", reason, flattened, clock.instant());
        } finally {
            lock.unlock();
        }
        return new KillSwitchStatus(repository.halted(), flattened, detail);
    }

    /** A resume that got the lock after this kill committed (but before it locked) already undid this halt. */
    private boolean supersededByResume(Instant epoch) {
        return !repository.halted() && !repository.updatedAt().equals(epoch);
    }

    /**
     * Core is resumed first; if it cannot be reached the system stays halted. A kill pressed meanwhile moves the
     * epoch, so the compare-and-set fails and the last kill wins.
     */
    public KillSwitchStatus resume(String reason) {
        lock.lock();
        try {
            Instant epoch = repository.updatedAt();
            try {
                core.resume();
            } catch (RuntimeException e) {
                log.error("core resume failed; staying halted", e);
                throw ApiException.unavailable("core unreachable; still halted");
            }
            boolean cleared = Boolean.TRUE.equals(tx.execute(s -> {
                if (!repository.clearHaltedIf(epoch, clock.instant())) {
                    return false;
                }
                repository.addEvent("RESUME", reason, null, clock.instant());
                return true;
            }));
            if (!cleared) {
                throw ApiException.conflict("kill pressed during resume; still halted");
            }
            return new KillSwitchStatus(false, null, "resumed");
        } finally {
            lock.unlock();
        }
    }

    public KillSwitchStatus status() {
        return new KillSwitchStatus(repository.halted(), null, null);
    }

    public List<KillSwitchEvent> recentEvents(int limit) {
        return repository.events(limit);
    }
}
