#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/java-env.sh
source scripts/docker-env.sh
if [[ -d /opt/homebrew/opt/node@24/bin ]]; then export PATH="/opt/homebrew/opt/node@24/bin:$PATH"; fi
(cd admin-ui && ../scripts/pnpm.sh install --frozen-lockfile && ../scripts/pnpm.sh typecheck && ../scripts/pnpm.sh build:prod)
mkdir -p ruoyi-admin/src/main/resources/static/admin
cp -R admin-ui/dist/. ruoyi-admin/src/main/resources/static/admin/
./mvnw -B package
