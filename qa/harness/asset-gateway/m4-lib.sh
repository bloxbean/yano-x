#!/bin/bash
# ADR-059 M4 devnet drills: shared helpers (QA only; not part of release-qa.sh).
# A three-node devnet: A is the L1 block producer, the app-chain proposer and the anchor leader; B and C are L1
# followers of A and app-chain members. C is the asset-settlement owner; B is a non-owner exit relayer.
# Usage: source this file with JAR, RUN (node data directory) and GW (the GatewayDevnet launcher) set.
# Ports: HTTP 7371-7373, N2N 13641-13643 (override HTTP_A.., N2N_A..).
HARNESS_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SP=${SP:-$RUN}
source "$HARNESS_DIR/appchain-common.sh"
HTTP_C=${HTTP_C:-7373}
N2N_C=${N2N_C:-13643}
C=http://localhost:$HTTP_C/api/v1
SEED_C=0505050505050505050505050505050505050505050505050505050505050505
PUB_C=6e7a1cdd29b0b78fd13af4c5598feff4ef2a97166e3ca6f2e4fbfccd80505bf1
L1_GENESIS_ID=a0f766a5508a14c7574c8ab6c46b95158ea52f45b790b7f621095beb5e69b6a0
export GW_DIR=$RUN/gw
mkdir -p "$GW_DIR"

gw() { # the tool against this cluster's node A unless GW_L1 / GW_APP say otherwise
  GW_L1=${GW_L1:-http://localhost:$HTTP_A/api/v1/} GW_APP=${GW_APP:-http://localhost:$HTTP_A/api/v1/app-chain/} \
    "$GW" "$@" 2> >(grep -v SLF4J >&2)
}

# The pinned genesis (fixed systemStart, so every node and the anchor profile agree on slot time).
make_genesis() { # <genesis-time-ms>
  local iso; iso=$(jq -nr --argjson t $(( $1 / 1000 )) '$t | todate')
  # Epochs of 3600 slots (12 min): Plutus time translation only reaches about one epoch ahead, and a gateway
  # validity window may span 10 minutes.
  jq --arg s "$iso" --argjson e "${EPOCH_LENGTH:-3600}" '.epochLength = $e | .systemStart = $s' "$DIST/config/network/devnet/shelley-genesis.json" \
    > "$RUN/genesis.json"
}

l1_args() { # <node a|b|c>
  echo "-Dquarkus.profile=devnet -Dyano.genesis.shelley-genesis-file=$RUN/genesis.json"
  echo "-Dyano.history.projection.enabled=false ${EXTRA_L1_ARGS:-}"
  # The settlement tasks report each pass (Accept, relayed Exit) only at DEBUG.
  echo '-Dquarkus.log.category."org.yanoproject.x.assets".level=DEBUG'
  if [ "$1" = a ]; then
    echo "-Dyano.block-producer.genesis-timestamp=$(cat "$RUN/genesis-time-ms")"
  else
    echo "-Dyano.block-producer.enabled=false -Dyano.dev-mode=false -Dyano.client.enabled=true"
    echo "-Dyano.remote.host=localhost -Dyano.remote.port=$N2N_A"
  fi
}

app_args() { # <node a|b|c>
  local n=$1 seed peers T; T=$(cat "$RUN/genesis-time-ms")
  case $n in
    a) seed=$SEED_A; peers=localhost:$N2N_B,localhost:$N2N_C ;;
    b) seed=$SEED_B; peers=localhost:$N2N_A,localhost:$N2N_C ;;
    c) seed=$SEED_C; peers=localhost:$N2N_A,localhost:$N2N_B ;;
  esac
  cat <<EOF
