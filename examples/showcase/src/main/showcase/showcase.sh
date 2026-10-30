#!/usr/bin/env bash
# Unified, copied-distribution-safe facade for the Yano app-chain showcase.
#
# The light profile is set up with the ordinary app-chain setup commands, as real operators would run them:
# `yano.sh appchain new --from config/showcase.template.yaml`, one `request` per node with a public demo
# member key, `seal`, `join`, `start`. Each node is a node directory under the instance. The two L1-driven
# chains are applications of their own, run by the founding nodes: Cardano History (`history`) and, on a
# devnet, the federated settlement chain (`settlement`).
set -euo pipefail

SHOWCASE_HOME="$(cd "$(dirname "$0")" && pwd -P)"
# The showcase ships at examples/showcase inside the Yano X JVM distribution; its scenarios call the
# distribution's yano.sh and read its plugins/.
YANO_HOME="$(cd "$SHOWCASE_HOME/../.." && pwd -P)"
CONFIG_DIR="$SHOWCASE_HOME/config"
TEMPLATE="$CONFIG_DIR/showcase.template.yaml"
SETTLEMENT_TEMPLATE="$CONFIG_DIR/settlement.template.yaml"
HISTORY_TEMPLATE="$CONFIG_DIR/history.template.yaml"
CODEC="$SHOWCASE_HOME/tools/showcase_codec.py"
LOCAL_CONFIG="$SHOWCASE_HOME/tools/showcase_local.py"
CATALOG="$SHOWCASE_HOME/catalog/showcase-catalog-v1.json"
CATALOG_TOOL="$SHOWCASE_HOME/tools/showcase_catalog.py"
VERSION="$(tr -d '\r\n' < "$SHOWCASE_HOME/VERSION" 2>/dev/null || printf development)"
APP=showcase
SETTLEMENT_APP=settlement
HISTORY_APP=history
CATALOG_CHAINS=()
while IFS= read -r chain_id; do CATALOG_CHAINS+=("$chain_id"); done \
  < <(python3 "$CATALOG_TOOL" "$CATALOG" list)
# The chains this instance runs: the catalog until an instance's sealed applications narrow it.
LIGHT_CHAINS=("${CATALOG_CHAINS[@]}")
SETTLEMENT_CHAIN_ID=payment-chain-settlement
CARDANO_HISTORY_CHAIN_ID=cardano-history-chain

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
note() { printf '%s\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"; }

PROFILE="light"
VARIANT="default"
INSTANCE="default"
NETWORK="devnet"
NODES=3
THRESHOLD=""
HTTP_BASE=7070
SERVER_BASE=13337
ANCHOR=false
ANCHOR_MODE="script"
ANCHOR_KEY_FILE=""
SETTLEMENT_KEY_FILE=""
SETTLEMENT_CONFIRM=""
# The chains `quickstart` and `anchor all` anchor. Every chain carries the anchor policy (mode and cadence);
# anchoring itself starts only with `./showcase.sh anchor`, which spends L1 fees.
ANCHOR_CHAINS="all"
ANCHOR_MODE_EXPLICIT=false
PUBLIC_CONFIRM=""
DATA_ROOT=""
NODE=0
COUNT=""
CONCURRENCY=20
PAYLOAD_SIZE=128
AUTHMAP_CHAIN="authenticated-map-chain"
AUTHMAP_COLLECTION="governed-catalog"
AUTHMAP_ACTOR="issuer-a"
AUTHMAP_SEED_FILE=""
DURATION=60
RATE=25
SAMPLE=5
SPREAD=false
REPORT_DIR=""
FINALIZED_INDEX_SELECTION=""
FINALIZED_INDEX_EXPLICIT=false
AUTHENTICATED_SNAPSHOT_SELECTION=""
AUTHENTICATED_SNAPSHOT_EXPLICIT=false
AUTHENTICATED_SNAPSHOT_PROFILE="mpf-blake2b256-v1"
AUTHENTICATED_SNAPSHOT_MPF_PRUNING_SELECTION=""
AUTHENTICATED_SNAPSHOT_MPF_PRUNING_EXPLICIT=false
CARDANO_HISTORY_PROFILE="params-only-v1"
CARDANO_HISTORY_PROFILE_EXPLICIT=false
CARDANO_HISTORY_ENABLED=true
CARDANO_HISTORY_ENABLED_EXPLICIT=false
L1_SOURCE_SNAPSHOT_RETENTION_EPOCHS=""
L1_SOURCE_SNAPSHOT_RETENTION_EXPLICIT=false
FOLLOW=false
YES=false
OUTPUT=""
POSITIONAL=()
EXPLICIT=""

parse() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --profile) PROFILE="${2:-}"; shift 2;;
      --variant) VARIANT="${2:-}"; shift 2;;
      --instance) INSTANCE="${2:-}"; shift 2;;
      --network) NETWORK="${2:-}"; EXPLICIT+=" network"; shift 2;;
      --nodes) NODES="${2:-}"; EXPLICIT+=" nodes"; shift 2;;
      --threshold) THRESHOLD="${2:-}"; EXPLICIT+=" threshold"; shift 2;;
      --http-base) HTTP_BASE="${2:-}"; EXPLICIT+=" httpBase"; shift 2;;
      --server-base) SERVER_BASE="${2:-}"; EXPLICIT+=" serverBase"; shift 2;;
      --anchor) ANCHOR=true; shift;;
      --anchor-mode) ANCHOR=true; ANCHOR_MODE="${2:-}"; ANCHOR_MODE_EXPLICIT=true; shift 2;;
      --anchor-key-file) ANCHOR=true; ANCHOR_KEY_FILE="${2:-}"; ANCHOR_KEY_EXPLICIT=true; shift 2;;
      --anchor-chain|--anchor-chains) ANCHOR=true; ANCHOR_CHAINS="${2:-}"; shift 2;;
      --confirm-public-anchor) PUBLIC_CONFIRM="${2:-}"; shift 2;;
      --settlement-key-file) SETTLEMENT_KEY_FILE="${2:-}"; shift 2;;
      --confirm-public-settlement) SETTLEMENT_CONFIRM="${2:-}"; shift 2;;
      --data-dir) DATA_ROOT="${2:-}"; shift 2;;
      --node) NODE="${2:-}"; shift 2;;
      --count) COUNT="${2:-}"; shift 2;;
      --concurrency) CONCURRENCY="${2:-}"; shift 2;;
      --payload-bytes) PAYLOAD_SIZE="${2:-}"; shift 2;;
      --duration) DURATION="${2:-}"; shift 2;;
      --rate) RATE="${2:-}"; shift 2;;
      --sample) SAMPLE="${2:-}"; shift 2;;
      --spread) SPREAD=true; shift;;
      --report-dir) REPORT_DIR="${2:-}"; shift 2;;
      --enable-finalized-message-index)
        FINALIZED_INDEX_SELECTION=all; FINALIZED_INDEX_EXPLICIT=true; shift;;
      --enable-finalized-message-index=*)
        FINALIZED_INDEX_SELECTION="${1#*=}"; FINALIZED_INDEX_EXPLICIT=true; shift;;
      --enable-authenticated-snapshots)
        if [ -n "${2:-}" ] && [[ "${2:-}" != --* ]]; then
          AUTHENTICATED_SNAPSHOT_SELECTION="$2"; shift 2
        else
          AUTHENTICATED_SNAPSHOT_SELECTION=all; shift
        fi
        AUTHENTICATED_SNAPSHOT_EXPLICIT=true;;
      --enable-authenticated-snapshots=*)
        AUTHENTICATED_SNAPSHOT_SELECTION="${1#*=}"; AUTHENTICATED_SNAPSHOT_EXPLICIT=true; shift;;
      --authenticated-snapshot-profile)
        AUTHENTICATED_SNAPSHOT_PROFILE="${2:-}"; shift 2;;
      --enable-authenticated-snapshot-mpf-pruning)
        if [ -n "${2:-}" ] && [[ "${2:-}" != --* ]]; then
          AUTHENTICATED_SNAPSHOT_MPF_PRUNING_SELECTION="$2"; shift 2
        else
          AUTHENTICATED_SNAPSHOT_MPF_PRUNING_SELECTION=all; shift
        fi
        AUTHENTICATED_SNAPSHOT_MPF_PRUNING_EXPLICIT=true;;
      --enable-authenticated-snapshot-mpf-pruning=*)
        AUTHENTICATED_SNAPSHOT_MPF_PRUNING_SELECTION="${1#*=}"
        AUTHENTICATED_SNAPSHOT_MPF_PRUNING_EXPLICIT=true; shift;;
      --cardano-history-profile)
        CARDANO_HISTORY_PROFILE="${2:-}"; CARDANO_HISTORY_PROFILE_EXPLICIT=true; shift 2;;
      --disable-cardano-history)
        CARDANO_HISTORY_ENABLED=false; CARDANO_HISTORY_ENABLED_EXPLICIT=true; shift;;
      --l1-source-snapshot-retention-epochs)
        L1_SOURCE_SNAPSHOT_RETENTION_EPOCHS="${2:-}"
        L1_SOURCE_SNAPSHOT_RETENTION_EXPLICIT=true; shift 2;;
      --chain) AUTHMAP_CHAIN="${2:-}"; shift 2;;
      --collection) AUTHMAP_COLLECTION="${2:-}"; shift 2;;
      --actor) AUTHMAP_ACTOR="${2:-}"; shift 2;;
      --seed-file) AUTHMAP_SEED_FILE="${2:-}"; shift 2;;
      --follow|-f) FOLLOW=true; shift;;
      --yes) YES=true; shift;;
      --output) OUTPUT="${2:-}"; shift 2;;
      --) shift; while [ "$#" -gt 0 ]; do POSITIONAL+=("$1"); shift; done;;
      *) POSITIONAL+=("$1"); shift;;
    esac
  done
}

chain_in_csv() {
  case ",${1}," in *",$2,"*) return 0;; *) return 1;; esac
}

canonical_anchor_chains() {
  local requested="$1" item cid seen="" result=""
  local -a values=()
  [ "$requested" != all ] || requested="$(IFS=,; printf '%s' "${LIGHT_CHAINS[*]}")"
  [ -n "$requested" ] || die "anchor chain scope cannot be empty"
  IFS=',' read -r -a values <<< "$requested"
  for item in "${values[@]}"; do
    [[ "$item" =~ ^[A-Za-z0-9._~-]{1,128}$ ]] || die "invalid anchor chain id: $item"
    chain_in_csv "$seen" "$item" && die "duplicate anchor chain: $item"
    seen+="${seen:+,}$item"
    local found=false
    for cid in "${LIGHT_CHAINS[@]}"; do [ "$cid" != "$item" ] || found=true; done
    [ "$found" = true ] || die "unknown showcase anchor chain: $item"
  done
  for cid in "${LIGHT_CHAINS[@]}"; do
    chain_in_csv "$seen" "$cid" && result+="${result:+,}$cid"
  done
  printf '%s' "$result"
}

canonical_finalized_index_chains() {
  local requested="$1" item cid seen="" result=""
  local -a values=()
  [ -n "$requested" ] || { printf ''; return; }
  [ "$requested" != all ] || requested="$(IFS=,; printf '%s' "${LIGHT_CHAINS[*]}")"
  [ -n "$requested" ] || die "finalized-message index scope cannot be empty"
  IFS=',' read -r -a values <<< "$requested"
  for item in "${values[@]}"; do
    [[ "$item" =~ ^[A-Za-z0-9._~-]{1,128}$ ]] \
      || die "invalid finalized-message index chain id: $item"
    [ "$item" != all ] || die "finalized-message index cannot mix all with chain ids"
    chain_in_csv "$seen" "$item" \
      && die "duplicate finalized-message index chain: $item"
    seen+="${seen:+,}$item"
    local found=false
    for cid in "${LIGHT_CHAINS[@]}"; do [ "$cid" != "$item" ] || found=true; done
    [ "$found" = true ] || die "unknown finalized-message index chain: $item"
  done
  for cid in "${LIGHT_CHAINS[@]}"; do
    chain_in_csv "$seen" "$cid" && result+="${result:+,}$cid"
  done
  printf '%s' "$result"
}

canonical_authenticated_snapshot_chains() {
  local requested="$1" item seen=""
  local -a values=()
  [ -n "$requested" ] || { printf ''; return; }
  [ "$requested" != all ] || requested="$CARDANO_HISTORY_CHAIN_ID"
  IFS=',' read -r -a values <<< "$requested"
  for item in "${values[@]}"; do
    [[ "$item" =~ ^[A-Za-z0-9._~-]{1,128}$ ]] \
      || die "invalid authenticated-snapshot chain id: $item"
    [ "$item" = "$CARDANO_HISTORY_CHAIN_ID" ] \
      || die "chain does not declare authenticated snapshot series: $item"
    chain_in_csv "$seen" "$item" \
      && die "duplicate authenticated-snapshot chain: $item"
    seen+="${seen:+,}$item"
  done
  printf '%s' "$seen"
}

validate_l1_source_snapshot_retention() {
  [ -z "$L1_SOURCE_SNAPSHOT_RETENTION_EPOCHS" ] || {
    [[ "$L1_SOURCE_SNAPSHOT_RETENTION_EPOCHS" =~ ^[0-9]+$ ]] \
      && [ "$L1_SOURCE_SNAPSHOT_RETENTION_EPOCHS" -ge 2 ] \
      && [ "$L1_SOURCE_SNAPSHOT_RETENTION_EPOCHS" -le 1000 ] \
      || die "--l1-source-snapshot-retention-epochs must be between 2 and 1000"
  }
}

validate_common() {
  [[ "$INSTANCE" =~ ^[a-z0-9][a-z0-9-]{0,31}$ ]] || die "invalid instance name"
  case "$PROFILE" in light|evidence|eutxo) ;; *) die "profile must be light, evidence, or eutxo";; esac
  case "$NETWORK" in devnet|preprod) ;; *) die "showcase supports devnet or preprod";; esac
  [[ "$NODES" =~ ^[0-9]+$ ]] && [ "$NODES" -ge 1 ] && [ "$NODES" -le 16 ] \
    || die "--nodes must be between 1 and 16"
  if [ -z "$THRESHOLD" ]; then THRESHOLD=$((NODES / 2 + 1)); fi
  [[ "$THRESHOLD" =~ ^[0-9]+$ ]] && [ "$((2 * THRESHOLD))" -gt "$NODES" ] && [ "$THRESHOLD" -le "$NODES" ] \
    || die "--threshold must be more than half of the bootstrap node count and at most all of them"
  if [ "$PROFILE" = evidence ]; then
    [ "$NODES" -eq 3 ] && [ "$THRESHOLD" -eq 2 ] \
      || die "the maintained evidence profile uses exactly 3 nodes with threshold 2"
  fi
  if [ "$PROFILE" = eutxo ] && [ "$NETWORK" != devnet ]; then
    die "the maintained EUTxO showcase variants are devnet-only"
  fi
  if [ "$NETWORK" = preprod ] && [ "$ANCHOR" = true ]; then
    [ "$PUBLIC_CONFIRM" = preprod ] \
      || die "preprod anchoring requires --confirm-public-anchor preprod"
    [ -n "$ANCHOR_KEY_FILE" ] || die "preprod anchoring requires --anchor-key-file"
  fi
  case "$ANCHOR_MODE" in script|metadata) ;; *) die "anchor mode must be script or metadata";; esac
  [ "$FINALIZED_INDEX_EXPLICIT" != true ] || [ -n "$FINALIZED_INDEX_SELECTION" ] \
    || die "finalized-message index selection cannot be empty"
  FINALIZED_INDEX_SELECTION="$(canonical_finalized_index_chains \
    "$FINALIZED_INDEX_SELECTION")"
  case "$AUTHENTICATED_SNAPSHOT_PROFILE" in
    mpf-blake2b256-v1|jmt-blake2b256-v1) ;;
    *) die "authenticated snapshot profile must be mpf-blake2b256-v1 or jmt-blake2b256-v1";;
  esac
  case "$CARDANO_HISTORY_PROFILE" in
    params-only|params-only-v1) CARDANO_HISTORY_PROFILE=params-only-v1;;
    params-stake|params-stake-v1) CARDANO_HISTORY_PROFILE=params-stake-v1;;
    params-governance|params-governance-v1) CARDANO_HISTORY_PROFILE=params-governance-v1;;
    full|full-v1) CARDANO_HISTORY_PROFILE=full-v1;;
    *) die "Cardano History profile must be params-only, params-stake, params-governance, or full";;
  esac
  validate_l1_source_snapshot_retention
  # Large Cardano History datasets always use one logical authenticated
  # snapshot per dataset epoch. The flag remains useful for generic/custom
  # chains, but a released stake/governance preset cannot silently fall back
  # to the ever-growing primary-map layout.
  if [ "$CARDANO_HISTORY_ENABLED" = true ] \
      && [ "$CARDANO_HISTORY_PROFILE" != params-only-v1 ]; then
    AUTHENTICATED_SNAPSHOT_SELECTION="$CARDANO_HISTORY_CHAIN_ID"
  fi
  if [ "$CARDANO_HISTORY_ENABLED" != true ]; then
    [ "$CARDANO_HISTORY_PROFILE_EXPLICIT" != true ] \
      || die "--disable-cardano-history cannot be combined with --cardano-history-profile"
    [ -z "$AUTHENTICATED_SNAPSHOT_SELECTION" ] \
      || die "authenticated snapshots require Cardano History"
  fi
  AUTHENTICATED_SNAPSHOT_SELECTION="$(canonical_authenticated_snapshot_chains \
    "$AUTHENTICATED_SNAPSHOT_SELECTION")"
  AUTHENTICATED_SNAPSHOT_MPF_PRUNING_SELECTION="$(canonical_authenticated_snapshot_chains \
    "$AUTHENTICATED_SNAPSHOT_MPF_PRUNING_SELECTION")"
  if [ -n "$AUTHENTICATED_SNAPSHOT_SELECTION" ] \
      && [ "$CARDANO_HISTORY_PROFILE" = params-only-v1 ]; then
    die "authenticated snapshots require a Cardano History stake or governance profile"
  fi
  if [ -n "$AUTHENTICATED_SNAPSHOT_MPF_PRUNING_SELECTION" ]; then
    [ "$AUTHENTICATED_SNAPSHOT_PROFILE" = mpf-blake2b256-v1 ] \
      || die "authenticated snapshot MPF pruning requires mpf-blake2b256-v1"
    chain_in_csv "$AUTHENTICATED_SNAPSHOT_SELECTION" "$CARDANO_HISTORY_CHAIN_ID" \
      || die "authenticated snapshot MPF pruning requires snapshots on cardano-history-chain"
  fi
}

