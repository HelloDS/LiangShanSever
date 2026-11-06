#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/java-env.sh
source scripts/docker-env.sh
exec ./mvnw -B test "$@"
