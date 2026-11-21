-- Instrument master (Phase 2 slice 1). One row per venue product; sizes are whole contracts.
create table instrument (
    id               bigserial primary key,
    venue            text        not null,
    symbol           text        not null,
    venue_product_id bigint      not null,
    contract_type    text        not null,
    contract_value   numeric     not null,
    tick_size        numeric     not null,
    min_size         numeric     not null,
    maker_fee        numeric     not null,
    taker_fee        numeric     not null,
    trading_enabled  boolean     not null,
    updated_at       timestamptz not null,
    unique (venue, symbol)
);
