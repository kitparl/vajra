# Shared image for the FastAPI services. Build from the repo root:
#   docker build -f services/python.Dockerfile --build-arg SERVICE=shankha --build-arg PACKAGE=signal-service \
#     --build-arg MODULE=signal_service --build-arg PORT=8091 .
# SERVICE = folder under services/, PACKAGE = uv project name, MODULE = Python import name.
FROM python:3.14.10-slim-trixie
COPY --from=ghcr.io/astral-sh/uv:0.13.9 /uv /usr/local/bin/uv
ARG SERVICE
ARG PACKAGE
ARG MODULE
ARG PORT
ENV SERVICE=${SERVICE} MODULE=${MODULE} PORT=${PORT} UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PROJECT_ENVIRONMENT=/venv PATH=/venv/bin:$PATH
WORKDIR /src
COPY contracts contracts
COPY services/pyproject.toml services/uv.lock services/.python-version services/
COPY services/common-py services/common-py
COPY services/shankha services/shankha
COPY services/manthan services/manthan
COPY services/strategies-py services/strategies-py
RUN --mount=type=cache,target=/root/.cache/uv \
    cd services && uv sync --frozen --no-dev --no-editable --package ${PACKAGE}
RUN useradd --system app
USER app
WORKDIR /src/services/${SERVICE}
# Migrations run once as a separate step before the server starts (0016), never inside the app.
CMD ["sh", "-c", "alembic -c alembic.ini upgrade head && exec uvicorn ${MODULE}.main:create_app --factory --host 0.0.0.0 --port ${PORT}"]
