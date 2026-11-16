# Yano X app-chain commands (ADR-064 D8): the JVM distribution adds these to Yano's launcher.
appchain_usage() {
    cat <<'EOF'
Usage: ./yano.sh appchain <command> [args...]

App-chain commands come from the app-chain tooling in the Yano X
distribution, which was not found here. Install the release-matched Yano X
JVM distribution; advanced users may set YANO_APPCHAIN_CLI.
EOF
}

appchain_cli() {
    local configured="${YANO_APPCHAIN_CLI:-}"
    local candidate

    if [ -n "$configured" ]; then
        case "$configured" in
            /*) ;;
            *) configured="$CALLER_DIR/$configured" ;;
        esac
        if [ ! -x "$configured" ]; then
            echo "Error: YANO_APPCHAIN_CLI is not executable: $configured" >&2
            exit 1
        fi
        printf '%s\n' "$configured"
        return
    fi

    candidate="$YANO_ROOT/tools/yano-appchain/bin/yano-appchain"
    if [ ! -x "$candidate" ]; then
        echo "Error: This app-chain command requires version-matched app-chain tooling." >&2
        echo "Install the release-matched Yano X JVM distribution/tooling archive." >&2
        echo "Advanced users may set YANO_APPCHAIN_CLI." >&2
        exit 1
    fi
    printf '%s\n' "$candidate"
}

# The tooling owns the command list, so help always matches the installed
# commands; without the tooling, explain where the commands come from. An
# explicit YANO_APPCHAIN_CLI that cannot run reports its own error.
appchain_help() {
    local cli
    if [ -n "${YANO_APPCHAIN_CLI:-}" ]; then
        cli="$(appchain_cli)" || exit 1
        cd "$CALLER_DIR"
        exec "$cli" help
    fi
    if cli="$(appchain_cli 2>/dev/null)"; then
        cd "$CALLER_DIR"
        exec "$cli" help
    fi
    appchain_usage
    exit 0
}

dispatch_appchain() {
    shift
    if [ "$#" -eq 0 ]; then
        appchain_help
    fi
    case "$1" in
        help|-h|--help)
            appchain_help
            ;;
    esac
    local cli
    cli="$(appchain_cli)"
    cd "$CALLER_DIR"
    exec "$cli" "$@"
}
