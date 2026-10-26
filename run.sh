#!/usr/bin/env bash
# Builds and starts MySQL, RabbitMQ and the API, then runs the test suite.
# The process exit code is the test exit code.
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  echo "Docker with Compose v2 is required." >&2
  exit 1
fi

docker compose up -d --build --wait
chmod +x mvnw
set +e
./mvnw -B test
status=$?
set -e
if [ "$status" -ne 0 ]; then
  echo "Tests failed. The API container is still up; logs: docker compose logs app" >&2
fi
exit "$status"
