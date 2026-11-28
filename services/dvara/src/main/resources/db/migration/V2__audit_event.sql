-- Login/logout audit trail (owned by gateway_svc). Never store passwords in detail.
CREATE TABLE audit_event (
    id          BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    kind        TEXT        NOT NULL,
    detail      JSONB       NOT NULL
);
CREATE INDEX audit_event_occurred_at_idx ON audit_event (occurred_at);
