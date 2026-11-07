#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/java-env.sh
if [[ -f .local/server.pid ]]; then
  prior_server_pid="$(cat .local/server.pid)"
  if [[ "$prior_server_pid" =~ ^[1-9][0-9]*$ ]] && kill -0 "$prior_server_pid" 2>/dev/null; then
    echo '登记的服务进程仍在运行，请先使用 scripts/stop.sh 正常停止。' >&2
    exit 1
  fi
fi
set -a
source .local/local.env
set +a
runtime_jar="$(python3 scripts/runtime_artifact.py "$$")"
printf '%s\n' "$$" > .local/server.pid
exec java -jar "$runtime_jar" "$@"
