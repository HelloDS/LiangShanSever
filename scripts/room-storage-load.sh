#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ "${RUN_ROOM_STORAGE_LOAD:-}" == '1' ]] || { echo '需显式设置 RUN_ROOM_STORAGE_LOAD=1' >&2; exit 1; }
source scripts/java-env.sh
source scripts/docker-env.sh
mkdir -p .local
if [[ -n "${ROOM_LOAD_FROZEN_SNAPSHOT:-}" ]]; then
  load_snapshot="$(python3 scripts/prepare-room-load.py --verify "$ROOM_LOAD_FROZEN_SNAPSHOT")"
else
  ./mvnw -B test-compile > .local/room-storage-load-compile.log 2>&1
  load_snapshot="$(python3 scripts/prepare-room-load.py)"
fi
export ROOM_LOAD_GIT="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["sourceCommit"])' "$load_snapshot/manifest.json")"
export ROOM_LOAD_SNAPSHOT_SHA256="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["sha256"])' "$load_snapshot/manifest.json")"
export ROOM_LOAD_DOCKER_ENV="$(python3 -c 'import subprocess;print(subprocess.check_output(["docker","info","--format","{{.NCPU}} {{.MemTotal}} {{.ServerVersion}}"],text=True,timeout=3).strip())')"
load_classpath="$(cat "$load_snapshot/classpath.txt")"
load_java_options=(-Xms256m -Xmx1024m)
if [[ "${ROOM_LOAD_JFR:-}" == '1' ]]; then
  umask 077
  load_java_options+=("-XX:StartFlightRecording=filename=$load_snapshot/profile.jfr,settings=profile,maxsize=256m,dumponexit=true")
fi
exec java "${load_java_options[@]}" -cp "$load_classpath" com.ruoyi.RoomStorageLoad
