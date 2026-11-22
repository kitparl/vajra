"""signal_log: every generated signal, kept until published to Kafka (outbox).

Revision ID: 0002
Revises: 0001
"""

from alembic import op

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        create table signal.signal_log (
            signal_id    uuid primary key,
            strategy     text not null,
            symbol       text not null,
            side         text not null,
            order_type   text not null,
            size         bigint not null check (size >= 1),
            generated_at timestamptz not null,
            published_at timestamptz
        )
        """
    )
    op.execute("create index signal_log_generated_at_idx on signal.signal_log (generated_at)")


def downgrade() -> None:
    op.execute("drop table signal.signal_log")
