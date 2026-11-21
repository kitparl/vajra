-- Order management, paper positions and audit outbox (Phase 2 slice 2). Sizes are whole contracts.
-- Money columns are unscaled numeric; positions' average entry price can carry up to 34 significant digits.
create table orders (
    id               uuid        primary key,
    client_order_id  text        not null,
    idempotency_key  text        not null,
    request_hash     text        not null,
    venue            text        not null,
    instrument_id    bigint      not null references instrument (id),
    side             text        not null,
    type             text        not null,
    size             bigint      not null,
    limit_price      numeric,
    time_in_force    text        not null,
    reduce_only      boolean     not null,
    state            text        not null,
    filled_size      bigint      not null,
    avg_fill_price   numeric,
    fees             numeric     not null,
    venue_order_id   text,
    source           text        not null,
    reason           text,
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    version          bigint      not null,
    unique (client_order_id)
);
create index orders_state_created_idx on orders (state, created_at desc);
create index orders_created_idx on orders (created_at desc);

create table order_transition (
    id         bigserial   primary key,
    order_id   uuid        not null references orders (id),
    from_state text,
    to_state   text        not null,
    reason     text,
    at         timestamptz not null
);
create index order_transition_order_idx on order_transition (order_id, id);

create table fill (
    id            uuid        primary key,
    order_id      uuid        not null references orders (id),
    venue         text        not null,
    venue_fill_id text        not null,
    price         numeric     not null,
    size          bigint      not null,
    fee           numeric     not null,
    liquidity     text,
    at            timestamptz not null,
    unique (venue, venue_fill_id)
);
create index fill_order_idx on fill (order_id, at);

create table position (
    venue           text        not null,
    instrument_id   bigint      not null references instrument (id),
    size            bigint      not null,
    avg_entry_price numeric     not null,
    realized_pnl    numeric     not null,
    fees_paid       numeric     not null,
    updated_at      timestamptz not null,
    version         bigint      not null,
    primary key (venue, instrument_id)
);

-- Outbox: every signal/order/fill/position change is recorded here in the caller's transaction.
create table audit_event (
    id           uuid        primary key,
    event_type   text        not null,
    payload      bytea       not null,
    occurred_at  timestamptz not null,
    published_at timestamptz
);
create index audit_event_unpublished_idx on audit_event (occurred_at) where published_at is null;
