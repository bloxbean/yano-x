# Preprod settlement (`payment-chain-settlement`)

Deploying a settlement identity on a public network with **your own** operator
key. For the zero-config devnet demo see [SETTLEMENT_CHAIN.md](SETTLEMENT_CHAIN.md);
this page is the public-network operator checklist, and it mirrors
[PREPROD_ANCHORING.md](PREPROD_ANCHORING.md).

> **Why a key of your own.** The packaged settlement actors are derived from a
> published formula — `sha256("yano-showcase-demo-actor:…")` — so their seeds
> are public and anyone can spend their funds. They exist to make the devnet
> demo reproducible. Every command below refuses to run on a public network
> without `--settlement-key-file`.

## 1. Generate the operator key

One lowercase 64-hex raw Ed25519 seed, owner-only. A mnemonic or a CIP-1852
account is **not** the raw seed this option expects.

```bash
mkdir -m 700 ./private-settlement
(umask 077; openssl rand -hex 32 > ./private-settlement/operator.seed)
chmod 600 ./private-settlement/operator.seed
```

Everything else is derived from that one file by domain-separated hashing: the
L2 depositor, the claim-forming withdrawal address, and the payout
destination (which defaults to the operator's own address, so settled funds
return to the key that paid for the deployment).

## 2. Start the nodes

(Step 3 needs no node — run it first and fund the address while preprod syncs.)

```bash
./showcase.sh up --network preprod --nodes 3 --instance preprod-demo
```

On a non-devnet instance `prepare` **leaves out** the packaged devnet
`settlement` application, and says so. That is deliberate: if the devnet chain
ever activated on preprod, its chain-id would retain the devnet profile digest
and your real identity could never be adopted under the same id.

## 3. Find out where to send the tADA

Run this **first**, before the nodes are even started. It contacts no node at
all — the address and the amount derive from your key alone — so it works
instantly and cannot fail because preprod is still syncing. Fund the address
while the node catches up.

```bash
./showcase.sh settlement prepare --instance preprod-demo \
  --settlement-key-file "$(pwd)/private-settlement/operator.seed"
```

It prints the operator address and the exact required lovelace, computed from
the genesis outputs rather than estimated:

```
FundThisAddress  : addr_test1v...
RequiredAda      : 62
MinimumUtxos     : 2
Profile          : yano-eutxo-v3-bridge-settlement
```

Fund that address externally with **at least two pure-ADA UTxOs** — each
one-shot mint consumes its own input. Most of the total is recoverable: it
ends up in the vault and the thread outputs, not in fees.

There is no separate "am I funded yet?" command: `settlement bootstrap` is
safe to run at any point. Until the funding is visible to a synced node it
reports exactly what is missing — for example *"holds 0 ADA across 0 pure-ADA
UTxO(s); the deploy needs 62 ADA across at least 2"* — and submits nothing.

## 4. Deploy

```bash
./showcase.sh settlement bootstrap --instance preprod-demo \
  --settlement-key-file "$(pwd)/private-settlement/operator.seed" \
  --confirm-public-settlement preprod
```

This deploys on the **production** profile `yano-eutxo-v3-bridge-settlement`
(fallback floor 21,600 slots ≈ 6 h — the real safety window; the relaxed
60-slot devnet profile is refused off devnet by the state machine itself). It
commits to the chain's **live** federation keys and threshold, read from the
running chain, so the vault releases only on that federation's threshold.

The deploy is **resumable**. The chosen one-shot seed outpoints are recorded
in `private-settlement/settlement-deployment-payment-chain-settlement.properties`
before anything is submitted; a re-run after a partial failure completes the
same identity instead of deploying a second one. Delete that file only if you
intend to deploy a *new* identity.

## 5. Adopt the generated settlement application

The vault, shard, and root addresses are parameterized by your seed outpoints,
so the settlement chain cannot ship with the distribution. `bootstrap` writes
it to `data/showcase/preprod-demo/settlement-bootstrap/`:

- `settlement.template.yaml` — the chain as an app-chain template (profile,
  bridge addresses, observers);
- `executor.json` — the settlement executor's node-local settings.

Seal them as this instance's `settlement` application:

```bash
./showcase.sh settlement adopt --instance preprod-demo
```

`adopt` stops the nodes, runs the setup commands (`new`, one `request` per
founding node, `seal`, `join`) for the `settlement` application, writes the
executor settings into every node's `config/local.yaml` (node 0 owns the
executor), and starts the nodes again. Inspect the sealed result with:

```bash
../../yano.sh appchain inspect \
  data/showcase/preprod-demo/settlement/settlement.chain
```

The executor settings reference your key **by path** (`operator-seed-file`) —
the seed itself is never written into the sealed application or the node
settings. Keep the key file readable only by the account running the nodes.

## 6. Use it

From here the commands are the same as devnet, each carrying the key file:

```bash
./showcase.sh settlement deposit  --instance preprod-demo \
  --settlement-key-file "$(pwd)/private-settlement/operator.seed" \
  --confirm-public-settlement preprod
./showcase.sh settlement withdraw --instance preprod-demo \
  --settlement-key-file "$(pwd)/private-settlement/operator.seed" \
  --confirm-public-settlement preprod
./showcase.sh settlement status   --instance preprod-demo
```

## 7. What is different from devnet

| | devnet demo | public network |
|---|---|---|
| Operator key | packaged, public | yours, `--settlement-key-file` |
| Profile | `…-devnet`, 60-slot floor | `…-settlement`, 21,600-slot floor |
| Identity | deterministic, template is static | derived from your seeds, template generated |
| Chain config | `config/settlement.template.yaml`, sealed at `prepare` | `settlement adopt` seals the generated template |
| A3 exit demo | ~60 slots | hours — the floor is the point |

Read §9 of `adr/app-layer/utxo/009-claim-settlement-process-and-vault-conditions.md`
before putting non-demo funds behind this, and note the open follow-up in
§13.1: the fallback floor is currently enforced off-chain only.
