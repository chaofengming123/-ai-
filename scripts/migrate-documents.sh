#!/bin/sh
set -eu
case "${1:-preview}" in
  preview|apply) mode=${1:-preview} ;;
  *) echo 'Usage: scripts/migrate-documents.sh [preview|apply]' >&2; exit 2 ;;
esac
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
# 独立随机端口；处理完即退出，不占用 IDEA 的 8080。
exec "$PROJECT_DIR/scripts/backend.sh" spring-boot:run "-Dspring-boot.run.arguments=--server.port=0 --app.documents.backend=minio --app.documents.migration=$mode"