# --- instance layout ----------------------------------------------------------------------------------------

instance_root() {
  if [ -n "$DATA_ROOT" ]; then
    case "$DATA_ROOT" in /*) printf '%s/%s' "${DATA_ROOT%/}" "$INSTANCE";;
      *) die "--data-dir must be absolute";;
    esac
  else
    printf '%s/data/showcase/%s' "$SHOWCASE_HOME" "$INSTANCE"
  fi
}
instance_file() { printf '%s/instance.json' "$(instance_root)"; }
coordinator_dir() { printf '%s/coordinator' "$(instance_root)"; }
settlement_dir() { printf '%s/settlement' "$(instance_root)"; }
history_dir() { printf '%s/history' "$(instance_root)"; }
node_dir() { printf '%s/node%d' "$(instance_root)" "$1"; }
outbox_dir() { printf '%s/outbox' "$(instance_root)"; }
joined_file() { printf '%s/joined-nodes' "$(instance_root)"; }
sealed_file() { printf '%s/%s.chain' "$(coordinator_dir)" "$APP"; }
settlement_sealed_file() { printf '%s/%s.chain' "$(settlement_dir)" "$SETTLEMENT_APP"; }
history_sealed_file() { printf '%s/%s.chain' "$(history_dir)" "$HISTORY_APP"; }
# The applications this instance has sealed, as "<app> <sealed file>" lines.
sealed_apps() {
  local app
  for app in "$APP" "$SETTLEMENT_APP" "$HISTORY_APP"; do
    case "$app" in
      "$APP") [ ! -f "$(sealed_file)" ] || printf '%s %s\n' "$app" "$(sealed_file)";;
      "$SETTLEMENT_APP") [ ! -f "$(settlement_sealed_file)" ] || printf '%s %s\n' "$app" "$(settlement_sealed_file)";;
      "$HISTORY_APP") [ ! -f "$(history_sealed_file)" ] || printf '%s %s\n' "$app" "$(history_sealed_file)";;
    esac
  done
}
# The application a chain belongs to.
chain_app() {
  case "$1" in "$SETTLEMENT_CHAIN_ID") printf '%s' "$SETTLEMENT_APP";;
    "$CARDANO_HISTORY_CHAIN_ID") printf '%s' "$HISTORY_APP";; *) printf '%s' "$APP";; esac
}
# The settlement executor's node-local settings: the packaged devnet ones, or those an adopted public-network
# bootstrap wrote.
settlement_executor_file() {
  if [ -f "$(settlement_dir)/executor.json" ]; then printf '%s/executor.json' "$(settlement_dir)"
  else printf '%s/settlement/executor.json' "$CONFIG_DIR"; fi
}

appchain() { "$YANO_HOME/yano.sh" appchain "$@"; }

# This node's API key, for privileged calls only (snapshot administration, member lists, effects). It is
# read from the node directory when needed and never printed.
api_key() { tr -d '\r\n' < "$(node_dir "${1:-$NODE}")/secrets/api.key"; }

# Every node index the instance runs: the founding nodes, then any joined later.
node_indexes() {
  local i
  for ((i=0;i<NODES;i++)); do printf '%d\n' "$i"; done
  [ ! -f "$(joined_file)" ] || cat "$(joined_file)"
}

# The nodes that run an application: every node for the showcase, the founding members for the others.
app_nodes() {
  local sealed
  case "$1" in
    "$APP") node_indexes;;
    "$SETTLEMENT_APP") sealed="$(settlement_sealed_file)";;
    "$HISTORY_APP") sealed="$(history_sealed_file)";;
  esac
  [ -z "${sealed:-}" ] || [ ! -f "$sealed" ] \
    || seq 0 $(( $(jq '.genesis.members | length' "$sealed") - 1 ))
}

# The settlement application exists only on a devnet with the three founding members its vault commits to.
settlement_included() {
  [ -f "$(settlement_sealed_file)" ] && return 0
  [ "$NETWORK" = devnet ] && [ "$NODES" -ge 3 ]
}

plugin_file() {
  local matches=("$YANO_HOME"/plugins/yano-x-showcase-bundle-*.jar)
  [ "${#matches[@]}" -eq 1 ] && [ -f "${matches[0]}" ] \
    || die "expected exactly one showcase plugin bundle"
  printf '%s' "${matches[0]}"
}

cardano_history_plugin_file() {
  local matches=("$YANO_HOME"/plugins/yano-x-cardano-history-bundle-*.jar)
  [ "${#matches[@]}" -eq 1 ] && [ -f "${matches[0]}" ] \
    || die "expected exactly one Cardano History product bundle"
  printf '%s' "${matches[0]}"
}

# --- instance options -----------------------------------------------------------------------------------------

write_instance() {
  local file="$(instance_file)" tmp
  tmp="$(mktemp "$(instance_root)/.instance.XXXXXX")"
  jq -n --arg version "$VERSION" --arg network "$NETWORK" --argjson nodes "$NODES" \
    --argjson threshold "$THRESHOLD" --argjson httpBase "$HTTP_BASE" --argjson serverBase "$SERVER_BASE" \
    --argjson historyEnabled "$CARDANO_HISTORY_ENABLED" --arg historyProfile "$CARDANO_HISTORY_PROFILE" \
    --arg finalized "$FINALIZED_INDEX_SELECTION" --arg snapshots "$AUTHENTICATED_SNAPSHOT_SELECTION" \
    --arg snapshotProfile "$AUTHENTICATED_SNAPSHOT_PROFILE" \
    --arg pruning "$AUTHENTICATED_SNAPSHOT_MPF_PRUNING_SELECTION" \
    --arg retention "$L1_SOURCE_SNAPSHOT_RETENTION_EPOCHS" --arg anchorMode "$ANCHOR_MODE" \
    --arg anchorChains "$ANCHOR_CHAINS" --arg anchorKey "$ANCHOR_KEY_FILE" '
    def list: if . == "" then [] else split(",") end;
    {schema: "yano-x-showcase-instance-v1", version: $version, profile: "light", network: $network,
     nodes: $nodes, threshold: $threshold, httpBase: $httpBase, serverBase: $serverBase,
     cardanoHistory: {enabled: $historyEnabled, profile: $historyProfile},
     finalizedMessageIndex: ($finalized | list),
     authenticatedSnapshots: {chainIds: ($snapshots | list), profile: $snapshotProfile,
                              mpfPruningChainIds: ($pruning | list)},
     l1SourceSnapshotRetentionEpochs: $retention,
     anchor: {mode: $anchorMode, chains: $anchorChains, keyFile: $anchorKey}}' > "$tmp"
  chmod 600 "$tmp"
  mv -f -- "$tmp" "$file"
}

instance_value() { jq -r "$1" "$(instance_file)"; }

# Adopts a prepared instance's options. An option given again must equal the retained one: the sealed
# application cannot change, so a different choice needs a new --instance (or reset).
adopt_instance() {
  local root="$(instance_root)" value
  if [ ! -f "$(instance_file)" ]; then
    [ ! -f "$root/showcase-identity.json" ] \
      || die "instance '$INSTANCE' was made by an earlier showcase; remove it with reset --yes or use another --instance"
    return 0
  fi
  PROFILE=light
  local field current
  for field in network nodes threshold httpBase serverBase; do
    case " $EXPLICIT " in *" $field "*) ;; *) continue;; esac
    case "$field" in network) current="$NETWORK";; nodes) current="$NODES";; threshold) current="$THRESHOLD";;
      httpBase) current="$HTTP_BASE";; serverBase) current="$SERVER_BASE";; esac
    [ "$current" = "$(instance_value ".$field")" ] \
      || die "requested $field differs from the retained instance ($(instance_value ".$field")); use a new instance or reset"
  done
  NETWORK="$(instance_value .network)"
  NODES="$(instance_value .nodes)"
  THRESHOLD="$(instance_value .threshold)"
  HTTP_BASE="$(instance_value .httpBase)"
  SERVER_BASE="$(instance_value .serverBase)"
  value="$(instance_value .cardanoHistory.enabled)"
  [ "$CARDANO_HISTORY_ENABLED_EXPLICIT" != true ] || [ "$CARDANO_HISTORY_ENABLED" = "$value" ] \
    || die "requested Cardano History enablement differs from the retained instance; use a new instance or reset"
  CARDANO_HISTORY_ENABLED="$value"
  value="$(instance_value .cardanoHistory.profile)"
  [ "$CARDANO_HISTORY_PROFILE_EXPLICIT" != true ] || [ "$CARDANO_HISTORY_PROFILE" = "$value" ] \
    || die "requested Cardano History profile differs from the retained instance; use a new instance or reset"
  CARDANO_HISTORY_PROFILE="$value"
  value="$(instance_value '.finalizedMessageIndex | join(",")')"
  [ "$FINALIZED_INDEX_EXPLICIT" != true ] || [ "$FINALIZED_INDEX_SELECTION" = "$value" ] \
    || die "requested finalized-message index scope differs from the retained instance; use a new instance or reset"
  FINALIZED_INDEX_SELECTION="$value"
  value="$(instance_value '.authenticatedSnapshots.chainIds | join(",")')"
  if [ "$AUTHENTICATED_SNAPSHOT_EXPLICIT" = true ] && { [ "$AUTHENTICATED_SNAPSHOT_SELECTION" != "$value" ] \
      || [ "$AUTHENTICATED_SNAPSHOT_PROFILE" != "$(instance_value .authenticatedSnapshots.profile)" ]; }; then
    die "requested authenticated-snapshot profile differs from the retained instance; use a new instance or reset"
  fi
  AUTHENTICATED_SNAPSHOT_SELECTION="$value"
  AUTHENTICATED_SNAPSHOT_PROFILE="$(instance_value .authenticatedSnapshots.profile)"
  value="$(instance_value '.authenticatedSnapshots.mpfPruningChainIds | join(",")')"
  [ "$AUTHENTICATED_SNAPSHOT_MPF_PRUNING_EXPLICIT" != true ] \
    || [ "$AUTHENTICATED_SNAPSHOT_MPF_PRUNING_SELECTION" = "$value" ] \
    || die "requested authenticated-snapshot MPF pruning scope differs from the retained instance; use a new instance or reset"
  AUTHENTICATED_SNAPSHOT_MPF_PRUNING_SELECTION="$value"
  value="$(instance_value .l1SourceSnapshotRetentionEpochs)"
  [ "$L1_SOURCE_SNAPSHOT_RETENTION_EXPLICIT" != true ] || [ "$L1_SOURCE_SNAPSHOT_RETENTION_EPOCHS" = "$value" ] \
    || die "requested L1 source snapshot retention differs from the retained instance"
  L1_SOURCE_SNAPSHOT_RETENTION_EPOCHS="$value"
  value="$(instance_value .anchor.mode)"
  [ "$ANCHOR_MODE_EXPLICIT" != true ] || [ "$ANCHOR_MODE" = "$value" ] \
    || die "the retained anchor mode is '$value' (sealed with the application) and cannot be changed"
  ANCHOR_MODE="$value"
  [ -n "$ANCHOR_KEY_FILE" ] || ANCHOR_KEY_FILE="$(instance_value .anchor.keyFile)"
  [ "$ANCHOR_CHAINS" != all ] || ANCHOR_CHAINS="$(instance_value .anchor.chains)"
  load_instance_chains
}

# The chains this instance runs, in catalog order.
load_instance_chains() {
  local sealed cid
  local -a present=() kept=()
  for sealed in "$(sealed_file)" "$(settlement_sealed_file)" "$(history_sealed_file)"; do
    [ -f "$sealed" ] || continue
    while IFS= read -r cid; do present+=("$cid"); done < <(jq -r '.genesis.chains[].id' "$sealed")
  done
  [ "${#present[@]}" -gt 0 ] || return 0
  for cid in "${LIGHT_CHAINS[@]}"; do
    case " ${present[*]} " in *" $cid "*) kept+=("$cid");; esac
  done
  LIGHT_CHAINS=("${kept[@]}")
}

# Showcase options become ordinary draft adjustments: `new --setting <chain>.<key>=<value>`.
draft_options() {
  local app="$1" cid every=2 index="$CARDANO_HISTORY_CHAIN_ID"
  DRAFT_OPTIONS=()
  [ "$NETWORK" = devnet ] || every=30
  # The catalog, not the chains sealed so far: an interrupted prepare still drafts every application in full.
  for cid in "${CATALOG_CHAINS[@]}"; do
    [ "$(chain_app "$cid")" = "$app" ] || continue
    # The anchor policy is sealed; `./showcase.sh anchor` turns anchoring on later.
    DRAFT_OPTIONS+=(--setting "$cid.anchor.mode=$ANCHOR_MODE" --setting "$cid.anchor.every-blocks=$every")
    # ordered-log already owns this intrinsic state index and keeps its ALL policy.
    if chain_in_csv "$FINALIZED_INDEX_SELECTION" "$cid" && [ "$cid" != orders-chain ]; then
      DRAFT_OPTIONS+=(--setting "$cid.machines.finalized-message-index.enabled=true"
        --setting "$cid.machines.finalized-message-index.policy=APPLICATION_ONLY"
        --setting "$cid.machines.finalized-message-index.max-messages-per-block=1000")
    fi
  done
  [ "$app" = "$HISTORY_APP" ] || return 0
  DRAFT_OPTIONS+=(--setting "$index.machines.cardano-history.preset=$CARDANO_HISTORY_PROFILE")
  case "$CARDANO_HISTORY_PROFILE" in
    params-stake-v1|full-v1)
      DRAFT_OPTIONS+=(--setting "$index.machines.epoch-stake.chunk-entries=25000"
        --setting "$index.machines.epoch-stake.snapshot-profile=$AUTHENTICATED_SNAPSHOT_PROFILE"
        --setting "$index.observers.epoch-stake.type=l1-epoch-stake-v1"
        --setting "$index.observers.epoch-stake.chain-id=$CARDANO_HISTORY_CHAIN_ID"
        --setting "$index.observers.epoch-stake.chunk-entries=25000");;
  esac
  case "$CARDANO_HISTORY_PROFILE" in
    params-governance-v1|full-v1)
      DRAFT_OPTIONS+=(--setting "$index.machines.epoch-governance.drep-chunk-entries=25000"
        --setting "$index.machines.epoch-governance.drep-snapshot-profile=$AUTHENTICATED_SNAPSHOT_PROFILE"
        --setting "$index.observers.epoch-governance.type=l1-epoch-governance-v1"
        --setting "$index.observers.epoch-governance.chain-id=$CARDANO_HISTORY_CHAIN_ID"
        --setting "$index.observers.epoch-governance.include-proposals=true"
        --setting "$index.observers.epoch-governance.include-drep-distribution=true"
        --setting "$index.observers.epoch-governance.drep-chunk-entries=25000");;
  esac
  if chain_in_csv "$AUTHENTICATED_SNAPSHOT_SELECTION" "$CARDANO_HISTORY_CHAIN_ID"; then
    local series=""
    case "$CARDANO_HISTORY_PROFILE" in
      params-stake-v1) series="l1-epoch-stake-v1.distribution";;
      params-governance-v1) series="l1-epoch-governance-v1.drep-distribution";;
      full-v1) series="l1-epoch-stake-v1.distribution,l1-epoch-governance-v1.drep-distribution";;
    esac
    DRAFT_OPTIONS+=(--setting "$index.capabilities.authenticated-snapshots.enabled=true"
      --setting "$index.capabilities.authenticated-snapshots.series=$series")
  fi
}

# --- setup through the app-chain commands ---------------------------------------------------------------------

# Node i's member key: the public demo seed byte(i+1) x 32. The authenticated-map, approval and settlement
# demos rely on these well-known members; never use them for anything but this showcase.
demo_member_seed() {
  local byte i
  byte="$(printf '%02x' $(( $1 + 1 )))"
  for ((i=0;i<32;i++)); do printf '%s' "$byte"; done
  printf '\n'
}

# `request` for node i: its node directory gets the demo member key; the card lands in <dir>.
request_member() {
  local source="$1" index="$2" dir="$3" seed
  seed="$(mktemp "$(instance_root)/.member-seed.XXXXXX")"
  chmod 600 "$seed"
  demo_member_seed "$index" > "$seed"
  appchain request "$source" --name "node$index" --host 127.0.0.1 \
    --node-port "$((SERVER_BASE + index))" --http-port "$((HTTP_BASE + index))" \
    --node-dir "$(node_dir "$index")" --key "$seed" --output "$dir" --yes >/dev/null \
    || { rm -f -- "$seed"; die "request failed for node$index"; }
  rm -f -- "$seed"
}

# new (unless drafted) -> request per node -> seal -> join per node. Every step is repeatable.
setup_app() {
  local app="$1" template="$2" dir="$3" count="$4" threshold="$5" i operators="" draft chain fingerprint
  shift 5
  draft="$dir/$app.draft.yaml"
  chain="$dir/$app.chain"
  mkdir -p "$dir"
  if [ ! -f "$chain" ]; then
    for ((i=0;i<count;i++)); do operators+="${operators:+,}node$i"; done
    [ -f "$draft" ] || appchain new "$app" --from "$template" --network "$NETWORK" --operators "$operators" \
      --threshold "$threshold" --output "$dir" --yes "$@" >/dev/null || die "could not create $draft"
    for ((i=0;i<count;i++)); do
      [ -f "$dir/node$i.member" ] || request_member "$draft" "$i" "$dir"
    done
    appchain seal "$draft" >/dev/null || die "could not seal $draft"
  fi
  fingerprint="$(appchain inspect "$chain" --json | jq -r .displayFingerprint)"
  for ((i=0;i<count;i++)); do
    appchain join "$chain" --expect-fingerprint "$fingerprint" --node-dir "$(node_dir "$i")" --yes >/dev/null \
      || die "node$i could not join $app"
  done
}

# config/local.yaml: what only this node runs. The outbox executor and the settlement executor's ownership
# stay on node 0; Cardano History snapshot archives and the L1 snapshot retention are local storage.
write_local_config() {
  local index="$1" args=()
  args=(--node "$index" --node-dir "$(node_dir "$index")" --outbox "$(outbox_dir)" --config-dir "$CONFIG_DIR")
  [ -z "$L1_SOURCE_SNAPSHOT_RETENTION_EPOCHS" ] \
    || args+=(--snapshot-retention-epochs "$L1_SOURCE_SNAPSHOT_RETENTION_EPOCHS")
  ! chain_in_csv "$AUTHENTICATED_SNAPSHOT_SELECTION" "$CARDANO_HISTORY_CHAIN_ID" \
    || args+=(--snapshot-archives "$CARDANO_HISTORY_CHAIN_ID")
  ! chain_in_csv "$AUTHENTICATED_SNAPSHOT_MPF_PRUNING_SELECTION" "$CARDANO_HISTORY_CHAIN_ID" \
    || args+=(--snapshot-mpf-pruning "$CARDANO_HISTORY_CHAIN_ID")
  [ ! -f "$(settlement_sealed_file)" ] || args+=(--settlement-executor "$(settlement_executor_file)")
  python3 "$LOCAL_CONFIG" "${args[@]}"
}

prepare_light() {
  need java; need python3; need jq; need curl
  local root="$(instance_root)" index
  if [ -e "$root" ] && [ -L "$root" ]; then die "instance root must not be a symlink"; fi
  mkdir -p "$root" "$(outbox_dir)"; chmod 700 "$root" "$(outbox_dir)"
  if [ -f "$(instance_file)" ]; then adopt_instance; else
    [ ! -f "$root/showcase-identity.json" ] \
      || die "instance '$INSTANCE' was made by an earlier showcase; remove it with reset --yes or use another --instance"
    write_instance
  fi
  draft_options "$APP"
  setup_app "$APP" "$TEMPLATE" "$(coordinator_dir)" "$NODES" "$THRESHOLD" ${DRAFT_OPTIONS[@]+"${DRAFT_OPTIONS[@]}"}
  if [ "$CARDANO_HISTORY_ENABLED" = true ]; then
    draft_options "$HISTORY_APP"
    setup_app "$HISTORY_APP" "$HISTORY_TEMPLATE" "$(history_dir)" "$NODES" "$THRESHOLD" \
      ${DRAFT_OPTIONS[@]+"${DRAFT_OPTIONS[@]}"}
  fi
  if settlement_included; then
    draft_options "$SETTLEMENT_APP"
    setup_app "$SETTLEMENT_APP" "$SETTLEMENT_TEMPLATE" "$(settlement_dir)" 3 majority \
      ${DRAFT_OPTIONS[@]+"${DRAFT_OPTIONS[@]}"}
  else
    note "the devnet settlement chain needs a devnet and the three founding nodes; it is not part of this instance"
  fi
  while IFS= read -r index; do write_local_config "$index"; done < <(node_indexes)
  load_instance_chains
  note "Prepared light instance '$INSTANCE'"
  note "  data:        $root"
  local app sealed
  while read -r app sealed; do
    note "  $(printf '%-11s' "$app:") $sealed  ($(appchain inspect "$sealed" --json | jq -r .displayFingerprint))"
  done < <(sealed_apps)
  note "  history:     $CARDANO_HISTORY_ENABLED ($CARDANO_HISTORY_PROFILE)"
}

start_nodes() {
  local index
  # The lead (node 0) starts first: it creates the devnet the others download.
  while IFS= read -r index; do
    appchain start --node-dir "$(node_dir "$index")" >/dev/null || die "node$index did not start; the reason is printed above (node log: $(node_dir "$index")/logs/node.log, once the node has run)"
    note "node$index is up at http://127.0.0.1:$((HTTP_BASE + index))"
  done < <(node_indexes)
  appchain status "$APP" --node-dir "$(node_dir 0)" --wait live --timeout 300
  local app sealed
  while read -r app sealed; do
    [ "$app" != "$APP" ] || continue
    appchain status "$app" --node-dir "$(node_dir 0)" --wait live --timeout 300 >/dev/null \
      || die "the $app application did not go live"
    note "$app is live ($(appchain inspect "$sealed" --json | jq -r .displayFingerprint))"
  done < <(sealed_apps)
}

stop_nodes() {
  local index
  local -a indexes=()
  while IFS= read -r index; do indexes=("$index" ${indexes[@]+"${indexes[@]}"}); done < <(node_indexes)
  for index in "${indexes[@]}"; do
    [ ! -f "$(node_dir "$index")/node.json" ] || appchain stop --node-dir "$(node_dir "$index")"
  done
}

up_light() {
  prepare_light
  start_nodes
}

show_status() {
  local app sealed
  while read -r app sealed; do
    # A node joined later runs the showcase application only.
    [ ! -f "$(node_dir "$NODE")/apps/$app.chain" ] || appchain status "$app" --node-dir "$(node_dir "$NODE")"
  done < <(sealed_apps)
}

show_logs() {
  local log="$(node_dir "$NODE")/logs/node.log"
  [ -f "$log" ] || die "node$NODE has no log yet"
  if [ "$FOLLOW" = true ]; then tail -f "$log"; else tail -n 200 "$log"; fi
}

# Membership growth exactly as operators do it: the new node requests with the sealed file, every existing node
# runs `approve` with its card, and the new node joins and starts. While the approvals wait for activation this
# script keeps every chain making blocks; then governance_activate proves a block under the new member set.
member_join() {
  local index="$1" card existing log pids=() logs=() status=0 i pending
  [[ "$index" =~ ^[0-9]+$ ]] && [ "$index" -ge "$NODES" ] && [ "$index" -le 15 ] \
    || die "joined-node index must be between the bootstrap count and 15"
  card="$(coordinator_dir)/node$index.member"
  [ -f "$card" ] || request_member "$(sealed_file)" "$index" "$(coordinator_dir)"
  if ! { [ -f "$(joined_file)" ] && grep -Fxq "$index" "$(joined_file)"; }; then
    note "existing nodes approve node$index ..."
    while IFS= read -r existing; do
      log="$(instance_root)/approve-node$index-by-node$existing.log"
      logs+=("$log")
      appchain approve "$card" "$APP" --node-dir "$(node_dir "$existing")" --yes --timeout 900 > "$log" 2>&1 &
      pids+=("$!")
    done < <(node_indexes)
    # If this script stops, the approvals stop too; a rerun of member join continues where they left off.
    trap 'kill "${pids[@]}" 2>/dev/null || true' EXIT
    while :; do
      pending=false
      for i in "${pids[@]}"; do kill -0 "$i" 2>/dev/null && pending=true; done
      [ "$pending" = true ] || break
      drive_blocks
      sleep 2
    done
    for i in "${!pids[@]}"; do
      wait "${pids[$i]}" || { status=1; note "approve failed:"; tail -n 20 "${logs[$i]}" >&2; }
    done
    trap - EXIT
    [ "$status" -eq 0 ] || die "node$index was not approved by every node; rerun member join to continue"
    printf '%s\n' "$index" >> "$(joined_file)"; chmod 600 "$(joined_file)"
  fi
  appchain join "$(sealed_file)" --expect-fingerprint \
    "$(appchain inspect "$(sealed_file)" --json | jq -r .displayFingerprint)" \
    --node-dir "$(node_dir "$index")" --yes >/dev/null || die "node$index could not join"
  write_local_config "$index"
  appchain start --node-dir "$(node_dir "$index")" >/dev/null || die "node$index did not start"
  governance_activate
  note "node$index joined: http://127.0.0.1:$((HTTP_BASE + index))"
}

# One message on every showcase chain whose membership change is still pending, so it reaches activation.
drive_blocks() {
  local cid status
  for cid in "${LIGHT_CHAINS[@]}"; do
    [ "$(chain_app "$cid")" = "$APP" ] || continue
    status="$(curl -fsS --connect-timeout 2 --max-time 5 \
      "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$cid/status" 2>/dev/null || true)"
    [ -n "$status" ] || continue
    printf '%s' "$status" | jq -e '.membershipEpochActive == true' >/dev/null 2>&1 \
      && [ "$(printf '%s' "$status" | jq -r '.membershipActiveMembers')" = \
        "$(curl -fsS -H "X-API-Key: $(api_key 0)" \
          "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$cid/admin/members" 2>/dev/null \
          | jq -r '.members | length')" ] && continue
    # A refused heartbeat must not end the join while the approvals run; the next round tries again.
    ( activation_submit "$cid" "activate-$(date +%s)-$RANDOM" ) >/dev/null 2>&1 || true
  done
}

# Anchoring with the setup tool: the lead (node 0) becomes each chain's anchor leader; on a devnet the tool
# funds its wallet from the devnet faucet, on preprod it uses --anchor-key-file.
anchor_chains() {
  local requested="${1:-workflow-chain}" cid app
  local -a selected=()
  if [ "$NETWORK" != devnet ]; then
    [ "$PUBLIC_CONFIRM" = "$NETWORK" ] || die "anchoring on $NETWORK spends real test ADA; add --confirm-public-anchor $NETWORK"
    [ -n "$ANCHOR_KEY_FILE" ] || die "anchoring on $NETWORK needs --anchor-key-file with a funded wallet seed"
  fi
  [ "$requested" != all ] || requested="$ANCHOR_CHAINS"
  local canonical
  canonical="$(canonical_anchor_chains "$requested")" || exit 1
  IFS=',' read -r -a selected <<< "$canonical"
  for cid in "${selected[@]}"; do
    case " ${LIGHT_CHAINS[*]} " in *" $cid "*) ;; *) note "skipping $cid: not part of this instance"; continue;; esac
    app="$(chain_app "$cid")"
    local args=(anchor "$app" --chain "$cid" --node-dir "$(node_dir 0)" --yes)
    [ -z "$ANCHOR_KEY_FILE" ] || args+=(--key "$ANCHOR_KEY_FILE")
    appchain "${args[@]}"
    # A script anchor starts with a bootstrap transaction; metadata anchors need none.
    [ "$ANCHOR_MODE" != script ] || wait_anchor_bootstrapped "$cid"
  done
  ANCHOR=true
}

show_config() {
  local cid status_json members_json
  [ -f "$(sealed_file)" ] || die "instance '$INSTANCE' is not prepared"
  note "Sealed applications (public files; compare their fingerprints):"
  local app sealed
  while read -r app sealed; do appchain inspect "$sealed"; done < <(sealed_apps)
  note "Runtime chain identities:"
  if curl -fsS "http://127.0.0.1:$HTTP_BASE/q/health/ready" >/dev/null 2>&1; then
    curl -fsS "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains" \
      | jq -r '.[].chainId' | while IFS= read -r cid; do
        status_json="$(curl -fsS \
          "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$cid/status")"
        members_json="$(curl -fsS -H "X-API-Key: $(api_key 0)" \
          "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$cid/admin/members")"
        jq -nc --argjson s "$status_json" --argjson g "$members_json" \
          '{chainId:$s.chainId,stateMachine:$s.stateMachine,tipHeight:$s.tipHeight,
          stateRoot:$s.stateRoot,activeMembers:$s.membershipActiveMembers,
          activeThreshold:$s.membershipActiveThreshold,scheduledMembers:($g.members|length),
          scheduledThreshold:$g.threshold,activationHeight:$s.membershipEpochFromHeight,
          epochActiveForNextBlock:$s.membershipEpochActive}'
      done | jq -s .
  else
    note "  (instance is stopped)"
  fi
}

config_paths() {
  note "instance options : $(instance_file)"
  note "showcase files   : $(coordinator_dir)  (draft, member cards, $APP.chain)"
  [ ! -f "$(history_sealed_file)" ] || note "history files    : $(history_dir)"
  [ ! -f "$(settlement_sealed_file)" ] || note "settlement files : $(settlement_dir)"
  note "node directories : $(instance_root)/node<N>  (yano.sh appchain status --node-dir …)"
  note "node settings    : $(instance_root)/node<N>/config/local.yaml"
  note "node logs        : $(instance_root)/node<N>/logs/node.log"
  note "outbox receipts  : $(outbox_dir)"
  note "plugin bundle    : $(plugin_file)"
}

config_export() {
  local output="${1:-${OUTPUT:-}}"
  [ -n "$output" ] || die "usage: showcase.sh config export <file>"
  [ -f "$(sealed_file)" ] || die "instance '$INSTANCE' is not prepared"
  cp -- "$(sealed_file)" "$output"
  note "wrote the sealed application (public; no keys): $output"
}

settlement_yml_value() {
  # The sealed settlement chain carries its bridge settings; the executor's are node-local.
  local key=".$1" value=""
  [ ! -f "$(settlement_sealed_file)" ] || value="$(jq -r --arg k "$key" \
    '.genesis.chains[0].settings | to_entries[] | select(.key | endswith($k)) | .value' \
    "$(settlement_sealed_file)" | head -n 1)"
  [ -n "$value" ] || value="$(jq -r --arg k "$key" 'to_entries[] | select(.key | endswith($k)) | .value' \
    "$(settlement_executor_file)" 2>/dev/null | head -n 1)"
  printf '%s' "$value"
}

settlement_script_dir() { printf '%s/settlement-bootstrap' "$(instance_root)"; }

# A public-network settlement chain, from the template and executor settings `settlement bootstrap` wrote:
# sealed as the settlement application of the current members, then every node restarts with it.
run_settlement_adopt() {
  local generated="$(settlement_script_dir)"
  [ -f "$generated/settlement.template.yaml" ] && [ -f "$generated/executor.json" ] \
    || die "no bootstrapped settlement chain in $generated; run settlement bootstrap first"
  [ ! -f "$(settlement_sealed_file)" ] || die "this instance already has a settlement application"
  # The vault commits to the federation the bootstrap read from the running nodes; seal exactly that one.
  local live members threshold
  live="$(curl -fsS -H "X-API-Key: $(api_key 0)" \
    "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/${LIGHT_CHAINS[0]}/admin/members")" \
    || die "node 0 must be running to compare the settlement federation"
  members="$(printf '%s' "$live" | jq -r '.members | length')"
  threshold="$(printf '%s' "$live" | jq -r '.threshold')"
  [ "$members" = "$NODES" ] && [ "$threshold" = "$THRESHOLD" ] \
    || die "the showcase now has $members members at threshold $threshold, but the settlement application is sealed for the $NODES founding nodes at threshold $THRESHOLD; bootstrap and adopt before adding members"
  mkdir -p "$(settlement_dir)"
  cp -- "$generated/executor.json" "$(settlement_dir)/executor.json"
  stop_nodes
  setup_app "$SETTLEMENT_APP" "$generated/settlement.template.yaml" "$(settlement_dir)" "$NODES" "$THRESHOLD"
  while IFS= read -r index; do write_local_config "$index"; done < <(node_indexes)
  load_instance_chains
  start_nodes
  note "adopted $SETTLEMENT_CHAIN_ID; next: ./showcase.sh settlement deposit --instance $INSTANCE"
}

# --- scenarios ----------------------------------------------------------------------------------------------

wait_message() {
  local chain="$1" id="$2" deadline=$(( $(date +%s) + 120 )) response
  while :; do
    response="$(curl -fsS --connect-timeout 2 --max-time 5 \
      "http://127.0.0.1:$((HTTP_BASE + NODE))/api/v1/app-chain/chains/$chain/messages/$id" \
      2>/dev/null || true)"
    if [ -n "$response" ]; then
      printf '%s' "$response" | jq -e '.height > 0' >/dev/null 2>&1 && {
        printf '%s\n' "$response"; return 0; }
    fi
    [ "$(date +%s)" -le "$deadline" ] || die "timed out waiting for message $id on $chain"
    sleep 1
  done
}

submit_hex() {
  local chain="$1" topic="$2" body="$3" node="${4:-$NODE}" response id
  response="$(curl -fsS -X POST \
    "http://127.0.0.1:$((HTTP_BASE + node))/api/v1/app-chain/chains/$chain/messages" \
    -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg topic "$topic" --arg body "$body" '{topic:$topic,bodyHex:$body}')")" \
    || die "submission failed on $chain through node $node"
  id="$(printf '%s' "$response" | jq -r '.messageId // empty')"
  [ -n "$id" ] || die "submission returned no message id: $response"
  note "submitted $topic via node $node: $id" >&2
  printf '%s' "$id"
}

expect_authenticated_map_admission_rejected() {
  local body="$1" collection="$2" key="$3" label="$4"
  local response status state_key proof
  response="$(curl -sS --connect-timeout 2 --max-time 10 -w '\n%{http_code}' -X POST \
    "http://127.0.0.1:$((HTTP_BASE + NODE))/api/v1/app-chain/chains/authenticated-map-chain/messages" \
    -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg body "$body" '{topic:"authenticated-map.command.v1",bodyHex:$body}')")" \
    || die "$label admission request failed"
  status="${response##*$'\n'}"
  response="${response%$'\n'*}"
  [ "$status" = 400 ] || die "$label expected immediate HTTP 400, got HTTP $status"
  # Legacy map validation returns prose; the host deliberately exposes only its
  # safe symbolic fallback, never plugin diagnostics or a pooled message id.
  printf '%s' "$response" | jq -e \
    'keys == ["code"] and .code == "APPLICATION_REJECTED"' >/dev/null \
    || die "$label returned an unexpected admission response: $response"
  # Prove the exact composite physical key the domain API reports; the
  # map-local state-key is not a leaf on composite chains.
  state_key="$(curl -fsS "http://127.0.0.1:$((HTTP_BASE + NODE))/api/v1/plugins/org.yanoproject.x.stdlib/authenticated-map/entries/$collection/$(printf '%s' "$key" | od -An -v -tx1 | tr -d ' \n')?chain=authenticated-map-chain" \
    | jq -r .proofKey)"
  proof="$(proof_key authenticated-map-chain "$state_key")"
  printf '%s' "$proof" | jq -e \
    '.presence == "ABSENT" and .valueHex == null and (.proofWireHex | length > 0)' \
    >/dev/null || die "$label changed authenticated state"
  note "REJECTED at admission (HTTP 400 APPLICATION_REJECTED): $label"
}

submit_text() {
  local chain="$1" topic="$2" body="$3" node="${4:-$NODE}" response id
  response="$(curl -fsS -X POST \
    "http://127.0.0.1:$((HTTP_BASE + node))/api/v1/app-chain/chains/$chain/messages" \
    -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg topic "$topic" --arg body "$body" '{topic:$topic,body:$body}')")" \
    || die "submission failed on $chain through node $node"
  id="$(printf '%s' "$response" | jq -r '.messageId // empty')"
  [ -n "$id" ] || die "submission returned no message id"
  note "submitted $topic via node $node: $id" >&2
  printf '%s' "$id"
}

proof_key() {
  curl -fsS "http://127.0.0.1:$((HTTP_BASE + NODE))/api/v1/app-chain/chains/$1/state/proof/$2"
}

finalized_message_proof() {
  local chain="$1" message_id="$2"
  curl -fsS -X POST \
    "http://127.0.0.1:$((HTTP_BASE + NODE))/api/v1/app-chain/chains/$chain/proof-subjects/finalized-message-v1/proof" \
    -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg id "$message_id" '{
      coordinates:{"message-id":$id},
      view:"latest",
      claim:{claimId:"recorded",operands:{}},
      includeEvidence:false
    }')"
}

snapshot_operation() {
  local action="${1:-status}" chain="${2:-$CARDANO_HISTORY_CHAIN_ID}"
  local series="${3:-}" sequence="${4:-}" idempotency="${5:-showcase-$(date +%s)}"
  local base="http://127.0.0.1:$((HTTP_BASE + NODE))/api/v1/app-chain/chains/$chain"
  case "$action" in
    status)
      curl -fsS -H "X-API-Key: $(api_key)" "$base/snapshots/status" | jq .;;
    list)
      curl -fsS -H "X-API-Key: $(api_key)" "$base/snapshots?limit=100" | jq .;;
    descriptor)
      [ -n "$series" ] && [ -n "$sequence" ] \
        || die "snapshots descriptor requires <chain> <series> <sequence>"
      curl -fsS -H "X-API-Key: $(api_key)" \
        "$base/snapshots/$series/$sequence" | jq .;;
    archive|restore|evict)
      [ -n "$series" ] && [ -n "$sequence" ] \
        || die "snapshots $action requires <chain> <series> <sequence> [idempotency-key]"
      curl -fsS -X POST -H "X-API-Key: $(api_key)" -H 'Content-Type: application/json' \
        "$base/admin/snapshots/$series/$sequence/$action" \
        -d "$(jq -nc --arg key "$idempotency" '{idempotencyKey:$key,evictAfterArchive:false}')" | jq .;;
    job)
      [ -n "$series" ] || die "snapshots job requires <chain> <job-id>"
      curl -fsS -H "X-API-Key: $(api_key)" "$base/admin/snapshots/jobs/$series" | jq .;;
    *) die "snapshots accepts status, list, descriptor, archive, restore, evict, or job";;
  esac
}

run_cardano_history() {
  [ "$CARDANO_HISTORY_ENABLED" = true ] || die "Cardano History is disabled for this instance"
  local url status waiting="" deadline=$(( $(date +%s) + 300 ))
  url="http://127.0.0.1:$((HTTP_BASE + NODE))/api/v1/plugins/org.yanoproject.x.cardano-history/status?chain=$CARDANO_HISTORY_CHAIN_ID"
  # The chain records its first epoch only after the L1 epoch-stability depth
  # (about two devnet epochs); until then the route has nothing to serve.
  until status="$(curl -fsS "$url" 2>/dev/null)"; do
    [ "$(date +%s)" -le "$deadline" ] || die "Cardano History recorded no stable L1 epoch within 300s"
    [ -n "$waiting" ] || { note "waiting for the first stable L1 epoch observation"; waiting=1; }
    sleep 5
  done
  printf '%s' "$status" | jq .
}

run_orders() {
  local value="${1:-showcase-order-$(date +%s)}" id finalized proof
  id="$(submit_text orders-chain orders.command.v1 "$value")"
  finalized="$(wait_message orders-chain "$id")"
  proof="$(finalized_message_proof orders-chain "$id")"
  printf '%s' "$proof" | jq -e \
    '.proof.presence == "PRESENT" and .claimResult.evaluated == true and
     .claimResult.satisfied == true and .fact.fields.height >= 1' >/dev/null \
    || die "ordered-log finalized-message proof is not a present, satisfied claim"
  printf '%s' "$proof" | jq \
    '{chainId:.proof.chainId,committedHeight:.proof.version,
      stateRoot:.proof.stateRoot,presence:.proof.presence,
      finalizedPosition:.fact.fields,claimSatisfied:.claimResult.satisfied}'
  note "ORDERED: message finalized at height $(printf '%s' "$finalized" | jq -r .height)"
}

run_registry() {
  local key="${1:-demo-key}" value="${2:-demo-value}" body id
  body="$(python3 "$CODEC" kv put "$key" "$value")"
  id="$(submit_hex registry-chain kv.command.v1 "$body")"; wait_message registry-chain "$id" >/dev/null
  proof_key registry-chain "$(printf '%s' "$key" | od -An -v -tx1 | tr -d ' \n')" \
    | jq '{chainId,committedHeight,stateRoot,valueHex}'
}

run_authenticated_map() {
  local suffix="${1:-$(date +%s)}" key value body id query state_key result

  key="attachment-$suffix"
  value="$(python3 "$CODEC" authmap-value opaque "demo-bytes-$suffix")"
  body="$(authmap_basic_body owner "$(python3 "$CODEC" authmap put attachments "$key" "$value")")"
  id="$(submit_hex authenticated-map-chain authenticated-map.command.v1 "$body")"
  wait_message authenticated-map-chain "$id" >/dev/null

  key="event-$suffix"
  value="$(python3 "$CODEC" authmap-value event created 1)"
  body="$(authmap_basic_body member "$(python3 "$CODEC" authmap put canonical-events "$key" "$value")")"
  id="$(submit_hex authenticated-map-chain authenticated-map.command.v1 "$body")"
  wait_message authenticated-map-chain "$id" >/dev/null

  key="sku-$suffix"
  value="$(python3 "$CODEC" authmap-value product "$key" 5 active "showcase product")"
  body="$(authmap_basic_body owner "$(python3 "$CODEC" authmap put products "$key" "$value")")"
  id="$(submit_hex authenticated-map-chain authenticated-map.command.v1 "$body")"
  wait_message authenticated-map-chain "$id" >/dev/null

  value="$(python3 "$CODEC" authmap-value gtin 95012346)"
  body="$(authmap_basic_body owner "$(python3 "$CODEC" authmap put gtins 95012346 "$value")")"
  id="$(submit_hex authenticated-map-chain authenticated-map.command.v1 "$body")"
  wait_message authenticated-map-chain "$id" >/dev/null

  value="$(python3 "$CODEC" authmap-value product invalid-sku 5 unknown)"
  body="$(authmap_basic_body owner "$(python3 "$CODEC" authmap put products invalid-sku "$value")")"
  expect_authenticated_map_admission_rejected \
    "$body" products invalid-sku "product schema violation"

  value="$(python3 "$CODEC" authmap-value gtin 95012345)"
  body="$(authmap_basic_body owner "$(python3 "$CODEC" authmap put gtins 95012345 "$value")")"
  expect_authenticated_map_admission_rejected \
    "$body" gtins 95012345 "custom GTIN validator violation"

  query="$(python3 "$CODEC" authmap query products "$key")"
  result="$(curl -fsS -X POST \
    "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/authenticated-map-chain/query/authenticated-map/entry-v1" \
    -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg params "$query" '{paramsHex:$params}')")"
  note "root-attested schema-validated product entry:"
  printf '%s' "$result" | jq '{chainId,stateMachineId,committedHeight,stateRoot,payloadHex}'

  state_key="$(curl -fsS "http://127.0.0.1:$((HTTP_BASE + NODE))/api/v1/plugins/org.yanoproject.x.stdlib/authenticated-map/entries/products/$(printf '%s' "$key" | od -An -v -tx1 | tr -d ' \n')?chain=authenticated-map-chain" \
    | jq -r .proofKey)"
  note "native proof for the product collection entry (composite physical key):"
  proof_key authenticated-map-chain "$state_key" \
    | jq '{chainId,committedHeight,stateRoot,valueHex,proofWireHex}'
  note "AUTHENTICATED MAP: opaque, canonical CBOR, schema, and plugin validation passed"

  run_authenticated_map_governed "$suffix"
  note "Inspect every record in the packaged console:"
  note "  http://127.0.0.1:$((HTTP_BASE + NODE))/ui/app-chain/authenticated-map/?chain=authenticated-map-chain"
}

# The composite authenticated-map machine admits only the final v1 command
# envelope (action + evidence). Wrap a legacy single-mutation command from the
# codec into a basic (evidence-free) envelope with the release-matched CLI.
authmap_basic_body() {
  local kind="$1" legacy_hex="$2" action
  [ -x "$YANO_HOME/yano.sh" ] || die "packaged yano.sh CLI is required for authenticated-map commands"
  action="$("$YANO_HOME/yano.sh" appchain authenticated-map action \
    --command-hex "$legacy_hex" --assignments "0:$kind::0")"
  "$YANO_HOME/yano.sh" appchain authenticated-map command \
    --action-hex "$action" --evidence-hex ''
}

# Prints "<authorization> <policyId> <valueEncoding>" for one collection.
authmap_collection_info() {
  authmap_domain authenticated-map | jq -r --arg c "$1" \
    '.record.collections[] | select(.id == $c)
     | "\(.authorization) \(.authorizationPolicy) \(.valueEncoding)"'
}

# Encodes the user-supplied value per the collection's committed encoding:
# opaque collections take UTF-8 text; canonical-cbor collections take hex
# (build it with: python3 tools/showcase_codec.py authmap-value ...).
authmap_value_hex() {
  local encoding="$1" value="$2"
  if [ "$encoding" = 1 ]; then
    [[ "$value" =~ ^([0-9a-f]{2})+$ ]] \
      || die "this collection stores canonical CBOR; pass the value as lowercase hex (see tools/showcase_codec.py authmap-value)"
    printf '%s' "$value"
  else
    python3 "$CODEC" authmap-value opaque "$value"
  fi
}

authmap_track() {
  local id="$1"
  note "message accepted: $id"
  wait_message "$AUTHMAP_CHAIN" "$id" >/dev/null
  note "finalized in a block"
  expect_receipt_applied "$id" "authmap command"
  authmap_domain "authenticated-map/receipts/$id" | jq -c .record
}

run_authmap_put() {
  local collection="$1" key="$2" value="$3" info mode policy encoding kind value_hex body id
  info="$(authmap_collection_info "$collection")"
  [ -n "$info" ] || die "unknown collection: $collection"
  read -r mode policy encoding <<< "$info"
  case "$mode" in
    0) kind=open;; 1) kind=owner;; 2) kind=member;;
    3) die "collection '$collection' is governed-role (policy $policy); use: authmap governed-put";;
    4) die "collection '$collection' requires the approval workflow (policy $policy); see run authenticated-map";;
    *) die "unsupported authorization mode: $mode";;
  esac
  value_hex="$(authmap_value_hex "$encoding" "$value")"
  body="$(authmap_basic_body "$kind" "$(python3 "$CODEC" authmap put "$collection" "$key" "$value_hex")")"
  id="$(submit_hex "$AUTHMAP_CHAIN" authenticated-map.command.v1 "$body")"
  authmap_track "$id"
  note "entry:"
  run_authmap_entry "$collection" "$key"
}

run_authmap_entry() {
  local collection="$1" key="$2" key_hex
  key_hex="$(printf '%s' "$2" | od -An -v -tx1 | tr -d ' \n')"
  authmap_domain "authenticated-map/entries/$collection/$key_hex" \
    | jq '{record, committedHeight, stateRoot, proofKey}'
}

run_authmap_receipt() {
  authmap_domain "authenticated-map/receipts/$1" | jq '{record, committedHeight, stateRoot, proofKey}'
}

# End-to-end governed-role PUT that prints every offline-signing step so the
# flow stays visible. Policy, revisions, key id, and genesis identity are all
# discovered from the chain itself, so this works for any governed collection
# the instance hosts. The private key: pass --seed-file <path to 64-hex raw
# Ed25519 seed> for a real actor; without it, the deterministic demo seed for
# the named showcase actor is derived and used via tools/showcase_signer.py.
run_authmap_governed_put() {
  local key="$1" value="$2" yano="$YANO_HOME/yano.sh" signer="$SHOWCASE_HOME/tools/showcase_signer.py"
  local collection="$AUTHMAP_COLLECTION" actor="$AUTHMAP_ACTOR"
  [ -x "$yano" ] || die "packaged yano.sh CLI is required"
  local info mode policy encoding
  info="$(authmap_collection_info "$collection")"
  [ -n "$info" ] || die "unknown collection: $collection"
  read -r mode policy encoding <<< "$info"
  [ "$mode" = 3 ] || die "collection '$collection' is not governed-role; use: authmap put"

  local policy_json revision required_role lifetime
  policy_json="$(authmap_domain "authenticated-map/direct-policies/$policy")" \
    || die "direct policy '$policy' not found"
  revision="$(printf '%s' "$policy_json" | jq -r .record.revision)"
  required_role="$(printf '%s' "$policy_json" | jq -r .record.requiredRole)"
  lifetime="$(printf '%s' "$policy_json" | jq -r .record.maximumAuthorizationLifetimeBlocks)"
  note "policy $policy r$revision requires role '$required_role' (lifetime ${lifetime} blocks)"

  local actor_json actor_revision key_id actor_pub
  actor_json="$(authmap_domain "actors/$actor")" || die "actor '$actor' not found"
  actor_revision="$(printf '%s' "$actor_json" | jq -r .record.revision)"
  printf '%s' "$actor_json" | jq -e --arg r "$required_role" \
    '.record.roles | index($r)' >/dev/null \
    || die "actor '$actor' does not hold required role '$required_role'"
  key_id="$(printf '%s' "$actor_json" | jq -r \
    '[.record.keys[] | select(.status == "ACTIVE")][0].keyId')"
  actor_pub="$(printf '%s' "$actor_json" | jq -r \
    '[.record.keys[] | select(.status == "ACTIVE")][0].publicKey')"
  [ "$key_id" != null ] || die "actor '$actor' has no ACTIVE key"
  note "actor $actor r$actor_revision signs with key $key_id"

  local seed
  if [ -n "$AUTHMAP_SEED_FILE" ]; then
    seed="$(tr -d ' \r\n' < "$AUTHMAP_SEED_FILE")" || die "cannot read --seed-file"
    [[ "$seed" =~ ^[0-9a-f]{64}$ ]] || die "--seed-file must contain a 64-hex raw Ed25519 seed"
  else
    seed="$(demo_actor_seed "$actor")"
    note "no --seed-file given: using the deterministic DEMO seed for '$actor' (showcase-only material)"
  fi
  [ "$(python3 "$signer" public-key "$seed")" = "$actor_pub" ] \
    || die "the provided seed does not match $actor's registered key $key_id; supply --seed-file with the correct raw Ed25519 seed"

  local genesis_id height deadline window authz_id value_hex cmd action
  genesis_id="$(authmap_domain authenticated-map | jq -r .record.genesisId)"
  height="$(authmap_tip_height)"
  window=90; [ "$lifetime" -le 90 ] && window=$((lifetime - 1))
  deadline=$((height + window))
  authz_id="$(python3 -c 'import secrets;print(secrets.token_hex(32))')"
  value_hex="$(authmap_value_hex "$encoding" "$value")"

  cmd="$(python3 "$CODEC" authmap put "$collection" "$key" "$value_hex")"
  note "1. plain command:      $cmd"
  action="$("$yano" appchain authenticated-map action \
    --command-hex "$cmd" --assignments "0:governed-role:$policy:1")"
  note "2. governed action:    $action"
  local args=(--action-hex "$action" --authorization-id "$authz_id"
    --chain "$AUTHMAP_CHAIN" --genesis-id "$genesis_id" --indexes 0
    --policy "$policy" --policy-revision "$revision" --actor "$actor"
    --actor-revision "$actor_revision" --key "$key_id" --public-key "$actor_pub"
    --issued-height "$height" --deadline-height "$deadline")
  local preimage signature evidence final id
  preimage="$("$yano" appchain authenticated-map direct-preimage "${args[@]}")"
  note "3. signing preimage:   $preimage"
  signature="$(python3 "$signer" sign "$seed" "$preimage")"
  note "4. Ed25519 signature:  $signature"
  evidence="$("$yano" appchain authenticated-map direct-complete \
    "${args[@]}" --signature "$signature")"
  note "5. completed evidence: ${evidence:0:64}..."
  final="$("$yano" appchain authenticated-map command \
    --action-hex "$action" --evidence-hex "$evidence")"
  note "6. final command:      ${final:0:64}..."
  id="$(submit_hex "$AUTHMAP_CHAIN" authenticated-map.command.v1 "$final")"
  authmap_track "$id"
  note "direct authorization consumed exactly once:"
  authmap_domain "authenticated-map/direct-consumptions/$actor/$authz_id" | jq -c .record
  note "entry:"
  run_authmap_entry "$collection" "$key"
}

# Demo-only deterministic actor seed, shared with showcase.template.yaml and ShowcaseGovernedGenesis.
demo_actor_seed() {
  python3 -c 'import hashlib,sys
print(hashlib.sha256(("yano-showcase-demo-actor:"+sys.argv[1]).encode()).hexdigest())' "$1"
}

authmap_domain() {
  curl -fsS "http://127.0.0.1:$((HTTP_BASE + NODE))/api/v1/plugins/org.yanoproject.x.stdlib/$1?chain=$AUTHMAP_CHAIN"
}

authmap_tip_height() {
  curl -fsS "http://127.0.0.1:$((HTTP_BASE + NODE))/api/v1/app-chain/chains/$AUTHMAP_CHAIN/tip" \
    | jq -r '.height'
}

expect_receipt_applied() {
  local id="$1" label="$2" deadline=$(( $(date +%s) + 60 )) receipt
  while :; do
    receipt="$(authmap_domain "authenticated-map/receipts/$id" 2>/dev/null || true)"
    if printf '%s' "$receipt" | jq -e '.record.presence == 1' >/dev/null 2>&1; then
      printf '%s' "$receipt" | jq -e '.record.status == 0' >/dev/null \
        || die "$label was rejected: $(printf '%s' "$receipt" | jq -c .record)"
      return 0
    fi
    [ "$(date +%s)" -le "$deadline" ] || die "timed out waiting for $label receipt"
    sleep 1
  done
}

# ADR-025.2 Phase F: direct-role and two-distinct-organization approval flows
# exercised end to end through offline CLI authoring and the demo signer. The
# node API and console never see actor private keys.
run_authenticated_map_governed() {
  local AUTHMAP_CHAIN=authenticated-map-chain
  local suffix="$1" yano="$YANO_HOME/yano.sh" signer="$SHOWCASE_HOME/tools/showcase_signer.py"
  local genesis_id issuer_seed issuer_pub key value cmd action height deadline
  [ -x "$yano" ] || die "packaged yano.sh CLI is required for the governed showcase"
  genesis_id="$(jq -r '.genesis.chains[] | select(.id == "authenticated-map-chain")
    | .settings["state.genesis-id"] // empty' "$(sealed_file)" 2>/dev/null)"
  [ -n "$genesis_id" ] || die "authenticated-map genesis identity is missing; run prepare first"
  issuer_seed="$(demo_actor_seed issuer-a)"
  issuer_pub="$(python3 "$signer" public-key "$issuer_seed")"

  # --- direct-role: issuer-a writes to governed-catalog under issuer-write ---
  key="catalog-$suffix"
  value="$(python3 "$CODEC" authmap-value opaque "governed-entry-$suffix")"
  cmd="$(python3 "$CODEC" authmap put governed-catalog "$key" "$value")"
  action="$("$yano" appchain authenticated-map action \
    --command-hex "$cmd" --assignments 0:governed-role:issuer-write:1)"
  local authz_id preimage signature evidence governed_cmd id
  authz_id="$(python3 -c 'import hashlib,sys
print(hashlib.sha256(("yano-showcase-authz:"+sys.argv[1]).encode()).hexdigest())' "$suffix")"
  height="$(authmap_tip_height)"
  deadline=$((height + 90))
  local direct_args=(--action-hex "$action" --authorization-id "$authz_id"
    --chain authenticated-map-chain --genesis-id "$genesis_id" --indexes 0
    --policy issuer-write --policy-revision 1 --actor issuer-a --actor-revision 1
    --key issuer-a-k1 --public-key "$issuer_pub"
    --issued-height "$height" --deadline-height "$deadline")
  preimage="$("$yano" appchain authenticated-map direct-preimage "${direct_args[@]}")"
  signature="$(python3 "$signer" sign "$issuer_seed" "$preimage")"
  evidence="$("$yano" appchain authenticated-map direct-complete \
    "${direct_args[@]}" --signature "$signature")"
  governed_cmd="$("$yano" appchain authenticated-map command \
    --action-hex "$action" --evidence-hex "$evidence")"
  id="$(submit_hex authenticated-map-chain authenticated-map.command.v1 "$governed_cmd")"
  wait_message authenticated-map-chain "$id" >/dev/null
  expect_receipt_applied "$id" "direct-role governed-catalog write"
  authmap_domain "authenticated-map/direct-consumptions/issuer-a/$authz_id" \
    | jq -e '.record.policyId == "issuer-write" and .record.appliedHeight > 0' >/dev/null \
    || die "direct authorization consumption record is missing"
  note "DIRECT ROLE: issuer-a wrote governed-catalog/$key with externally signed evidence"

  # --- approval: issuer-a proposes, auditors from two organizations approve ---
  local proposal payload_hash statement approve_seed reference approval_cmd
  key="release-$suffix"
  value="$(python3 "$CODEC" authmap-value opaque "released-product-$suffix")"
  cmd="$(python3 "$CODEC" authmap put released-products "$key" "$value")"
  action="$("$yano" appchain authenticated-map action \
    --command-hex "$cmd" --assignments 0:approval:product-release:1)"
  payload_hash="$("$yano" appchain authenticated-map approval-payload \
    --action-hex "$action" --genesis-id "$genesis_id")"
  proposal="release-$suffix"
  height="$(authmap_tip_height)"
  deadline=$((height + 300))
  local seed_file
  seed_file="$(mktemp "$(instance_root)/.demo-seed.XXXXXX")"
  chmod 600 "$seed_file"
  sign_statement() {
    local actor="$1" seed="$2" statement_action="$3" clause="$4"
    printf '%s' "$seed" > "$seed_file"
    "$yano" appchain role sign --action "$statement_action" \
      --chain authenticated-map-chain --proposal "$proposal" \
      --policy product-release --policy-revision 1 \
      --payload-domain yano.authenticated-map.action.v1 \
      --payload-hash "$payload_hash" --deadline-height "$deadline" \
      --actor "$actor" --actor-revision 1 --key "$actor-k1" \
      --clause "$clause" --seed-file "$seed_file"
  }
  statement="$(sign_statement issuer-a "$issuer_seed" PROPOSE '')"
  id="$(submit_hex authenticated-map-chain role-approvals.command.v1 "$statement")"
  wait_message authenticated-map-chain "$id" >/dev/null
  for auditor in auditor-a auditor-b; do
    approve_seed="$(demo_actor_seed "$auditor")"
    statement="$(sign_statement "$auditor" "$approve_seed" APPROVE independent-auditors)"
    id="$(submit_hex authenticated-map-chain role-approvals.command.v1 "$statement")"
    wait_message authenticated-map-chain "$id" >/dev/null
  done
  rm -f -- "$seed_file"
  authmap_domain "proposals/$proposal" \
    | jq -e '.record.status == "APPROVED" and (.record.decisions | length) == 2' >/dev/null \
    || die "product-release proposal did not reach APPROVED with two distinct-organization decisions"
  reference="$("$yano" appchain authenticated-map approval-reference \
    --action-hex "$action" --proposal "$proposal" --indexes 0 \
    --policy product-release --policy-revision 1)"
  approval_cmd="$("$yano" appchain authenticated-map command \
    --action-hex "$action" --evidence-hex "$reference")"
  id="$(submit_hex authenticated-map-chain authenticated-map.command.v1 "$approval_cmd")"
  wait_message authenticated-map-chain "$id" >/dev/null
  expect_receipt_applied "$id" "approved released-products execution"
  authmap_domain "authenticated-map/approval-consumptions/$proposal" \
    | jq -e '.record.policyId == "product-release" and .record.appliedHeight > 0' >/dev/null \
    || die "approval consumption record is missing"
  note "APPROVAL: $proposal approved by auditors in two organizations and executed exactly once"
}

approval_propose() {
  local item="$1" payload="$2" required="${3:-2}" body id
  body="$(python3 "$CODEC" approval propose "$item" "$payload" --required "$required")"
  id="$(submit_hex approvals-chain approvals.command.v1 "$body" 0)"
  wait_message approvals-chain "$id" >/dev/null
  note "proposal: $item (requires $required separate approval messages)"
}

approval_approve() {
  local item="$1" member="$2" body id
  body="$(python3 "$CODEC" approval approve "$item")"
  id="$(submit_hex approvals-chain approvals.command.v1 "$body" "$member")"
  wait_message approvals-chain "$id" >/dev/null
  note "approval: $item by member/node $member"
}

run_approvals() {
  local item="${1:-approval-$(date +%s)}" required=2 first=1 member
  if [ "$NODES" -lt 3 ]; then required="$NODES"; first=0; fi
  approval_propose "$item" '{"action":"release-demo"}' "$required"
  for ((member=first;member<first+required;member++)); do
    approval_approve "$item" "$member"
  done
  proof_key approvals-chain "$(python3 "$CODEC" state-key approval "$item")" \
    | jq '{chainId,committedHeight,stateRoot,valueHex}'
}

run_balances() {
  local sender body id
  sender="$(curl -fsS "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/balances-chain/status" \
    | jq -r .memberKey)"
  body="$(python3 "$CODEC" balance mint "$sender" 1000)"
  id="$(submit_hex balances-chain balances.command.v1 "$body" 0)"; wait_message balances-chain "$id" >/dev/null
  body="$(python3 "$CODEC" balance transfer demo-recipient 250)"
  id="$(submit_hex balances-chain balances.command.v1 "$body" 0)"; wait_message balances-chain "$id" >/dev/null
  proof_key balances-chain "$(python3 "$CODEC" state-key balance demo-recipient)" \
    | jq '{chainId,committedHeight,stateRoot,valueHex}'
}

run_documents() {
  local entity="${1:-case-$(date +%s)}" body id
  body="$(python3 "$CODEC" document "$entity" 'demo-document-v1' --reference 'demo://document/1')"
  id="$(submit_hex documents-chain doc-trail.command.v1 "$body")"; wait_message documents-chain "$id" >/dev/null
  proof_key documents-chain "$(python3 "$CODEC" state-key document "$entity")" \
    | jq '{chainId,committedHeight,stateRoot,valueHex}'
}

document_review_statement() {
  local actor="$1" action="$2" proposal="$3" payload_hash="$4" deadline="$5" clause="$6"
  local seed_file seed="$7" yano="$YANO_HOME/yano.sh"
  seed_file="$(mktemp "$(instance_root)/.document-review-seed.XXXXXX")"
  chmod 600 "$seed_file"; printf '%s' "$seed" > "$seed_file"
  local args=(appchain role sign --action "$action"
    --chain document-review-chain --proposal "$proposal"
    --policy document-release --policy-revision 1
    --payload-domain document.review.release.v1 --payload-hash "$payload_hash"
    --deadline-height "$deadline" --actor "$actor" --actor-revision 1
    --key "$actor-k1" --seed-file "$seed_file")
  [ -z "$clause" ] || args+=(--clause "$clause")
  "$yano" "${args[@]}"
  rm -f -- "$seed_file"
}

document_review_propose() {
  local proposal="$1" payload_hash="$2" height deadline statement id
  height="$(curl -fsS "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/document-review-chain/tip" | jq -r .height)"
  deadline=$((height + 300))
  statement="$(document_review_statement issuer-a PROPOSE "$proposal" "$payload_hash" \
    "$deadline" '' "$(demo_actor_seed issuer-a)")"
  id="$(submit_hex document-review-chain role-approvals.command.v1 "$statement" 0)"
  wait_message document-review-chain "$id" >/dev/null
  printf '%s:%s' "$deadline" "$id"
}

run_document_review() {
  local suffix="${1:-$(date +%s)}" proposal entity content_hash command commitment
  local proposed deadline id statement receipt local_key physical_key auditor
  proposal="document-review-$suffix"; entity="document-$suffix"
  content_hash="$(python3 -c 'import hashlib,sys;print(hashlib.sha256(sys.argv[1].encode()).hexdigest())' \
    "approved-document-$suffix")"
  command="$(python3 "$CODEC" document-review "$proposal" "$entity" "$content_hash" \
    "showcase://documents/$entity")"
  commitment="$(python3 "$CODEC" blake2b-hex "$command")"
  proposed="$(document_review_propose "$proposal" "$commitment")"
  deadline="${proposed%%:*}"
  for auditor in auditor-a auditor-b; do
    statement="$(document_review_statement "$auditor" APPROVE "$proposal" "$commitment" \
      "$deadline" independent-auditors "$(demo_actor_seed "$auditor")")"
    id="$(submit_hex document-review-chain role-approvals.command.v1 "$statement" 0)"
    wait_message document-review-chain "$id" >/dev/null
  done
  id="$(submit_hex document-review-chain document-review.release.v1 "$command" 0)"
  wait_message document-review-chain "$id" >/dev/null
  receipt="$(curl -fsS -X POST \
    "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/document-review-chain/query/components/document-review-receipts/receipt" \
    -H 'Content-Type: application/json' -d "$(jq -nc --arg p "$(printf '%s' "$proposal" | od -An -v -tx1 | tr -d ' \n')" '{paramsHex:$p}')")"
  [ -n "$(printf '%s' "$receipt" | jq -r '.payloadHex // empty')" ] \
    || die "document-review approval-consumption receipt is absent"
  local_key="$(python3 "$CODEC" state-key document "$entity")"
  physical_key="$(python3 "$CODEC" composite-key documents "$local_key")"
  note "DOCUMENT REVIEW: issuer proposed, two organizations approved, document head mutated, approval consumed once"
  printf '%s' "$receipt" | jq '{chainId,stateMachineId,committedHeight,stateRoot,payloadHex}'
  proof_key document-review-chain "$physical_key" \
    | jq '{chainId,committedHeight,stateRoot,presence,valueHex}'
}

composite_register() {
  local key="$1" order="$2" canonical body id
  canonical="$(python3 "$CODEC" order "$order")"
  body="$(python3 "$CODEC" kv put "$key" "$canonical")"
  id="$(submit_hex workflow-chain orders.command.v1 "$body" 0)"; wait_message workflow-chain "$id" >/dev/null
  note "registered order '$key': $canonical"
}

composite_propose() {
  local item="$1" order="$2" required="${3:-2}" canonical body id
  canonical="$(python3 "$CODEC" order "$order")"
  body="$(python3 "$CODEC" approval propose "$item" "$canonical" --required "$required")"
  id="$(submit_hex workflow-chain approvals.command.v1 "$body" 0)"; wait_message workflow-chain "$id" >/dev/null
  note "proposed '$item' bound to order hash $(python3 "$CODEC" blake2b "$canonical")"
}

composite_approve() {
  local item="$1" member="$2" body id
  body="$(python3 "$CODEC" approval approve "$item")"
  id="$(submit_hex workflow-chain approvals.command.v1 "$body" "$member")"; wait_message workflow-chain "$id" >/dev/null
  note "application approval '$item' sent by node/member $member"
}

composite_release() {
  local release="$1" key="$2" item="$3" body id
  body="$(python3 "$CODEC" release "$release" "$key" "$item")"
  id="$(submit_hex workflow-chain showcase.release.v1 "$body" 0)"; wait_message workflow-chain "$id" >/dev/null
  note "release command '$release' finalized"
}

composite_verify() {
  local release="$1" deadline=$(( $(date +%s) + 120 )) status outbox
  local effects effect height ordinal effect_id detail proof
  while :; do
    status="$(curl -fsS -X POST \
      "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/workflow-chain/query/components/release/get" \
      -H 'Content-Type: application/json' \
      -d "$(jq -nc --arg p "$(printf '%s' "$release" | od -An -v -tx1 | tr -d ' \n')" '{paramsHex:$p}')" \
      2>/dev/null || true)"
    [ -n "$status" ] && [ "$(printf '%s' "$status" | jq -r '.payloadHex // empty')" != "" ] && break
    [ "$(date +%s)" -le "$deadline" ] || die "release state did not appear"
    sleep 1
  done
  while :; do
    effects="$(curl -fsS --connect-timeout 2 --max-time 5 \
      "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/workflow-chain/effects?fromHeight=0&limit=1000" \
      2>/dev/null || true)"
    effect="$(printf '%s' "$effects" | jq -c --arg scope "showcase/order-release/$release" \
      '.effects[]? | select(.scope == $scope and .type == "showcase.outbox.write")' | tail -1)"
    [ -n "$effect" ] && break
    [ "$(date +%s)" -le "$deadline" ] || die "release effect did not appear"
    sleep 1
  done
  height="$(printf '%s' "$effect" | jq -r .height)"
  ordinal="$(printf '%s' "$effect" | jq -r .ordinal)"
  effect_id="$(printf '%s' "$effect" | jq -r .effectIdHashHex)"
  while :; do
    detail="$(curl -fsS --connect-timeout 2 --max-time 5 \
      "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/workflow-chain/effects/$height/$ordinal" \
      2>/dev/null || true)"
    [ "$(printf '%s' "$detail" | jq -r '.execution.status // empty' 2>/dev/null)" = DONE ] && break
    [ "$(date +%s)" -le "$deadline" ] || die "outbox effect did not reach DONE"
    sleep 1
  done
  outbox="$(outbox_dir)/$effect_id.json"
  [ -f "$outbox" ] || die "executor finished without its deterministic outbox receipt"
  proof="$(curl -fsS --connect-timeout 2 --max-time 5 \
    "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/workflow-chain/effects/$height/$ordinal/proof")"
  printf '%s' "$proof" | jq -e \
    '.effectHashHex and .effectsRootHex and .stateRootHex and .stateProofWireHex' >/dev/null \
    || die "effect commitment proof is incomplete"
  while :; do
    status="$(curl -fsS -X POST \
      "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/workflow-chain/query/components/release/get" \
      -H 'Content-Type: application/json' \
      -d "$(jq -nc --arg p "$(printf '%s' "$release" | od -An -v -tx1 | tr -d ' \n')" '{paramsHex:$p}')" \
      2>/dev/null || true)"
    printf '%s' "$status" | jq -e '.payloadHex | startswith("890101")' >/dev/null 2>&1 && break
    printf '%s' "$status" | jq -e '.payloadHex | startswith("890102")' >/dev/null 2>&1 \
      && die "release effect result was finalized as failed"
    [ "$(date +%s)" -le "$deadline" ] || die "confirmed effect result did not finalize on chain"
    sleep 1
  done
  note "root-attested release query:"
  printf '%s' "$status" | jq '{chainId,stateMachineId,committedHeight,stateRoot,payloadHex}'
  note "effect execution and commitment proof:"
  printf '%s' "$detail" | jq '{record:{height:.record.height,ordinal:.record.ordinal,type:.record.type,scope:.record.scope},execution}'
  printf '%s' "$proof" | jq '{effectHashHex,effectsRootHex,stateRootHex}'
  note "idempotent external receipt: $outbox"
  jq . "$outbox"
}

run_composite() {
  local suffix key item release order required=2 first=1 member
  suffix="${1:-$(date +%s)}"
  key="order-$suffix"
  item="proposal-$suffix"
  release="release-$suffix"
  if [ "$NODES" -lt 3 ]; then required="$NODES"; first=0; fi
  order="{\"id\":\"$key\",\"amount\":42,\"currency\":\"USD\"}"
  composite_register "$key" "$order"
  composite_propose "$item" "$order" "$required"
  for ((member=first;member<first+required;member++)); do
    composite_approve "$item" "$member"
  done
  composite_release "$release" "$key" "$item"
  composite_verify "$release"
}

run_roles() {
  note "roles-chain is running the generic role-approvals-v1 profile."
  curl -fsS "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/roles-chain/status" \
    | jq '{chainId,stateMachine,stateMachineStatus,tipHeight,stateRoot}'
  note "For the complete governed actor/policy/signature lifecycle run:"
  note "  ./showcase.sh run roles --profile evidence --variant role --instance $INSTANCE"
}

accepted_eutxo_receipt() {
  local transaction_id="$1" query payload receipt_status
  query="$(curl -fsS -X POST \
    "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/payments-chain/query/transactions/receipt" \
    -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg p "$(printf '%s' "$transaction_id" | od -An -v -tx1 | tr -d ' \n')" \
      '{paramsHex:$p}')")"
  payload="$(printf '%s' "$query" | jq -r '.payloadHex // empty')"
  [ -n "$payload" ] || die "payments-chain returned no transaction receipt"
  receipt_status="$(eutxo_tool receipt "$payload")"
  [ "$receipt_status" = ACCEPTED ] \
    || die "payments-chain transaction outcome is $receipt_status, expected ACCEPTED"
  printf '%s\n' "$query"
}

run_eutxo() {
  local result id transaction_id query
  result="$(submit_eutxo_payment)"
  id="$(printf '%s' "$result" | jq -r .messageId)"
  transaction_id="$(printf '%s' "$result" | jq -r .transactionId)"
  wait_message payments-chain "$id" >/dev/null
  query="$(accepted_eutxo_receipt "$transaction_id")"
  note "payments-chain accepted a signed, zero-fee virtual-funds transfer:"
  printf '%s' "$query" | jq '{chainId,stateMachineId,committedHeight,stateRoot,payloadHex}'
  note "For a signed payment lifecycle use the dedicated profile:"
  note "  ./showcase.sh run eutxo --profile eutxo --variant ledger --instance $INSTANCE"
}

run_load_test() {
  [ "$PROFILE" = light ] || die "load-test applies to the light profile"
  [ -n "$COUNT" ] && [[ "$COUNT" =~ ^[0-9]+$ ]] && [ "$COUNT" -ge 1 ] \
    || die "load-test requires --count N"
  [[ "$CONCURRENCY" =~ ^[0-9]+$ ]] && [ "$CONCURRENCY" -ge 1 ] \
    || die "--concurrency must be a positive integer"
  [[ "$PAYLOAD_SIZE" =~ ^[0-9]+$ ]] \
    || die "--payload-bytes must be a non-negative integer"
  local scenario="${1:-orders}" chain topic mode="" target=()
  case "$scenario" in
    orders|orders-chain)
      chain=orders-chain; topic=orders.command.v1;;
    registry|registry-chain)
      chain=registry-chain; topic=kv.command.v1; mode=kv;;
    *) die "load-test supports orders or registry";;
  esac
  if [ "$SPREAD" = true ]; then target=(--spread); else target=(--node "$NODE"); fi
  local args=("$chain" -n "$COUNT" -c "$CONCURRENCY" -s "$PAYLOAD_SIZE" -t "$topic")
  [ "$mode" != kv ] || args+=(--kv)
  args+=("${target[@]}")
  YANO_SHOWCASE_HTTP_BASE="$HTTP_BASE" "$SHOWCASE_HOME/tools/loadtest.sh" "${args[@]}"
}

run_soak_test() {
  [ "$PROFILE" = light ] || die "soak-test applies to the light profile"
  case "${1:-orders}" in orders|orders-chain) ;; \
    *) die "soak-test currently supports the ordered-log orders chain";; esac
  [[ "$DURATION" =~ ^[0-9]+$ ]] && [ "$DURATION" -ge 1 ] \
    || die "--duration must be a positive integer"
  [[ "$RATE" =~ ^[0-9]+$ ]] && [ "$RATE" -ge 1 ] \
    || die "--rate must be a positive integer"
  [[ "$CONCURRENCY" =~ ^[0-9]+$ ]] && [ "$CONCURRENCY" -ge 1 ] \
    || die "--concurrency must be a positive integer"
  [[ "$PAYLOAD_SIZE" =~ ^[0-9]+$ ]] \
    || die "--payload-bytes must be a non-negative integer"
  [[ "$SAMPLE" =~ ^[0-9]+$ ]] && [ "$SAMPLE" -ge 1 ] \
    || die "--sample must be a positive integer"
  local target=() report="${REPORT_DIR:-$(instance_root)/reports/soak-$(date +%Y%m%d-%H%M%S)}"
  if [ "$SPREAD" = true ]; then target=(--spread); else target=(--node "$NODE"); fi
  YANO_SHOWCASE_HTTP_BASE="$HTTP_BASE" "$SHOWCASE_HOME/tools/soaktest.sh" orders-chain \
    --duration "$DURATION" --rate "$RATE" --conc "$CONCURRENCY" \
    -s "$PAYLOAD_SIZE" --sample "$SAMPLE" --out "$report" "${target[@]}"
}

eutxo_tool() {
  # The helper's eUTxO contract types ship in the ledger bundle, not the showcase bundle.
  local ledger=("$YANO_HOME"/plugins/yano-x-eutxo-ledger-bundle-*.jar)
  [ "${#ledger[@]}" -eq 1 ] && [ -f "${ledger[0]}" ] \
    || die "expected exactly one eUTxO ledger plugin bundle"
  java -cp "$YANO_HOME/yano.jar:$(plugin_file):${ledger[0]}" \
    org.yanoproject.x.showcase.ShowcaseEutxoTransactions "$@"
}

eutxo_current_outpoint() {
  local identity address status tip params response payload
  identity="$(eutxo_tool identity)"
  address="$(printf '%s' "$identity" | jq -r .address)"
  status="$(curl -fsS \
    "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/payments-chain/status")"
  tip="$(printf '%s' "$status" | jq -r '.tipHeight // 0')"
  if [ "$tip" -eq 0 ]; then
    printf '%s' "$identity" | jq -r .genesisOutpoint
    return
  fi
  params="$(printf '%s' "$address" | od -An -v -tx1 | tr -d ' \n')"
  response="$(curl -fsS -X POST \
    "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/payments-chain/query/utxos/address" \
    -H 'Content-Type: application/json' -d "$(jq -nc --arg p "$params" '{paramsHex:$p}')")"
  payload="$(printf '%s' "$response" | jq -r '.payloadHex // empty')"
  [ -n "$payload" ] || die "payments-chain returned no virtual EUTxO query payload"
  eutxo_tool outpoint "$payload"
}

submit_eutxo_payment() {
  local outpoint transaction id identity
  outpoint="$(eutxo_current_outpoint)"
  transaction="$(eutxo_tool payment "$outpoint")"
  id="$(submit_hex payments-chain eutxo.transactions \
    "$(printf '%s' "$transaction" | jq -r .transactionHex)" 0)"
  identity="$(eutxo_tool identity)"
  jq -nc --arg messageId "$id" \
    --arg transactionId "$(printf '%s' "$transaction" | jq -r .transactionId)" \
    --arg address "$(printf '%s' "$identity" | jq -r .address)" \
    '{messageId:$messageId,transactionId:$transactionId,address:$address}'
}

activation_submit() {
  local cid="$1" suffix="$2" body sender result value
  case "$cid" in
    orders-chain)
      submit_text "$cid" showcase.governance-heartbeat.v1 "$suffix" 0;;
    registry-chain)
      body="$(python3 "$CODEC" kv put "$suffix" active)"
      submit_hex "$cid" kv.command.v1 "$body" 0;;
    approvals-chain)
      body="$(python3 "$CODEC" approval propose "$suffix" activation --required 1)"
      submit_hex "$cid" approvals.command.v1 "$body" 0;;
    balances-chain)
      sender="$(curl -fsS \
        "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$cid/status" | jq -r .memberKey)"
      body="$(python3 "$CODEC" balance mint "$sender" 1)"
      submit_hex "$cid" balances.command.v1 "$body" 0;;
    documents-chain)
      body="$(python3 "$CODEC" document "$suffix" activation \
        --reference showcase://governance-activation)"
      submit_hex "$cid" doc-trail.command.v1 "$body" 0;;
    workflow-chain)
      body="$(python3 "$CODEC" kv put "$suffix" activation)"
      submit_hex "$cid" orders.command.v1 "$body" 0;;
    roles-chain)
      body="$(python3 "$CODEC" role-probe "$suffix")"
      submit_hex "$cid" actors.command.v1 "$body" 0;;
    document-review-chain)
      body="$(python3 -c 'import hashlib,sys;print(hashlib.blake2b(sys.argv[1].encode(),digest_size=32).hexdigest())' "$suffix")"
      document_review_propose "$suffix" "$body" | cut -d: -f2;;
    payments-chain)
      result="$(submit_eutxo_payment)"
      wait_message "$cid" "$(printf '%s' "$result" | jq -r .messageId)" >/dev/null
      accepted_eutxo_receipt "$(printf '%s' "$result" | jq -r .transactionId)" >/dev/null
      printf '%s' "$result" | jq -r .messageId;;
    authenticated-map-chain)
      value="$(python3 "$CODEC" authmap-value opaque "$suffix")"
      body="$(authmap_basic_body owner \
        "$(python3 "$CODEC" authmap put attachments "$suffix" "$value")")"
      submit_hex "$cid" authenticated-map.command.v1 "$body" 0;;
    authenticated-map-jmt-chain)
      value="$(python3 "$CODEC" authmap-value opaque "$suffix")"
      body="$(authmap_basic_body owner \
        "$(python3 "$CODEC" authmap put attachments "$suffix" "$value")")"
      submit_hex "$cid" authenticated-map.command.v1 "$body" 0;;
    "$CARDANO_HISTORY_CHAIN_ID")
      # Cardano History admits only L1 observations; it makes a block per observed epoch.
      printf 'skip';;
    "$SETTLEMENT_CHAIN_ID")
      # The settlement chain admits only eutxo transactions and proposer-injected
      # L1 observations; it has no funds until the first vault deposit, so
      # there is nothing valid to submit here. It bootstraps with its
      # membership epoch active; a pending epoch (e.g. after member changes)
      # activates on the next settlement block — drive one with a deposit.
      printf 'skip';;
    *) die "unsupported light-profile chain: $cid";;
  esac
}

governance_activate() {
  local deadline=$(( $(date +%s) + 240 )) round=0 pending cid status id index suffix
  local -a submitted_chains=() submitted_ids=() chains=()
  # The L1-driven applications keep their founding members; only the showcase application changes.
  for cid in "${LIGHT_CHAINS[@]}"; do [ "$(chain_app "$cid")" != "$APP" ] || chains+=("$cid"); done
  while :; do
    pending=0
    submitted_chains=()
    submitted_ids=()
    round=$((round + 1))
    for cid in "${chains[@]}"; do
      status="$(curl -fsS --connect-timeout 2 --max-time 5 \
        "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$cid/status")" \
        || die "cannot inspect governed epoch on $cid"
      if [ "$(printf '%s' "$status" | jq -r '.membershipEpochActive // false')" != true ]; then
        suffix="activate-$round-$(date +%s)"
        id="$(activation_submit "$cid" "$suffix")"
        if [ "$id" = skip ]; then
          note "note: $cid has a pending membership epoch; it activates on the next settlement block (run: ./showcase.sh settlement deposit --instance $INSTANCE)"
          continue
        fi
        pending=$((pending + 1))
        submitted_chains+=("$cid")
        submitted_ids+=("$id")
      fi
    done
    [ "$pending" -gt 0 ] || break
    for ((index=0;index<${#submitted_ids[@]};index++)); do
      wait_message "${submitted_chains[$index]}" "${submitted_ids[$index]}" >/dev/null
    done
    [ "$(date +%s)" -le "$deadline" ] \
      || die "timed out advancing governed membership epochs"
  done

  # Finalize one block under the newly active profile. This makes the latest
  # certificate itself prove the new member set/threshold, rather than merely
  # leaving the next block poised at the activation boundary.
  submitted_ids=()
  for cid in "${chains[@]}"; do
    id="$(activation_submit "$cid" "proof-$round-$(date +%s)")"
    submitted_ids+=("$id")
  done
  for ((index=0;index<${#chains[@]};index++)); do
    [ "${submitted_ids[$index]}" = skip ] \
      || wait_message "${chains[$index]}" "${submitted_ids[$index]}" >/dev/null
    status="$(curl -fsS --connect-timeout 2 --max-time 5 \
      "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/${chains[$index]}/status")"
    printf '%s' "$status" | jq -e \
      '.membershipEpochActive == true
       and .membershipActiveMembers == .members
       and .membershipActiveThreshold == .threshold' >/dev/null \
      || die "governed epoch did not activate consistently on ${chains[$index]}"
  done
  note "all showcase governed epochs are active; proof blocks finalized"
}

verify_light() {
  show_status
  local cid identity total i deadline converged reference view
  local reference_tip reference_root threshold block cert="" anchor_status
  local -a nodes=()
  for cid in "${LIGHT_CHAINS[@]}"; do
    # Each chain is compared across the nodes of its own application.
    nodes=()
    while IFS= read -r i; do nodes+=("$i"); done < <(app_nodes "$(chain_app "$cid")")
    total="${#nodes[@]}"
    deadline=$(( $(date +%s) + 120 ))
    while :; do
      converged=true
      reference="$(curl -fsS --connect-timeout 2 --max-time 5 \
        "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$cid/status" 2>/dev/null || true)"
      reference_tip="$(printf '%s' "$reference" | jq -r '.tipHeight // -1' 2>/dev/null)"
      reference_root="$(printf '%s' "$reference" | jq -r '.stateRoot // empty' 2>/dev/null)"
      [[ "$reference_tip" =~ ^[0-9]+$ ]] && [ -n "$reference_root" ] || converged=false
      for i in "${nodes[@]}"; do
        curl -fsS --connect-timeout 2 --max-time 5 \
          "http://127.0.0.1:$((HTTP_BASE + i))/q/health/ready" >/dev/null 2>&1 \
          || { converged=false; break; }
        view="$(curl -fsS --connect-timeout 2 --max-time 5 \
          "http://127.0.0.1:$((HTTP_BASE + i))/api/v1/app-chain/chains/$cid/status" \
          2>/dev/null || true)"
        [ "$(printf '%s' "$view" | jq -r '.tipHeight // -1' 2>/dev/null)" = "$reference_tip" ] \
          && [ "$(printf '%s' "$view" | jq -r '.stateRoot // empty' 2>/dev/null)" = "$reference_root" ] \
          || { converged=false; break; }
      done
      [ "$converged" = true ] && break
      [ "$(date +%s)" -le "$deadline" ] || die "$cid did not converge across $total nodes"
      sleep 1
    done
    threshold="$(printf '%s' "$reference" | jq -r '.membershipActiveThreshold // 0')"
    if [ "$reference_tip" -gt 0 ]; then
      block="$(curl -fsS --connect-timeout 2 --max-time 5 \
        "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$cid/blocks/$reference_tip")"
      cert="$(printf '%s' "$block" | jq -r '.certSignatures // 0')"
      [ "$cert" -ge "$threshold" ] \
        || die "$cid tip certificate has $cert signatures below active threshold $threshold"
    else
      cert="n/a"
    fi
    identity="$(curl -fsS -H "X-API-Key: $(api_key 0)" \
      "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$cid/identity")"
    printf '%-18s nodes=%d tip=%d cert=%s/%s config=%s plugin=%s\n' "$cid" "$total" \
      "$reference_tip" "$cert" "$threshold" \
      "$(printf '%s' "$identity" | jq -r '.resolvedConfigDigest[0:12] // "n/a"')" \
      "$(printf '%s' "$identity" | jq -r '.pluginCatalogFingerprint[0:12] // "n/a"')"
  done
  if [ "$ANCHOR" = true ]; then
    deadline=$(( $(date +%s) + 120 ))
    while :; do
      anchor_status="$(curl -fsS --connect-timeout 2 --max-time 5 \
        "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/workflow-chain/status" \
        2>/dev/null || true)"
      printf '%s' "$anchor_status" | jq -e \
        '.anchor.enabled == true and .anchor.bootstrapped == true
         and (.anchor.lastAnchoredHeight // 0) > 0
         and (.anchor.lagBlocks // 999999) < 2
         and ((.anchor.lastAnchorTx // "") | length > 0)' \
        >/dev/null 2>&1 && break
      [ "$(date +%s)" -le "$deadline" ] || die "workflow-chain has no confirmed L1 anchor"
      sleep 2
    done
    note "workflow-chain L1 anchor confirmed: $(printf '%s' "$anchor_status" | jq -r '.anchor.lastAnchorTx')"
  fi
}

delegate_evidence() {
  local command="$1" demo="$YANO_HOME/examples/evidence/demo.sh" machine args=()
  local evidence_id
  [ -x "$demo" ] || die "packaged evidence harness is missing"
  case "$VARIANT" in composite|default) machine=composite;; role) machine=role;;
    *) die "evidence variant must be composite or role";; esac
  if [ "$machine" = role ]; then
    case "$command" in run) command=role-lifecycle;; verify) command=probe;; esac
  fi
  unset DEMO_PREBUILT_ARTIFACT_ROOT
  export DEMO_YANO_HOME="$YANO_HOME"
  args=("$command" --deployment compose --machine "$machine" --network "$NETWORK"
    --instance "$INSTANCE" --data-dir "$(instance_root)/evidence")
  [ -z "$ANCHOR_KEY_FILE" ] || args+=(--anchor-key-file "$ANCHOR_KEY_FILE")
  [ -z "$PUBLIC_CONFIRM" ] || args+=(--confirm-public-anchor "$PUBLIC_CONFIRM")
  if [ "$command" = verify ]; then
    evidence_id="$(sed -n 's/^DEMO_EVIDENCE_ID=//p' \
      "$YANO_HOME/examples/evidence/config/common.env")"
    [ -n "$evidence_id" ] || die "packaged evidence default id is missing"
    args+=(--evidence-id "$evidence_id")
  fi
  [ -z "$COUNT" ] || args+=(--count "$COUNT")
  "$demo" "${args[@]}"
}

# True when the one-shot root thread token is present at the root address —
# the single authoritative signal that the L1 identity is deployed.
settlement_root_thread_live() {
  local unit
  unit="$(settlement_yml_value root-unit)"
  [ -n "$unit" ] || return 1
  curl -fsS --connect-timeout 2 --max-time 8 \
    "http://127.0.0.1:$HTTP_BASE/api/v1/addresses/$1/utxos" 2>/dev/null \
    | grep -q "$unit"
}

run_settlement_info() {
  local vault shard root payout profile delay chain_status tip
  vault="$(settlement_yml_value vault-address)"
  shard="$(settlement_yml_value shard-address)"
  root="$(settlement_yml_value root-address)"
  payout="$(settlement_yml_value withdrawal-address)"
  profile="$(settlement_yml_value profile)"
  delay="$(settlement_yml_value fallback-delay-slots)"
  note "$SETTLEMENT_CHAIN_ID — federated L1 settlement (ADR-UTXO-009)"
  note "  network               : $NETWORK"
  note "  machine profile       : $profile"
  note "  vault (script)        : $vault"
  note "  nullifier shards      : $shard (16 threads)"
  note "  accepted-root thread  : $root"
  note "  withdrawal address    : $payout (the ONLY claim-forming L2 address)"
  note "  fallback delay        : $delay slots (arms the permissionless exit)"
  # Bootstrapped = the root thread token actually sits at the root address on
  # L1. The validators ship with the distribution, so their presence proves
  # nothing about the deploy.
  if settlement_root_thread_live "$root"; then
    note "  L1 deploy             : bootstrapped (root thread live at the root address)"
  else
    note "  L1 deploy             : NOT bootstrapped — run: ./showcase.sh settlement bootstrap --instance $INSTANCE"
  fi
  chain_status="$(curl -fsS --connect-timeout 2 --max-time 5 \
    "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$SETTLEMENT_CHAIN_ID/status" \
    2>/dev/null || true)"
  if [ -n "$chain_status" ]; then
    tip="$(printf '%s' "$chain_status" | jq -r '.tipHeight // "?"')"
    note "  live tip height       : $tip"
    note "  console               : http://127.0.0.1:$HTTP_BASE/ui/app-chain/eutxo/?chain=$SETTLEMENT_CHAIN_ID"
  else
    note "  live status           : node 0 not reachable on port $HTTP_BASE"
  fi
  note ""
  note "Custody: a Plutus vault released by the FEDERATION THRESHOLD, with"
  note "nullifier shards preventing double settlement and a permissionless"
  note "exit if the federation stops rooting. No single key is custody."
  note "The node settles on its own — there is no operator 'settle' command."
  note ""
  note "  ./showcase.sh settlement bootstrap --instance $INSTANCE   # deploy the L1 identity (once)"
  note "  ./showcase.sh settlement deposit   --instance $INSTANCE   # L1 deposit -> mirrored L2 funds"
  note "  ./showcase.sh settlement withdraw  --instance $INSTANCE   # L2 claim -> watch the node settle"
  note "  ./showcase.sh settlement status    --instance $INSTANCE   # claims and their settlement state"
  if [[ "$profile" == *-devnet ]]; then
    note "DEVNET-ONLY demo profile (relaxed fallback floor); PUBLIC demo keys."
  else
    note "Public-network settlement profile; operator key is supplied by the deployment owner."
  fi
}

# The packaged settlement actors are derived from a PUBLISHED formula, so on
# any public network the operator must bring their own key — exactly like the
# preprod anchor seed.
require_settlement_actors() {
  local action="$1"
  [ "$NETWORK" != devnet ] || return 0
  [ -n "$SETTLEMENT_KEY_FILE" ] || die \
    "settlement $action on $NETWORK needs --settlement-key-file: the packaged demo actors' seeds are public. Generate one:" \
    "  mkdir -m 700 ./private-settlement && (umask 077; openssl rand -hex 32 > ./private-settlement/operator.seed)" \
    "Then: ./showcase.sh settlement prepare --instance $INSTANCE --settlement-key-file \$(pwd)/private-settlement/operator.seed"
  [ "$SETTLEMENT_CONFIRM" = "$NETWORK" ] || die \
    "settlement $action on $NETWORK spends real funds; add --confirm-public-settlement $NETWORK"
}

# Key-file arguments for the demo CLI, empty on the packaged devnet demo.
settlement_key_args() {
  [ -n "$SETTLEMENT_KEY_FILE" ] || return 0
  printf -- '--operator-seed-file %s' "$SETTLEMENT_KEY_FILE"
}

# The nodes' LIVE federation keys — the settlement root datum commits to
# them, so they must come from the running nodes, not a constant. Read them
# from a chain that already EXISTS: the settlement chain is adopted only after
# this identity is deployed, so querying it would always fall back and could
# silently commit to the wrong federation.
settlement_member_args() {
  local members threshold json
  json="$(curl -fsS -H "X-API-Key: $(api_key 0)" \
    "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/${LIGHT_CHAINS[0]}/admin/members" \
    2>/dev/null || true)"
  [ -n "$json" ] || return 0
  members="$(printf '%s' "$json" | jq -r '.members | join(",")' 2>/dev/null || true)"
  threshold="$(printf '%s' "$json" | jq -r '.threshold' 2>/dev/null || true)"
  [ -n "$members" ] && [ "$members" != null ] || return 0
  printf -- '--member-keys %s' "$members"
  [ -n "$threshold" ] && [ "$threshold" != null ] \
    && printf -- ' --threshold %s' "$threshold"
}

# A claim sits at PENDING whether its settlement effect is waiting, retrying,
# or DEAD in the poison lane. Parked effects are NEVER retried, so surface them
# explicitly — otherwise a stuck chain is indistinguishable from a slow one.
settlement_effect_health() {
  local base status effects parked retry failure
  base="http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$SETTLEMENT_CHAIN_ID"
  status="$(curl -fsS -H "X-API-Key: $(api_key 0)" "$base/status" 2>/dev/null || true)"
  [ -n "$status" ] || return 0
  parked="$(printf '%s' "$status" | jq -r '.effects.executor.statusCounts.PARKED // 0' 2>/dev/null || echo 0)"
  retry="$(printf '%s' "$status" | jq -r '.effects.executor.statusCounts.RETRY // 0' 2>/dev/null || echo 0)"
  failure="$(printf '%s' "$status" | jq -r '[.effects.executor.executorOperations[]?.failureCode | select(. != null and . != "NONE")] | first // empty' 2>/dev/null || true)"
  [ "$parked" != 0 ] || [ "$retry" != 0 ] || [ -n "$failure" ] || return 0

  note ""
  note "Settlement queue health:"
  [ "$retry" = 0 ] \
    || note "  $retry effect(s) RETRYING — the node is still trying; no action needed."
  [ -z "$failure" ] || note "  last executor failure code: $failure"
  if [ "$parked" != 0 ]; then
    note "  $parked effect(s) PARKED — DEAD, and they will never retry on their own."
    note "  A claim stays PENDING while its batch is parked, and batches form"
    note "  oldest-first, so every later claim is blocked behind it."
    effects="$(curl -fsS -H "X-API-Key: $(api_key 0)" "$base/effects" 2>/dev/null || true)"
    if [ -n "$effects" ]; then
      printf '%s' "$effects" | jq -r '.effects[]? | "    stuck: \(.type) height=\(.height) ordinal=\(.ordinal) scope=\(.scope)"' 2>/dev/null || true
      printf '%s' "$effects" | jq -r --arg b "$base" '.effects[]? | "    requeue: curl -X POST -H \"X-API-Key: <key>\" \($b)/effects/\(.height)/\(.ordinal)/requeue"' 2>/dev/null || true
    fi
    note "  Diagnose the cause BEFORE requeuing — a requeue repeats the same failure."
    note "  The effect runtime logs the cause at DEBUG."
  fi
}

run_settlement_prepare() {
  [ -n "$SETTLEMENT_KEY_FILE" ] || die \
    "settlement prepare needs --settlement-key-file. Generate one:" \
    "  mkdir -m 700 ./private-settlement && (umask 077; openssl rand -hex 32 > ./private-settlement/operator.seed)"
  "$YANO_HOME/yano.sh" appchain eutxo demo settlement-prepare \
    --network "$NETWORK" --operator-seed-file "$SETTLEMENT_KEY_FILE"
  note "fund the address above, wait for the node to see it, then:"
  note "  ./showcase.sh settlement bootstrap --instance $INSTANCE \\"
  note "    --settlement-key-file $SETTLEMENT_KEY_FILE \\"
  note "    --confirm-public-settlement $NETWORK"
}

run_settlement_bootstrap() {
  require_settlement_actors bootstrap
  if [ -n "$SETTLEMENT_KEY_FILE" ]; then
    note "deploying the settlement identity on the $NETWORK L1 with your own operator key (idempotent)..."
    "$YANO_HOME/yano.sh" appchain eutxo demo settlement-bootstrap \
      --target-base "http://127.0.0.1:$HTTP_BASE" \
      --network "$NETWORK" --operator-seed-file "$SETTLEMENT_KEY_FILE" \
      $(settlement_member_args) \
      --output "$(settlement_script_dir)"
    note "bootstrap complete. It wrote a settlement template and the executor's settings to"
    note "$(settlement_script_dir); seal them as this instance's settlement application with:"
    note "  ./showcase.sh settlement adopt --instance $INSTANCE"
    return 0
  fi
  note "deploying the settlement identity on the devnet L1 (idempotent)..."
  [ -f "$(settlement_sealed_file)" ] || die "this instance has no devnet settlement application"
  "$YANO_HOME/yano.sh" appchain eutxo demo settlement-bootstrap \
    --target-base "http://127.0.0.1:$HTTP_BASE" \
    --network devnet \
    --output "$(settlement_script_dir)"
  # No restart: the devnet plan is deterministic and its validators ship with
  # the distribution, so the executor was already wired at startup.
  note "bootstrap complete — the settlement executor is live. Next:"
  note "  ./showcase.sh settlement deposit --instance $INSTANCE"
}

delegate_eutxo() {
  local command="$1"
  case "$VARIANT" in ledger|bridge) ;; default) VARIANT=ledger;; *) die "eutxo variant must be ledger or bridge";; esac
  local -a extra=()
  case "$command" in
    prepare) command=setup;;
    run)
      command=round-trip
      # Bridge rounds are journaled and idempotent; a rerun produces new
      # activity only with a higher round count.
      [ -z "$COUNT" ] || extra=(--count "$COUNT");;
    up|status|stop|verify) ;;
    *) die "unsupported EUTxO command: $command";;
  esac
  "$YANO_HOME/yano.sh" appchain eutxo demo "$command" --scenario "$VARIANT" \
    --workspace "$(instance_root)/eutxo-$VARIANT" --members "$NODES" \
    --http-port-base "$HTTP_BASE" --server-port-base "$SERVER_BASE" \
    ${extra[@]+"${extra[@]}"}
}

wait_anchor_bootstrapped() {
  local cid="$1" deadline=$(( $(date +%s) + 900 )) status
  while :; do
    status="$(curl -fsS --connect-timeout 2 --max-time 5 \
      "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$cid/status" 2>/dev/null || true)"
    if [ -n "$status" ] && printf '%s' "$status" | jq -e '.anchor.bootstrapped == true' \
        >/dev/null 2>&1; then
      note "Script anchor confirmed for $cid"
      return
    fi
    [ "$(date +%s)" -le "$deadline" ] \
      || die "timed out waiting for the $cid bootstrap to become L1-visible"
    sleep 5
  done
}

usage() {
  cat <<'EOF'
Yano unified app-chain showcase

  ./showcase.sh profiles
  ./showcase.sh describe <light|evidence|eutxo>
  ./showcase.sh doctor [--profile light]
  ./showcase.sh prepare|up|quickstart [--profile light] [--nodes 3|5|7]
  ./showcase.sh status|ui|logs|restart|stop|reset [--instance name]
  ./showcase.sh config show|paths|export <file>
  ./showcase.sh run orders|registry|authenticated-map|approvals|balances|documents|document-review|composite|roles|eutxo|cardano-history|anchor|all
  ./showcase.sh snapshots status|list [chain]
  ./showcase.sh snapshots descriptor|archive|restore|evict <chain> <series> <sequence> [idempotency-key]
  ./showcase.sh snapshots job <chain> <job-id>
  ./showcase.sh authmap put <collection> <key> <value> [--chain id]
  ./showcase.sh authmap governed-put <key> <value> [--chain id] [--collection C] [--actor A] [--seed-file F]
  ./showcase.sh authmap entry <collection> <key> [--chain id]
  ./showcase.sh authmap receipt <message-id> [--chain id]
  ./showcase.sh settlement info                      (vault/shard/root facts + next steps)
  ./showcase.sh settlement bootstrap                 (deploy the L1 settlement identity)
  ./showcase.sh settlement adopt                     (public network: seal the bootstrapped chain)
  ./showcase.sh settlement deposit|withdraw|status   (L1 deposit, L2 claim, autonomous settlement)
  ./showcase.sh approvals propose <id> <payload> [required]
  ./showcase.sh approvals approve <id> <member-node>
  ./showcase.sh composite register-order <key> '<json>'
  ./showcase.sh composite propose <id> '<json>'
  ./showcase.sh composite approve <id> <member-node>
  ./showcase.sh composite release <release> <key> <proposal>
  ./showcase.sh composite verify <release>
  ./showcase.sh load <orders|registry|approvals|documents|document-review|composite> --count N
  ./showcase.sh load-test <orders|registry> --count N [--concurrency N] [--spread]
  ./showcase.sh soak-test orders [--duration SEC] [--rate MSG_PER_SEC] [--spread]
  ./showcase.sh member join <next-index>
  ./showcase.sh governance activate
  ./showcase.sh anchor [chain-id[,chain-id]|all]

The light profile runs the ordinary setup commands (yano.sh appchain new, request, seal, join, start)
for every node; each node is a node directory under data/showcase/<instance>/.

Common options: --instance, --network devnet|preprod, --nodes, --threshold,
--http-base, --server-base, --data-dir, --variant, --anchor-mode, --anchor-chain,
--anchor-key-file, --confirm-public-anchor preprod,
--enable-finalized-message-index[=chain-id,chain-id],
--enable-authenticated-snapshots[=cardano-history-chain],
--authenticated-snapshot-profile mpf-blake2b256-v1|jmt-blake2b256-v1,
--enable-authenticated-snapshot-mpf-pruning[=cardano-history-chain],
--cardano-history-profile params-only|params-stake|params-governance|full,
--l1-source-snapshot-retention-epochs 2..1000,
--disable-cardano-history. Load options:
--concurrency, --payload-bytes, --duration, --rate, --sample, --spread,
--node, and --report-dir.
EOF
}

COMMAND="${1:-help}"; [ "$#" -eq 0 ] || shift
parse "$@"
validate_common

case "$COMMAND" in
  profiles)
    note "light     local multi-chain application (default 3 nodes; supports 1..16)"
    note "evidence  product demo with Kafka, S3-compatible storage, and IPFS (composite|role)"
    note "eutxo     maintained virtual-ledger and bridge demos (ledger|bridge)";;
  describe)
    case "${POSITIONAL[0]:-$PROFILE}" in
      light)
        note "Thirteen-chain reference catalog: foundations, reusable compositions, MPF/JMT proof contrast, Cardano history, and safe L1 settlement."
        python3 "$CATALOG_TOOL" "$CATALOG" describe;;
      evidence) note "Maintained evidence product deployment; Docker required. Evidence is the product, this is its demo harness.";;
      eutxo) note "Maintained experimental EUTxO scenarios; no real funds in the ledger variant.";;
      *) die "unknown profile";; esac;;
  doctor)
    need java; need python3; need curl; need jq
    python3 - <<'PY' >/dev/null \
      || die "Python 3 is missing required standard-library hash support"
import hashlib
hashlib.sha256(b"yano-showcase-doctor").digest()
hashlib.blake2b(b"yano-showcase-doctor", digest_size=32).digest()
PY
    java_version="$(java -version 2>&1 | sed -n '1s/.*version "\([^"]*\)".*/\1/p')"
    case "$java_version" in 25|25.*) ;; *) die "Java 25 is required (found ${java_version:-unknown})";; esac
    [ -f "$YANO_HOME/yano.jar" ] || die "yano.jar missing"
    [ -x "$YANO_HOME/tools/yano-appchain/bin/yano-appchain" ] || die "the app-chain setup tool is missing"
    [ -f "$TEMPLATE" ] && [ -f "$SETTLEMENT_TEMPLATE" ] || die "showcase templates missing"
    [ -x "$SHOWCASE_HOME/tools/loadtest.sh" ] || die "packaged burst load driver missing"
    [ -x "$SHOWCASE_HOME/tools/soaktest.sh" ] || die "packaged soak driver missing"
    plugin_file >/dev/null
    cardano_history_plugin_file >/dev/null
    [ -x "$YANO_HOME/tools/yano-cardano-history/bin/yano-cardano-history" ] \
      || die "packaged Cardano History CLI missing"
    [ "$PROFILE" != evidence ] || need docker
    note "doctor: Java $java_version, $(python3 --version 2>&1), curl, jq, and packaged artifacts are present"
    note "doctor: Python helpers use the standard library only; no pip or virtual environment is needed";;
  prepare)
    case "$PROFILE" in light) prepare_light;; evidence) delegate_evidence prepare;; eutxo) delegate_eutxo prepare;; esac;;
  up)
    case "$PROFILE" in light) adopt_instance; up_light;; evidence) delegate_evidence up;; eutxo) delegate_eutxo up;; esac;;
  quickstart)
    if [ "$PROFILE" = light ]; then
      adopt_instance
      up_light
      [ "$ANCHOR_MODE" != script ] || anchor_chains workflow-chain
      run_composite quickstart
      run_authenticated_map quickstart
      verify_light
      note "Status UI: http://127.0.0.1:$HTTP_BASE/ui/app-chain/"
    else
      case "$PROFILE" in evidence) delegate_evidence prepare; delegate_evidence up; delegate_evidence run;;
        eutxo)
          delegate_eutxo prepare; delegate_eutxo up; delegate_eutxo run;; esac
    fi;;
  status)
    case "$PROFILE" in light) adopt_instance; show_status;; evidence) delegate_evidence status;; eutxo) delegate_eutxo status;; esac;;
  ui)
    adopt_instance; note "http://127.0.0.1:$((HTTP_BASE + NODE))/ui/app-chain/";;
  logs)
    adopt_instance; show_logs;;
  stop)
    case "$PROFILE" in light) adopt_instance; stop_nodes;; evidence) delegate_evidence stop;; eutxo) delegate_eutxo stop;; esac;;
  restart)
    adopt_instance; [ "$PROFILE" = light ] || die "restart currently applies to the light profile"
    stop_nodes; start_nodes;;
  reset)
    [ "$YES" = true ] || die "reset requires --yes and preserves nothing in the named instance"
    local_root="$(instance_root)"; [ -n "$INSTANCE" ] || die "instance is required"
    if [ -d "$local_root" ]; then
      if [ -f "$(instance_file)" ]; then adopt_instance; stop_nodes || true; fi
      rm -rf -- "$local_root"; note "removed $local_root (not recoverable by the showcase)"
    fi;;
  config)
    adopt_instance
    case "${POSITIONAL[0]:-show}" in show) show_config;; paths) config_paths;;
      export) config_export "${POSITIONAL[1]:-}";; *) die "config accepts show, paths, or export";; esac;;
  run)
    adopt_instance
    if [ "$PROFILE" = evidence ]; then
      [ "${POSITIONAL[0]:-}" = roles ] && delegate_evidence role-lifecycle || delegate_evidence run
    elif [ "$PROFILE" = eutxo ]; then delegate_eutxo run
    else
      case "${POSITIONAL[0]:-}" in
        orders) run_orders "${POSITIONAL[1]:-}";; registry) run_registry "${POSITIONAL[1]:-}" "${POSITIONAL[2]:-}";;
        authenticated-map|authmap) run_authenticated_map "${POSITIONAL[1]:-}";;
        approvals) run_approvals "${POSITIONAL[1]:-}";; balances) run_balances;;
        documents) run_documents "${POSITIONAL[1]:-}";; document-review) run_document_review "${POSITIONAL[1]:-}";;
        composite) run_composite "${POSITIONAL[1]:-}";;
        roles) run_roles;; eutxo) run_eutxo;; cardano-history) run_cardano_history;;
        anchor) show_status;;
        all) run_orders; run_registry; run_authenticated_map; run_approvals; run_balances; run_documents; run_document_review; run_composite; run_roles; run_eutxo; [ "$CARDANO_HISTORY_ENABLED" != true ] || run_cardano_history;;
        *) die "unknown run scenario";; esac
    fi;;
  snapshots)
    adopt_instance
    snapshot_operation "${POSITIONAL[0]:-status}" "${POSITIONAL[1]:-$CARDANO_HISTORY_CHAIN_ID}" \
      "${POSITIONAL[2]:-}" "${POSITIONAL[3]:-}" "${POSITIONAL[4]:-}";;
  approvals)
    adopt_instance
    case "${POSITIONAL[0]:-}" in
      propose) approval_propose "${POSITIONAL[1]:?proposal id required}" "${POSITIONAL[2]:?payload required}" "${POSITIONAL[3]:-2}";;
      approve) approval_approve "${POSITIONAL[1]:?proposal id required}" "${POSITIONAL[2]:?member node required}";;
      *) die "approvals accepts propose or approve";; esac;;
  composite)
    adopt_instance
    case "${POSITIONAL[0]:-}" in
      register-order) composite_register "${POSITIONAL[1]:?order key required}" "${POSITIONAL[2]:?order JSON required}";;
      propose) composite_propose "${POSITIONAL[1]:?proposal id required}" "${POSITIONAL[2]:?order JSON required}";;
      approve) composite_approve "${POSITIONAL[1]:?proposal id required}" "${POSITIONAL[2]:?member node required}";;
      release) composite_release "${POSITIONAL[1]:?release id required}" "${POSITIONAL[2]:?order key required}" "${POSITIONAL[3]:?proposal id required}";;
      verify) composite_verify "${POSITIONAL[1]:?release id required}";;
      *) die "unknown composite operation";; esac;;
  load)
    adopt_instance
    [ -n "$COUNT" ] && [[ "$COUNT" =~ ^[0-9]+$ ]] && [ "$COUNT" -ge 1 ] || die "load requires --count N"
    for ((load_index=1;load_index<=COUNT;load_index++)); do
      suffix="load-$(date +%s)-$load_index"
      case "${POSITIONAL[0]:-}" in orders) run_orders "$suffix";; registry) run_registry "$suffix" "value-$load_index";;
        approvals) run_approvals "$suffix";; documents) run_documents "$suffix";;
        document-review) run_document_review "$suffix";; composite) run_composite "$suffix";;
        *) die "unsupported load scenario";; esac
    done;;
  authmap)
    adopt_instance
    case "${POSITIONAL[0]:-}" in
      put) run_authmap_put "${POSITIONAL[1]:?collection required}" \
        "${POSITIONAL[2]:?key required}" "${POSITIONAL[3]:?value required}";;
      governed-put) run_authmap_governed_put \
        "${POSITIONAL[1]:?key required}" "${POSITIONAL[2]:?value required}";;
      entry) run_authmap_entry "${POSITIONAL[1]:?collection required}" \
        "${POSITIONAL[2]:?key required}";;
      receipt) run_authmap_receipt "${POSITIONAL[1]:?message id required}";;
      *) die "usage: authmap put <collection> <key> <value> | authmap governed-put <key> <value> [--collection C] [--actor A] [--seed-file F] | authmap entry <collection> <key> | authmap receipt <message-id>";;
    esac;;
  settlement)
    adopt_instance
    [ "$PROFILE" = light ] || die "settlement applies to the light profile (the eutxo profile has its own scenarios)"
    case "${POSITIONAL[0]:-}" in
      info) run_settlement_info;;
      prepare) run_settlement_prepare;;
      bootstrap) run_settlement_bootstrap;;
      adopt) run_settlement_adopt;;
      deposit)
        require_settlement_actors deposit
        "$YANO_HOME/yano.sh" appchain eutxo demo settlement-deposit \
          --target-base "http://127.0.0.1:$HTTP_BASE" \
          --network "$NETWORK" $(settlement_key_args);;
      withdraw)
        require_settlement_actors withdraw
        "$YANO_HOME/yano.sh" appchain eutxo demo settlement-withdraw \
          --target-base "http://127.0.0.1:$HTTP_BASE" \
          --network "$NETWORK" $(settlement_key_args)
        note "claim created — the federation settles it on its own; watch with:"
        note "  ./showcase.sh settlement status --instance $INSTANCE";;
      status)
        "$YANO_HOME/yano.sh" appchain eutxo demo settlement-status \
          --target-base "http://127.0.0.1:$HTTP_BASE"
        settlement_effect_health;;
      *) die "usage: settlement info|prepare|bootstrap|adopt|deposit|withdraw|status";;
    esac;;
  load-test)
    adopt_instance; run_load_test "${POSITIONAL[0]:-orders}";;
  soak-test)
    adopt_instance; run_soak_test "${POSITIONAL[0]:-orders}";;
  verify)
    adopt_instance
    [ "$PROFILE" = light ] && verify_light || { [ "$PROFILE" = evidence ] && delegate_evidence verify || delegate_eutxo verify; };;
  member)
    adopt_instance; [ "${POSITIONAL[0]:-}" = join ] || die "usage: member join <next-index>"
    member_join "${POSITIONAL[1]:?node index required}";;
  governance)
    adopt_instance; [ "${POSITIONAL[0]:-}" = activate ] \
      || die "usage: governance activate"
    [ "$PROFILE" = light ] || die "governance activate applies to the light profile"
    governance_activate;;
  anchor)
    adopt_instance; [ "$PROFILE" = light ] || die "anchor applies to the light profile"
    anchor_chains "${POSITIONAL[0]:-workflow-chain}";;
  help|-h|--help) usage;;
  *) usage; exit 1;;
esac
