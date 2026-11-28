#!/bin/bash
# Runs one Yano X node that follows a running Yaci DevKit devnet (as an L1 client of DevKit's cardano-node) and
# hosts a single-member asset-payments app chain with an ADA vault and a display-only token vault. For the
# reference client's manual use and its Playwright E2E. QA only: the vault script is not deployed, so ADA sent to
# the vault address stays there (devnet funds); withdrawals reserve but are never paid (no payout observer runs).
#
#   DIST=<unpacked yano-x-jvm-<version>> RUN=<state dir> e2e/devkit-node.sh start|stop|status|args
#
# Environment (defaults in brackets): DEVKIT_CONTAINER [node1-yaci-cli-1] (to copy DevKit's genesis files),
# DEVKIT_HOST [localhost], DEVKIT_PORT [3001], HTTP [7470], N2N [13740], CHAIN [payments-devkit-1], JAVA [java].
set -euo pipefail
: "${DIST:?DIST must be an unpacked yano-x-jvm distribution}" "${RUN:?RUN must be a state directory}"
DEVKIT_CONTAINER=${DEVKIT_CONTAINER:-node1-yaci-cli-1}
DEVKIT_HOST=${DEVKIT_HOST:-localhost}
DEVKIT_PORT=${DEVKIT_PORT:-3001}
HTTP=${HTTP:-7470}
N2N=${N2N:-13740}
CHAIN=${CHAIN:-payments-devkit-1}
JAVA=${JAVA:-java}
# Fixed public devnet member key (the QA harness's member A); never use it on a real network.
SEED=0101010101010101010101010101010101010101010101010101010101010101
PUB=8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c
STATE_GENESIS=c2b9c92a865dfa7c218a1a6e49f1dd88163372e40466009876458c01609d0d70
FPRINT=91ee14091200f1e24659112d640e877e9177779dcc81dd06117f013e9190082b
ADA_SCRIPT=e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2
TOKEN_SCRIPT=e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3
TOKEN=f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0.5553444d
mkdir -p "$RUN"

# An enterprise script address on the test network for a 28-byte script hash (CIP-19 type 7, CIP-5 bech32).
script_address() {
  python3 - "$1" <<'PY'
import sys
CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
def polymod(values):
    gen = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]
    chk = 1
    for v in values:
        top = chk >> 25
        chk = (chk & 0x1ffffff) << 5 ^ v
        for i in range(5):
            chk ^= gen[i] if ((top >> i) & 1) else 0
    return chk
def convert(data):
    acc = bits = 0; out = []
    for b in data:
        acc = (acc << 8) | b; bits += 8
        while bits >= 5:
            bits -= 5; out.append((acc >> bits) & 31)
    if bits: out.append((acc << (5 - bits)) & 31)
    return out
hrp = "addr_test"
data = convert(bytes([0x70]) + bytes.fromhex(sys.argv[1]))
expanded = [ord(c) >> 5 for c in hrp] + [0] + [ord(c) & 31 for c in hrp]
mod = polymod(expanded + data + [0] * 6) ^ 1
print(hrp + "1" + "".join(CHARSET[d] for d in data + [(mod >> 5 * (5 - i)) & 31 for i in range(6)]))
PY
}

genesis() {
  if [ ! -f "$RUN/genesis/shelley-genesis.json" ]; then
    mkdir -p "$RUN/genesis"
    for file in shelley byron alonzo conway; do
      docker cp "$DEVKIT_CONTAINER:/clusters/nodes/default/node/genesis/$file-genesis.json" "$RUN/genesis/" >/dev/null
    done
  fi
}

