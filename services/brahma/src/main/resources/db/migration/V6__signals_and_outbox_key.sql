alter table orders add column signal_id uuid;
create unique index orders_signal_id_uq on orders (signal_id) where signal_id is not null;
alter table audit_event add column event_key text;
