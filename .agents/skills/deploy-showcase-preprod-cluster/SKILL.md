---
name: deploy-showcase-preprod-cluster
description: Safely stage, start, anchor, smoke-test, and validate the Yano X three-node preprod showcase from the retained L1 chainstate and owner-supplied anchor/settlement keys, on HTTP 17070-17072. Use to create or resume the preprod cluster, import /Users/satya/Downloads/yano-cluster/chainstate-preprod into three nodes, deploy the production settlement chain, bootstrap SCRIPT anchors, or rerun the preprod validation.
---

# Deploy Showcase Preprod Cluster

This workflow can submit real preprod transactions. Keep read-only resume and
validation separate from fresh deployment or smoke traffic.

## Prepare safely

1. Announce the public-network effects before a fresh deployment or smoke run.
2. Read `AGENTS.md`, `examples/showcase/DEMO_SHOWCASE.md`,
   `examples/showcase/docs/PREPROD_SETTLEMENT.md`, and the target's
   `PREPROD_CLUSTER.md` when it exists.
3. Inspect repository status and preserve unrelated work. Never commit
   automatically.
4. Confirm Java 25, `curl`, `jq`, `python3`, and `unzip`.
5. Treat an existing target as retained identity and data. Never replace,
   reset, or regenerate it implicitly. A clean redeploy uses a new reviewed
   `--target`-style directory or a reviewed backup of the old one.

## Fixed inputs and defaults

- Source L1 chainstate: `/Users/satya/Downloads/yano-cluster/chainstate-preprod`
- Source keys: `/Users/satya/Downloads/yano-cluster/keys` (`anchor.seed`,
  `operator.seed`, and the retained public settlement deployment record)
- Target: `/Users/satya/Downloads/yano-cluster/preprod-cluster`
- Instance: `preprod`
- HTTP: `17070-17072`
- N2N: `17337-17339`
- Nodes/threshold: `3/2`
- Cardano History: `params-stake` profile, L1 source snapshot retention of the
  latest two completed boundaries; experimental MPF pruning stays disabled.

## Build a current archive

Build the integrated JVM distribution exactly as `$deploy-showcase-devnet-cluster`
describes. The showcase runs from the extracted archive's `examples/showcase/`
directory. Never extract it over an existing target.

## Resume an existing deployment

Read-only resume and validation spend nothing:

```bash
cd /Users/satya/Downloads/yano-cluster/preprod-cluster
./showcase.sh status --instance preprod
./showcase.sh up --instance preprod
```

## Create a fresh deployment

Only with an explicit user confirmation of the public-network effects:

1. Copy `anchor.seed` and `operator.seed` into `private-anchor/` and
   `private-settlement/` of the new target with owner-only permissions
   (`mkdir -m 700`, `chmod 600`). Check only existence, mode, and derived
   addresses; never print or inspect seed contents.
2. Prepare the instance without starting it:

   ```bash
   ./showcase.sh prepare --profile light --network preprod --nodes 3 \
     --instance preprod --http-base 17070 --server-base 17337 \
     --cardano-history-profile params-stake \
     --l1-source-snapshot-retention-epochs 2 \
     --anchor-chain all --anchor-key-file "$PWD/private-anchor/anchor.seed" \
     --confirm-public-anchor preprod
   ```

3. Import the retained L1 state into every node with APFS clone-on-write
   (`cp -cR`) at `data/showcase/preprod/node<N>/data/chainstate`
   (`./showcase.sh config paths --instance preprod` lists the node
   directories). Exclude any legacy `chainstate/appchains` content: derived
   app-chain data never enters L1 chainstate. No app-chain state or identity is
   copied.
4. Start and anchor:

   ```bash
   ./showcase.sh up --instance preprod
   ./showcase.sh anchor all --instance preprod --confirm-public-anchor preprod
   ```

5. Deploy the operator-specific production settlement identity
   (`examples/showcase/docs/PREPROD_SETTLEMENT.md`):

   ```bash
   ./showcase.sh settlement prepare --instance preprod \
     --settlement-key-file "$PWD/private-settlement/operator.seed"
   ./showcase.sh settlement bootstrap --instance preprod \
     --settlement-key-file "$PWD/private-settlement/operator.seed" \
     --confirm-public-settlement preprod
   ./showcase.sh settlement adopt --instance preprod
   ```

   When the source keys already hold a retained settlement deployment record,
   reuse it: the imported L1 snapshot can predate the already-submitted one-shot
   mint, so never select a replacement seed UTxO or a second settlement identity.
6. Smoke traffic advances anchors, so it needs the same explicit confirmation:
   `./showcase.sh run orders|registry|authenticated-map|document-review --instance preprod`.

## Validate

Run the shared read-only three-node validator, then check the settlement chain:

```bash
bash .agents/skills/deploy-showcase-devnet-cluster/scripts/validate_cluster.sh \
  /Users/satya/Downloads/yano-cluster/preprod-cluster preprod 17070
curl -fsS http://127.0.0.1:17070/api/v1/app-chain/chains/payment-chain-settlement/status \
  | jq -e '.stateCommitment.profile == "mpf-blake2b256-v1" and
           .stateCommitment.implementation.verificationTarget == "off-chain-and-on-chain" and
           .observers["bridge-deposits"].type == "eutxo-vault-deposit-v1" and
           .effects.executor.executors == ["eutxo-settlement"]'
./showcase.sh settlement status --instance preprod
```

Review the first relevant `ERROR` in each node log if validation fails. Leave the
cluster running unless the user asks otherwise.

## Safety and proof rules

- Require explicit confirmation before anchor bootstrap, settlement bootstrap,
  or smoke messages that can advance an anchor.
- Never regenerate retained state, anchor, or settlement identity.
- Require the three nodes to agree on tip/root/profile/genesis/capability
  manifest digest for each chain. Only node 0 needs an anchor-writer view.
- Require the retained chain order and capability manifests to match the
  packaged catalog.
- Reject a derived-index checkpoint above its authoritative app-chain tip and
  any legacy derived index below the L1 chainstate root.
- A caller-pinned proof verifies mechanics, not L1 trust. Call a proof anchored
  only after separately validating the referenced Cardano transaction/datum.
- Do not use settlement deposit/withdraw as health checks; they exercise real
  preprod custody flows.
