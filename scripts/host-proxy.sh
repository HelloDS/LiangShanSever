#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
proxy_root="$(pwd)/.local/nginx-native"
proxy_config="$(pwd)/deploy/nginx-host.conf"
proxy_binary="$(command -v nginx)"
mkdir -p "$proxy_root/logs" "$proxy_root/tmp"
case "${1:-status}" in
  start)
    ulimit -n 16384
    "$proxy_binary" -t -p "$proxy_root/" -c "$proxy_config"
    if [[ -f "$proxy_root/nginx.pid" ]] && kill -0 "$(cat "$proxy_root/nginx.pid")" 2>/dev/null; then
      echo '本机代理已在运行'; exit 0
    fi
    "$proxy_binary" -p "$proxy_root/" -c "$proxy_config"
    printf '%s\n' "$(ulimit -n)" > "$proxy_root/nofile"
    echo '本机 Nginx 已启动，仅监听 127.0.0.1:8080'
    ;;
  stop) "$proxy_binary" -s quit -p "$proxy_root/" -c "$proxy_config" ;;
  status)
    if [[ -f "$proxy_root/nginx.pid" ]] && kill -0 "$(cat "$proxy_root/nginx.pid")" 2>/dev/null; then echo '本机代理运行中'; else echo '本机代理未运行'; exit 1; fi
    ;;
  *) echo '用法：host-proxy.sh start|stop|status' >&2; exit 1 ;;
esac
