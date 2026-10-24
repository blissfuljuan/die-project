# syntax=docker/dockerfile:1
# RabbitMQ for Render (private service + persistent disk at /var/lib/rabbitmq). Build context = repository root.
# Compose mounts these same files instead of baking them in; the content is identical.
FROM rabbitmq:3.13-management-alpine@sha256:606d8c0d6b3c18d1da9afc53bc7cdb2a8d5486df91b5a9830e9e07626c9ae281

# The data directory is named after the node, and the node name defaults to the container hostname, which Render changes on
# every deploy. A fixed node name keeps the persistent disk usable after a redeploy (single node, so "localhost" is enough).
ENV RABBITMQ_NODENAME=rabbit@localhost

COPY infra/rabbitmq/rabbitmq.conf /etc/rabbitmq/conf.d/20-die.conf
COPY infra/rabbitmq/definitions.json /etc/rabbitmq/definitions.json
COPY infra/rabbitmq/die-entrypoint.sh /usr/local/bin/die-entrypoint.sh

EXPOSE 5672
# The Blueprint env group supplies RABBITMQ_USER / RABBITMQ_PASSWORD; die-entrypoint.sh maps them to the RABBITMQ_DEFAULT_* names the
# stock entrypoint reads. They seed the user on the first start with an EMPTY disk only; changing the password later needs
# `rabbitmqctl change_password` (docs/ops/recovery.md).
ENTRYPOINT ["sh", "/usr/local/bin/die-entrypoint.sh"]
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=5   CMD rabbitmq-diagnostics -q ping || exit 1
