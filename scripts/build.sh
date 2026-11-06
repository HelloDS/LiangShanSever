#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/java-env.sh
source scripts/docker-env.sh
if [[ -d /opt/homebrew/opt/node@24/bin ]]; then export PATH="/opt/homebrew/opt/node@24/bin:$PATH"; fi
(cd admin-ui && ../scripts/pnpm.sh install --frozen-lockfile && ../scripts/pnpm.sh typecheck && ../scripts/pnpm.sh build:prod)
test -f admin-ui/dist/index.html
# 替换已忽略的生成目录，避免历史哈希资源残留；旧资源保存为本地备份。
python3 scripts/sync-admin-assets.py
./mvnw -B package
