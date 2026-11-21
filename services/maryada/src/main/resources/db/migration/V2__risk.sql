-- Risk limits, decision audit and kill-switch state (Phase 2 slice 3). Owned by risk_svc.
create table risk_limit (
    name       text        primary key,
    value      bigint      not null,
    updated_at timestamptz not null
);

insert into risk_limit (name, value, updated_at) values
    ('max_order_size', 1, now()),
    ('max_abs_position', 5, now()),
    ('max_orders_per_minute', 10, now());

create table risk_decision (
    id               uuid        primary key,
    client_order_id  text        not null,
    venue            text,
    symbol           text,
    side             text,
    size             bigint,
    order_type       text,
    limit_price      numeric,
    reduce_only      boolean,
    source           text,
    current_position bigint,
    approved         boolean     not null,
    reason           text,
    decided_at       timestamptz not null
);

create index risk_decision_approved_at_idx on risk_decision (decided_at) where approved;

create table kill_switch (
    id         smallint    primary key check (id = 1),
    halted     boolean     not null,
    updated_at timestamptz not null
);

insert into kill_switch (id, halted, updated_at) values (1, false, now());

create table kill_switch_event (
    id        bigserial   primary key,
    action    text        not null,
    reason    text,
    flattened boolean,
    at        timestamptz not null
);