args() {
  genesis
  local ada token l1id
  ada=$(script_address "$ADA_SCRIPT")
  token=$(script_address "$TOKEN_SCRIPT")
  l1id=$(python3 -c "import hashlib,sys; print(hashlib.blake2b(open(sys.argv[1],'rb').read(), digest_size=32).hexdigest())" \
    "$RUN/genesis/shelley-genesis.json")
  local m=yano.app-chain.machines.asset-payments. o=yano.app-chain.observers.deposits.
  cat <<EOF
-Dquarkus.profile=devnet
-Dquarkus.http.port=$HTTP
-Dyano.server.port=$N2N
-Dyano.storage.path=$RUN/chainstate
-Dyano.history.dir=$RUN/history
-Dquarkus.log.file.path=$RUN/yano-file.log
-Dyano.plugins.directory=$DIST/plugins
-Dyano.history.projection.enabled=false
-Dyano.block-producer.enabled=false
-Dyano.dev-mode=false
-Dyano.client.enabled=true
-Dyano.remote.host=$DEVKIT_HOST
-Dyano.remote.port=$DEVKIT_PORT
-Dyano.genesis.shelley-genesis-file=$RUN/genesis/shelley-genesis.json
-Dyano.genesis.byron-genesis-file=$RUN/genesis/byron-genesis.json
-Dyano.genesis.alonzo-genesis-file=$RUN/genesis/alonzo-genesis.json
-Dyano.genesis.conway-genesis-file=$RUN/genesis/conway-genesis.json
-Dyano.app-chain.enabled=true
-Dyano.app-chain.chain-id=$CHAIN
-Dyano.app-chain.members=$PUB
-Dyano.app-chain.sequencer.proposer=$PUB
-Dyano.app-chain.threshold=1
-Dyano.app-chain.signing-key=$SEED
-Dyano.app-chain.block.interval-ms=1000
-Dyano.app-chain.l1.stability-depth=2
-Dyano.app-chain.observation.l1-network-genesis-id=$l1id
-Dyano.app-chain.state.commitment-profile=mpf-blake2b256-v1
-Dyano.app-chain.state.format-fingerprint=$FPRINT
-Dyano.app-chain.state.genesis-id=$STATE_GENESIS
-Dyano.app-chain.storage.path=$RUN/appchain
-Dyano.app-chain.state-machine=asset-payments
-D${m}domain.address-network-id=0
-D${m}domain.network-magic=42
-D${m}domain.genesis-id=$STATE_GENESIS
-D${m}deposit-observer-id=deposits
-D${m}payout-observer-id=payouts
-D${m}min-stability-depth=2
-D${m}vaults.0.asset=lovelace
-D${m}vaults.0.script-hash=$ADA_SCRIPT
-D${m}vaults.0.address=$ada
-D${m}vaults.0.ticker=ADA
-D${m}vaults.0.decimals=6
-D${m}vaults.1.asset=$TOKEN
-D${m}vaults.1.script-hash=$TOKEN_SCRIPT
-D${m}vaults.1.address=$token
-D${m}vaults.1.ticker=USDM
-D${m}vaults.1.decimals=6
-D${o}type=asset-vault-deposit-v1
-D${o}domain.address-network-id=0
-D${o}domain.network-magic=42
-D${o}domain.chain-id=$CHAIN
-D${o}domain.genesis-id=$STATE_GENESIS
-D${o}min-stability-depth=2
-D${o}vaults.0.asset=lovelace
-D${o}vaults.0.script-hash=$ADA_SCRIPT
-D${o}vaults.0.address=$ada
-D${o}vaults.1.asset=$TOKEN
-D${o}vaults.1.script-hash=$TOKEN_SCRIPT
-D${o}vaults.1.address=$token
EOF
}

case "${1:-}" in
  args) args ;;
  start)
    if [ -f "$RUN/node.pid" ] && kill -0 "$(cat "$RUN/node.pid")" 2>/dev/null; then echo "already running"; exit 0; fi
    # One argument per line, quoted: a java @argfile treats an unquoted # as a comment.
    args | awk '{ print "\"" $0 "\"" }' > "$RUN/node.args"
    (cd "$DIST" && exec "$JAVA" @"$RUN/node.args" -jar "$DIST/yano.jar" >> "$RUN/node.log" 2>&1) &
    echo $! > "$RUN/node.pid"
    echo "started pid $(cat "$RUN/node.pid"); API http://localhost:$HTTP/api/v1 chain $CHAIN; log $RUN/node.log"
    ;;
  stop)
    if [ -f "$RUN/node.pid" ]; then kill "$(cat "$RUN/node.pid")" 2>/dev/null || true; rm -f "$RUN/node.pid"; fi
    ;;
  status)
    curl -fsS "http://localhost:$HTTP/api/v1/app-chain/chains" && echo
    curl -fsS "http://localhost:$HTTP/api/v1/plugins/org.yanoproject.x.asset-payments/chains/$CHAIN/payments/info" \
      | python3 -c 'import json,sys; d=json.load(sys.stdin); print("payments height", d["committed"]["height"], [v["ticker"] for v in d["vaults"]])'
    ;;
  *) echo "usage: DIST=... RUN=... $0 start|stop|status|args" >&2; exit 2 ;;
esac
