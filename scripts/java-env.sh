#!/usr/bin/env bash
set -euo pipefail
if [[ -x /usr/libexec/java_home ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  export JAVA_HOME
fi
export PATH="${JAVA_HOME:?请配置 Java 21 的 JAVA_HOME}/bin:$PATH"
