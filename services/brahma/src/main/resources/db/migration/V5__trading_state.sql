-- Local halt flag (Phase 2 slice 3): brahma refuses new orders while halted, even when risk-service is down.
-- Single row (id = 1); only kill-switch flatten orders pass while halted.
create table trading_state (
    id         smallint    primary key check (id = 1),
    halted     boolean     not null,
    reason     text,
    updated_at timestamptz not null
);
insert into trading_state (id, halted, reason, updated_at) values (1, false, null, now());

-- risk-service decision that approved or rejected the order (null when rejected before calling it).
alter table orders add column risk_decision_id uuid;
