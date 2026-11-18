#!/usr/bin/env bash
# Usage: require-java-version.sh <version> <minimum>
# Fails unless <version> (a Java version number such as 25.0.5.1, without the +build suffix) is at or above
# <minimum>. Components compare numerically, and a missing component counts as 0, so 25.0.5 < 25.0.5.1 < 25.0.6.
# The release workflow uses it to refuse a bundled Java runtime that lacks a security fix (CVE-2026-114938, fixed
# in 25.0.5.1).
set -euo pipefail

version=${1:-}
minimum=${2:?usage: require-java-version.sh <version> <minimum>}
if [[ ! "$version" =~ ^[0-9]+(\.[0-9]+)*$ ]]; then
  echo "::error::Not a Java version number: '$version' (minimum $minimum)" >&2
  exit 1
fi

IFS=. read -r -a have <<< "$version"
IFS=. read -r -a need <<< "$minimum"
count=$(( ${#have[@]} > ${#need[@]} ? ${#have[@]} : ${#need[@]} ))
for (( i = 0; i < count; i++ )); do
  h=$(( 10#${have[i]:-0} ))
  n=$(( 10#${need[i]:-0} ))
  if (( h > n )); then
    break
  fi
  if (( h < n )); then
    echo "::error::Java $version is older than $minimum, the first build with the required security fix" >&2
    exit 1
  fi
done
echo "Java $version is at or above $minimum"
