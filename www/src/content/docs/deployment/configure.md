---
title: "Configure your application"
description: "Use the App-Chain Studio to choose recipes and capabilities, or use the CLI from an extracted Yano X JVM release. Studio ships in the release under…"
editUrl: false
---

:::note[Imported page]
This page is generated from [`docs/appchain/deployment/configure.md`](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/deployment/configure.md)
in the Yano X repository, which is its source of truth.
:::

Use the [App-Chain Studio](/studio/index.html) to
choose recipes and capabilities, or use the CLI from an extracted Yano X JVM
release. Studio ships in the release under `studio/` and is also served by the
documentation site. Serve the packaged directory over local HTTP to use it offline.

## 1. Choose chains and member nodes

In Studio, give each chain a unique ID. Add a chain for another independent
application: for example, an order log plus a document trail. Select capabilities
on the chain that uses them. Network, node count, public member keys, VM hosts,
and ports belong to the shared deployment. Finality and membership policy may
differ between chains.

The initial project compiler supports 1–32 chains on the same member nodes.
This is an authoring bound, not a throughput or capacity guarantee. All chains
must agree on shared node placement. Plugin providers must coexist in the node's
catalog; alternative implementations of the same contribution require separate
node deployments.

Download `appchain.yaml` into a new directory. It contains public intent, not
private keys. Custom catalogs require their original signed snapshots at the
paths recorded in the blueprint. The matching CLI performs authoritative validation.

## 2. Prepare a local devnet

For a CLI-only start, create one chain:

```bash
# Run from the extracted Yano X JVM distribution.
export YANO_HOME="$PWD"
./yano.sh appchain init --non-interactive --recipe audit-log \
  --network devnet --members 3 --name my-application --chain-id orders \
  --output ./my-application
./yano.sh appchain prepare ./my-application
./my-application/scripts/start
```

For a Studio download, set `YANO_HOME` to the extracted release and use:

```bash
"$YANO_HOME/yano.sh" appchain prepare /path/to/my-application
/path/to/my-application/scripts/start
```

`prepare` generates independent local member keys, pins their public keys in
every chain, and writes private `secrets/nodeN.env` files with owner-only
permissions. Repeating preparation reuses the same keys. It refuses to regenerate
identities beside retained data. It supports local JVM devnet projects; remote
operators provide their own identity and secret files.

Generated configuration is locked. Edit `appchain.yaml`, not `config/` or scripts.
Once a project starts, changes require the explicit plan/apply workflow.

## 3. Submit and verify an order

The generated default HTTP ports are 8080–8082. In a terminal where `YANO_HOME`
is set, read the local API key into the environment without printing it:

```bash
set -a
. ./my-application/secrets/node0.env
set +a

RESPONSE=$(curl -fsS -X POST \
  http://127.0.0.1:8081/api/v1/app-chain/chains/orders/messages \
  -H "X-API-Key: $YANO_APPCHAIN_API_KEYS" -H 'Content-Type: application/json' \
  -d '{"topic":"orders","body":"order A-100 created"}')
MESSAGE_ID=$(printf '%s' "$RESPONSE" | jq -er .messageId)
printf 'Accepted message %s\n' "$MESSAGE_ID"
```

Wait up to 60 seconds for finalization, retaining the message ID:

```bash
HEIGHT=0
for attempt in $(seq 1 60); do
  HEIGHT=$(curl -fsS -H "X-API-Key: $YANO_APPCHAIN_API_KEYS" \
    "http://127.0.0.1:8080/api/v1/app-chain/chains/orders/messages/$MESSAGE_ID" \
    | jq -r '.height // 0') || HEIGHT=0
  [ "$HEIGHT" -gt 0 ] && break
  sleep 1
done
[ "$HEIGHT" -gt 0 ] || { echo "Message did not finalize within 60 seconds" >&2; exit 1; }
curl -fsS -H "X-API-Key: $YANO_APPCHAIN_API_KEYS" \
  "http://127.0.0.1:8080/api/v1/app-chain/chains/orders/blocks/$HEIGHT" | jq .
```

The block includes the finalized root and certificate signature count. A successful
submission alone does not establish finality.

Compare the configured identity on every member:

```bash
"$YANO_HOME/yano.sh" appchain drift ./my-application \
  --peer http://127.0.0.1:8080/api/v1/ \
  --peer http://127.0.0.1:8081/api/v1/ \
  --peer http://127.0.0.1:8082/api/v1/ \
  --api-key-env YANO_APPCHAIN_API_KEYS
```

Drift now covers **every configured chain**. `DRIFT_OK` checks deployment and
consensus identity agreement; it is not proof of message finality or independent
L1 anchoring. Follow [the proof guide](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/PROOF_LAB.md) to retrieve and verify the
message proof, pinning its chain, genesis, height, and trusted root.

Per-chain instructions are under `chains/<chain-id>/docs/` for multi-chain
projects. The application prerequisite plan lists all recipes, capabilities,
required services, and bootstrap stages. Advanced recipes may remain pending
until their external prerequisites are satisfied.

## 4. Stop or extend

```bash
./my-application/scripts/stop
./my-application/scripts/start
```

Stop preserves state. Do not replace the local devnet genesis or member secrets.
Continue with [adding a chain](/deployment/add-chain/) or [remote operations](/deployment/operators/).