-Dyano.app-chain.enabled=true
-Dyano.app-chain.chain-id=$(grep '^chain-id=' "$GW_DIR/deploy.properties" | cut -d= -f2)
-Dyano.app-chain.members=$PUB_A,$PUB_B,$PUB_C
-Dyano.app-chain.sequencer.proposer=$PUB_A
-Dyano.app-chain.threshold=2
-Dyano.app-chain.block.interval-ms=1000
-Dyano.app-chain.l1.stability-depth=4
-Dyano.app-chain.observation.l1-network-genesis-id=$L1_GENESIS_ID
-Dyano.app-chain.anchor-profile.version=2
-Dyano.app-chain.anchor-profile.slot-zero=0
-Dyano.app-chain.anchor-profile.slot-zero-time-ms=$T
-Dyano.app-chain.anchor-profile.slot-length-ms=200
-Dyano.app-chain.state.commitment-profile=mpf-blake2b256-v1
-Dyano.app-chain.state.format-fingerprint=$FPRINT
-Dyano.app-chain.state.genesis-id=$GENID
-Dyano.app-chain.storage.path=$RUN/$n/appchain
-Dyano.app-chain.api.keys=$APIKEY
-Dyano.app-chain.signing-key=$seed
-Dyano.app-chain.peers=$peers
-Dyano.app-chain.effects.enabled=true
-Dyano.app-chain.effects.executor.enabled=true
-Dyano.app-chain.effects.executor.tick-ms=500
EOF
  if [ "$n" = a ]; then
    cat <<EOF
-Dyano.app-chain.anchor.enabled=true
-Dyano.app-chain.anchor.signing-key=$SEED_ANCHOR
-Dyano.app-chain.anchor.mode=script
-Dyano.app-chain.anchor.every-blocks=${ANCHOR_EVERY:-4}
-Dyano.app-chain.anchor.max-interval-minutes=${ANCHOR_MAX_MIN:-2}
-Dyano.app-chain.anchor.validity-slots=150
EOF
  fi
  gw args "$n"
}

http_port() { case $1 in a) echo $HTTP_A ;; b) echo $HTTP_B ;; c) echo $HTTP_C ;; esac; }
n2n_port() { case $1 in a) echo $N2N_A ;; b) echo $N2N_B ;; c) echo $N2N_C ;; esac; }

# start_node <a|b|c> <l1only|app>: writes the node's argument file and starts it (java @argfile).
start_node() {
  local n=$1 mode=$2; local argfile="$RUN/node-$n.args"
  # One argument per line, each quoted: a java @argfile treats an unquoted # as the start of a comment.
  { l1_args "$n"; [ "$mode" = app ] && app_args "$n"; } | tr ' ' '\n' | grep -v '^$' | awk '{ q = index($0, "\"") ? "\047" : "\""; print q $0 q }' \
    > "$argfile"
  local http n2n; http=$(http_port "$n"); n2n=$(n2n_port "$n")
  mkdir -p "$RUN/$n"
  cd "$DIST" || return 1
  "$JAVA" -Dquarkus.http.port=$http -Dyano.server.port=$n2n -Dyano.storage.path=$RUN/$n/chainstate \
    -Dyano.history.dir=$RUN/$n/history -Dquarkus.log.file.path=$RUN/$n/yano-file.log \
    @"$argfile" -jar "$JAR" >> "$RUN/node-$n.log" 2>&1 &
  local pid=$!
  cd - >/dev/null
  echo "$pid" > "$RUN/node-$n.pid"
  track_pid $pid "yano-$n"
  log "started node $n ($mode) pid=$pid http=$http n2n=$n2n"
}

stop_node() { # <a|b|c>
  local pid; pid=$(cat "$RUN/node-$1.pid" 2>/dev/null) || return 0
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid"; for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -9 "$pid" 2>/dev/null
    log "stopped node $1 ($pid)"
  fi
}

st() { curl -s "http://localhost:$(http_port "$1")/api/v1/app-chain/status"; }
tip() { curl -s "http://localhost:$(http_port "$1")/api/v1/app-chain/tip" | jq -c .; }
l1tip() { yano_tip "$(http_port "$1")" | jq -c '{slot,blockNumber}'; }
gwn() { # <node> <gw args...>: run the tool against node n's REST
  local p; p=$(http_port "$1"); shift
  GW_L1=http://localhost:$HTTP_A/api/v1/ GW_APP=http://localhost:$p/api/v1/app-chain/ gw "$@"
}
