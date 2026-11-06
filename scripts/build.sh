#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/java-env.sh
source scripts/docker-env.sh
if [[ -d /opt/homebrew/opt/node@24/bin ]]; then export PATH="/opt/homebrew/opt/node@24/bin:$PATH"; fi
(cd admin-ui && ../scripts/pnpm.sh install --frozen-lockfile && ../scripts/pnpm.sh typecheck && ../scripts/pnpm.sh build:prod)
test -f admin-ui/dist/index.html
# 只清理已忽略的生成目录，避免历史 Vite 哈希资源残留到新 JAR。
rm -rf ruoyi-admin/src/main/resources/static/admin ruoyi-admin/target/classes/static/admin
mkdir -p ruoyi-admin/src/main/resources/static/admin
cp -R admin-ui/dist/. ruoyi-admin/src/main/resources/static/admin/
./mvnw -B package
