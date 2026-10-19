#!/bin/sh
# Starts RabbitMQ with the stock entrypoint (which seeds the default user from RABBITMQ_DEFAULT_USER/PASS) and then
# imports the project topology (exchanges, queues, bindings) from definitions.json.
#
# Why not `load_definitions` in rabbitmq.conf: a node that loads definitions at boot does NOT seed the default user
# ("Will not seed default virtual host and user: have definitions to load"), and the password cannot live in a static file.
# Importing after boot keeps the credentials in the environment and the topology in the repository. The import is
# idempotent, so it runs on every start.
set -eu

# Platforms that share one broker login between services (Render env group) provide RABBITMQ_USER / RABBITMQ_PASSWORD, the names
# the API and the workers use; the stock entrypoint wants RABBITMQ_DEFAULT_USER / _PASS (compose sets those directly).
if [ -z "${RABBITMQ_DEFAULT_USER:-}" ] && [ -n "${RABBITMQ_USER:-}" ]; then export RABBITMQ_DEFAULT_USER="$RABBITMQ_USER"; fi
if [ -z "${RABBITMQ_DEFAULT_PASS:-}" ] && [ -n "${RABBITMQ_PASSWORD:-}" ]; then export RABBITMQ_DEFAULT_PASS="$RABBITMQ_PASSWORD"; fi

docker-entrypoint.sh rabbitmq-server &
server=$!
trap 'kill -TERM "$server"; wait "$server"; exit $?' TERM INT

# rabbitmqctl must run as the broker's own user: as root it can create a root-owned Erlang cookie while the node is still
# booting, which crashes the node. (su-exec is part of the official alpine image.)
ctl() {
    su-exec rabbitmq "$@"
}

# The node reports "running" slightly before it accepts a definitions import, so retry instead of failing the container.
attempt=0
until error=$(ctl rabbitmqctl -q import_definitions /etc/rabbitmq/definitions.json 2>&1); do
    attempt=$((attempt + 1))
    if ! kill -0 "$server" 2>/dev/null; then
        echo "die: RabbitMQ stopped before the topology could be imported" >&2
        exit 1
    fi
    if [ "$attempt" -ge 90 ]; then
        echo "die: topology import failed after $attempt attempts: $error" >&2
        kill -TERM "$server"
        exit 1
    fi
    sleep 1
done
echo "die: topology imported from definitions.json (attempt $((attempt + 1)))"

wait "$server"
