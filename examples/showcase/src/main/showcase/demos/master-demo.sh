#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd -P)"
INSTANCE="${1:-master-demo}"
AUTO="${SHOWCASE_DEMO_AUTO:-false}"
pause() { [ "$AUTO" = true ] || { printf '\nPress Return to continue: %s\n' "$1"; read -r _; }; }

"$HERE/showcase.sh" doctor
pause "set up the three-node showcase with the app-chain setup commands, then the composite/anchor quickstart"
"$HERE/showcase.sh" quickstart --instance "$INSTANCE" --nodes 3
pause "inspect the sealed applications and where each node keeps its files"
"$HERE/showcase.sh" config show --instance "$INSTANCE"
"$HERE/showcase.sh" config paths --instance "$INSTANCE"
pause "exercise every lightweight stock scenario"
"$HERE/showcase.sh" run orders --instance "$INSTANCE"
"$HERE/showcase.sh" run registry --instance "$INSTANCE"
"$HERE/showcase.sh" run authenticated-map --instance "$INSTANCE"
"$HERE/showcase.sh" run approvals --instance "$INSTANCE"
"$HERE/showcase.sh" run balances --instance "$INSTANCE"
"$HERE/showcase.sh" run documents --instance "$INSTANCE"
"$HERE/showcase.sh" run composite --instance "$INSTANCE"
"$HERE/showcase.sh" run roles --instance "$INSTANCE"
"$HERE/showcase.sh" run eutxo --instance "$INSTANCE"
pause "add node 3: every node approves its card (2-of-3 becomes 3-of-4) and every chain activates it"
"$HERE/showcase.sh" member join 3 --instance "$INSTANCE"
"$HERE/showcase.sh" config show --instance "$INSTANCE"
"$HERE/showcase.sh" verify all --instance "$INSTANCE"
pause "restart retained state and the governed joiner"
"$HERE/showcase.sh" restart --instance "$INSTANCE"
"$HERE/showcase.sh" verify all --instance "$INSTANCE"
"$HERE/showcase.sh" ui --instance "$INSTANCE"
printf '\nDemo complete. Stop without deleting data:\n  ./showcase.sh stop --instance %s\n' "$INSTANCE"
