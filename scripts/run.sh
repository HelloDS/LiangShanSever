#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/java-env.sh
set -a
source .local/local.env
set +a
printf '%s\n' "$$" > .local/server.pid
exec java -jar ruoyi-admin/target/ruoyi-admin.jar "$@"
