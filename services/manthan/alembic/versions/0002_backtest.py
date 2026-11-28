"""backtest_run and backtest_trade: stored backtest results.

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
        create table research.backtest_run (
            run_id     uuid primary key,
            strategy   text not null,
            params     jsonb not null,
            metrics    jsonb not null,
            created_at timestamptz not null default now()
        )
        """
    )
    op.execute(
        """
        create table research.backtest_trade (
            run_id        uuid not null references research.backtest_run (run_id),
            seq           int not null,
            side          text not null,
            entry_time    timestamptz not null,
            entry_price   numeric not null,
            exit_time     timestamptz not null,
            exit_price    numeric not null,
            fees          numeric not null,
            pnl           numeric not null,
            closed_at_end boolean not null,
            primary key (run_id, seq)
        )
        """
    )


def downgrade() -> None:
    op.execute("drop table research.backtest_trade")
    op.execute("drop table research.backtest_run")
