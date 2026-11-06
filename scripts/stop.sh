#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -f .local/server.pid ]] || exit 0
server_pid="$(cat .local/server.pid)"
[[ "$server_pid" =~ ^[0-9]+$ ]] || exit 1
process_args="$(ps -p "$server_pid" -o args= || true)"
if [[ "$process_args" != 'java -jar ruoyi-admin/target/ruoyi-admin.jar'* ]]; then
  exit 0
fi
kill "$server_pid"
for attempt in {1..100}; do
  if ! kill -0 "$server_pid" 2>/dev/null; then exit 0; fi
  sleep 0.1
done
echo 'Java 尚未完成正常退出，请检查本地日志。' >&2
exit 1
