#!/usr/bin/env bash
set -euo pipefail
if [[ -d /opt/homebrew/opt/node@24/bin ]]; then export PATH="/opt/homebrew/opt/node@24/bin:$PATH"; fi
if command -v corepack >/dev/null 2>&1; then exec corepack pnpm "$@"; fi
PNPM_LAUNCHER="$(command -v pnpm)"
if [[ "$PNPM_LAUNCHER" == */codex-primary-runtime/dependencies/bin/fallback/pnpm ]]; then
  exec node "$(dirname "$PNPM_LAUNCHER")/../../node/node_modules/pnpm/bin/pnpm.mjs" "$@"
fi
exec pnpm "$@"
