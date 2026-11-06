#!/usr/bin/env bash
# Testcontainers 使用 Docker endpoint；Docker CLI 的 context 不会自动传给 Java。
if [[ -z "${DOCKER_HOST:-}" && -S "$HOME/.colima/default/docker.sock" ]]; then
  export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
  export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
  export TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1
fi
