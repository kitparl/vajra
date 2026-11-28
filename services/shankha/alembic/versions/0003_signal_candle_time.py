"""signal_log.candle_time: the candle a strategy decided on, one signal per strategy+symbol+candle.

expired_at: outbox row that went stale before it could be published, never published.

Revision ID: 0003
Revises: 0002
"""

from alembic import op

revision = "0003"
down_revision = "0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("alter table signal.signal_log add column candle_time timestamptz, add column expired_at timestamptz")
    op.execute(
        "create unique index signal_log_candle_uidx on signal.signal_log (strategy, symbol, candle_time) "
        "where candle_time is not null"
    )


def downgrade() -> None:
    op.execute("drop index signal.signal_log_candle_uidx")
    op.execute("alter table signal.signal_log drop column candle_time, drop column expired_at")
