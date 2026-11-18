#!/usr/bin/env sh
set -eu

PROFILE="${YANO_PROFILE:-preprod}"
DEFAULT_NETWORK_CONFIG_DIR="/app/default-config/network"
NETWORK_CONFIG_DIR="/app/config/network"

# Copies the directories and regular files of $1 that $2 lacks into $2, recursively, never replacing an existing
# file. Plain sh with no find(1): the Amazon Linux 2023 Corretto base image has no findutils. The subshell body
# keeps each level's variables apart.
seed_tree() (
  mkdir -p "$2"
  for entry in "$1"/* "$1"/.[!.]* "$1"/..?*; do
    name="${entry##*/}"
    if [ -L "$entry" ]; then
      continue
    elif [ -d "$entry" ]; then
      seed_tree "$entry" "$2/$name"
    elif [ -f "$entry" ] && [ ! -e "$2/$name" ]; then
      cp -p "$entry" "$2/$name"
    fi
  done
)

seed_network_config() {
  if [ ! -d "$DEFAULT_NETWORK_CONFIG_DIR" ]; then
    return
  fi

  seed_tree "$DEFAULT_NETWORK_CONFIG_DIR" "$NETWORK_CONFIG_DIR"
}

seed_network_config

# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -Dquarkus.profile="${PROFILE}" -jar /app/yano.jar ${YANO_EXTRA_ARGS:-} "$@"
