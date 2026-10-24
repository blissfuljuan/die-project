# syntax=docker/dockerfile:1
# RENDER VARIANT of workers/document-analysis/Dockerfile: build context = repository root (Render cannot pass named build contexts).
# Keep it in sync with the original; CI builds both.
FROM python:3.12-slim@sha256:a6e34c598f2467ed0e9a8d349809fcd8b5c603269512df273a0bb1784edc11b1 AS build
WORKDIR /build
COPY workers/document-analysis/pyproject.toml .
COPY workers/document-analysis/src ./src
RUN pip install --no-cache-dir --prefix=/install .

FROM python:3.12-slim@sha256:a6e34c598f2467ed0e9a8d349809fcd8b5c603269512df273a0bb1784edc11b1
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 PIP_NO_CACHE_DIR=1 DIE_CONTRACTS_DIR=/app/contracts
# Non-root, no login shell, no home. The root filesystem is mounted read-only by compose (only /tmp is writable).
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin app
COPY --from=build /install /usr/local
COPY contracts/messaging/envelope-v1.schema.json /app/contracts/envelope-v1.schema.json
WORKDIR /app
USER 10001
EXPOSE 8082
HEALTHCHECK --interval=15s --timeout=5s --start-period=15s --retries=3 \
  CMD ["python", "-c", "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8082/health/ready', timeout=3).status == 200 else 1)"]
CMD ["python", "-m", "die_document_worker"]
