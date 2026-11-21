-- 1-minute candles for trading-enabled instruments (Phase 2 slice 1). The open candle is overwritten until it closes.
create table candle_1m (
    instrument_id bigint      not null references instrument (id),
    open_time     timestamptz not null,
    open          numeric     not null,
    high          numeric     not null,
    low           numeric     not null,
    close         numeric     not null,
    volume        numeric     not null,
    primary key (instrument_id, open_time)
);
