#!/usr/bin/env bash
set -euo pipefail
if [[ -d /opt/homebrew/opt/node@24/bin ]]; then export PATH="/opt/homebrew/opt/node@24/bin:$PATH"; fi
exec npx --yes --package=pnpm@11.25.0 pnpm "$@"
