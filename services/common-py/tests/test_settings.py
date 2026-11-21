import pytest
from pydantic import ValidationError
from vajra_common.settings import DatabaseSettings


def test_missing_db_password_names_variable(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("DB_URL", "postgresql+psycopg://localhost:5432/vajra")
    monkeypatch.setenv("DB_USER", "signal_svc")
    monkeypatch.delenv("DB_PASSWORD", raising=False)

    with pytest.raises(ValidationError) as error:
        DatabaseSettings()

    assert "db_password" in str(error.value)


def test_reads_all_values_from_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("DB_URL", "postgresql+psycopg://localhost:5432/vajra")
    monkeypatch.setenv("DB_USER", "signal_svc")
    monkeypatch.setenv("DB_PASSWORD", "secret")

    settings = DatabaseSettings()

    assert settings.db_user == "signal_svc"
    assert settings.db_password.get_secret_value() == "secret"


@pytest.mark.parametrize("name", ["DB_URL", "DB_USER", "DB_PASSWORD"])
def test_empty_value_counts_as_missing(monkeypatch: pytest.MonkeyPatch, name: str) -> None:
    monkeypatch.setenv("DB_URL", "postgresql+psycopg://localhost:5432/vajra")
    monkeypatch.setenv("DB_USER", "signal_svc")
    monkeypatch.setenv("DB_PASSWORD", "secret")
    monkeypatch.setenv(name, "")

    with pytest.raises(ValidationError) as error:
        DatabaseSettings()

    assert name.lower() in str(error.value)
