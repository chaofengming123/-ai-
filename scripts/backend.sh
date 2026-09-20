#!/bin/sh
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ ! -f "$PROJECT_DIR/docker/.env" ]; then
  echo 'Run python3 scripts/init-db-env.py first.' >&2
  exit 1
fi
set -a
. "$PROJECT_DIR/docker/.env"
set +a
cd "$PROJECT_DIR/backend"
exec ./mvnw "$@"
