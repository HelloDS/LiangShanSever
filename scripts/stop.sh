#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -f .local/server.pid ]] || exit 0
server_pid="$(cat .local/server.pid)"
[[ "$server_pid" =~ ^[0-9]+$ ]] || exit 1
if ! kill -0 "$server_pid" 2>/dev/null; then exit 0; fi
if ! python3 scripts/server-process.py "$server_pid"; then
  echo '登记的 PID 不符合本项目 Java 命令及工作目录，未发送停止信号。' >&2
  exit 1
fi
kill "$server_pid"
for attempt in {1..300}; do
  if ! kill -0 "$server_pid" 2>/dev/null; then exit 0; fi
  sleep 0.1
done
echo 'Java 尚未完成正常退出，请检查本地日志。' >&2
exit 1
