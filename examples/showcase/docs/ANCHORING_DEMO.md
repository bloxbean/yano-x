# L1 Anchoring Demo

Anchoring needs no configuration edit. Every chain is sealed with an anchor
policy: the mode (`--anchor-mode script|metadata` at `prepare`, default
`script`) and a cadence (every 2 blocks on devnet, every 30 on a public
network). The mode is part of the sealed application and cannot change later.
The policy alone spends nothing; a chain is anchored only after
`./showcase.sh anchor [chain-id|all]` turns it on.

`./showcase.sh anchor` runs `yano.sh appchain anchor` on node 0 for each
chain. Node 0 becomes the chain's anchor leader: it gets an anchor wallet in
`node0/secrets/anchor.key` (or imports `--anchor-key-file`), the wallet is
funded (from the devnet faucet on devnet; by you on preprod), node 0 restarts
with the anchor settings, and in script mode it submits the chain's one-time
bootstrap. Running it again is safe: a chain is bootstrapped at most once.

## Fast devnet script-anchor demo

This is the recommended presentation path:

```bash
./showcase.sh quickstart --profile light --instance anchor-demo --nodes 3
```

For the light profile, `quickstart` turns on `script` anchoring for
`workflow-chain`. It starts a 2-of-3 app chain, funds node 0's anchor wallet
from the local devnet faucet, performs the one-time `workflow-chain`
bootstrap, finalizes the composite/effect flow, and verifies a confirmed
Cardano anchor. The devnet wallet is demo material and must never be reused
on another network.

Open the status UI and select `workflow-chain`:

```bash
./showcase.sh ui --instance anchor-demo
./showcase.sh status --instance anchor-demo
curl -fsS \
  http://127.0.0.1:7070/api/v1/app-chain/chains/workflow-chain/status \
  | jq .anchor
```

Show the anchor wallet address, bootstrap/script output reference, last anchor
transaction ID, anchored L2 height/root, lag, and member L1 observations.

## Manual devnet flow

Use this version when you want to narrate the anchor step separately:

```bash
./showcase.sh up --profile light --instance anchor-manual --nodes 3 \
  --anchor-mode script

./showcase.sh config show --instance anchor-manual
./showcase.sh anchor workflow-chain --instance anchor-manual
./showcase.sh run composite --instance anchor-manual
./showcase.sh verify all --instance anchor-manual
```

The bootstrap mints the thread NFT and establishes the script/datum identity,
once per chain. Ordinary anchors occur automatically after finalized
`workflow-chain` activity; restart reuses the identity.

## Add anchoring to an existing chain

An app chain may already contain finalized history when anchoring is turned
on. The command restarts node 0 on its existing L1 and app-chain stores:

```bash
# Add one chain while retaining an existing workflow-chain anchor.
./showcase.sh anchor registry-chain --instance anchor-demo

# Or every chain of the instance, one after another.
./showcase.sh anchor all --instance anchor-demo
```

For preprod, repeat the public-network acknowledgement. The key recorded at
`prepare` is reused; do not supply a different one:

```bash
./showcase.sh anchor registry-chain --instance preprod-anchor \
  --confirm-public-anchor preprod

./showcase.sh anchor all --instance preprod-anchor \
  --confirm-public-anchor preprod
```

In script mode each chain's bootstrap needs enough clean pure-ADA UTxOs in the
anchor wallet; `anchor all` waits for each chain's L1 confirmation before
starting the next.

Each chain receives its own thread NFT, policy ID, validator address, and
bootstrap transaction, even though the wallet key may be shared. Bootstrap
first establishes identity at height zero; the first threshold-co-signed
advance commits the chain's then-current tip. Earlier history remains verified
by the app-chain ledger but is not retroactively emitted as one L1 transaction
per old height. Metadata mode needs no bootstrap.

A one-node demonstration is also supported:

```bash
./showcase.sh quickstart --profile light --instance anchor-one \
  --nodes 1 --threshold 1
```

That proves the mechanics, but a three-node 2-of-3 run better demonstrates
threshold co-signing and follower observation.

## Metadata mode

Metadata mode is lighter and has no thread-NFT bootstrap. Once the anchor
wallet is funded, finalized roots are submitted automatically. Run the setup
tool's `anchor` command on node 0 directly; on devnet it funds the wallet from
the faucet:

```bash
./showcase.sh up --profile light --instance metadata-demo --nodes 3 \
  --anchor-mode metadata

../../yano.sh appchain anchor showcase --chain workflow-chain \
  --node-dir data/showcase/metadata-demo/node0 --yes
./showcase.sh run composite --instance metadata-demo
./showcase.sh status --instance metadata-demo
```

Use script mode for the stronger demo: current app-chain members co-sign the
L1 state transition at the app-chain threshold. Metadata mode is useful when
you only need a simple L1 checkpoint transaction.

## Preprod script-anchor flow

Preprod spends test ADA and uses the live network's UTxO view. The wallet is
derived from an owner-only file containing one raw 32-byte Ed25519 seed—not a
mnemonic and not a CIP-1852 phrase.

1. Create or securely supply the key file outside logs and shell arguments:

   ```bash
   mkdir -m 700 ./private-anchor
   (umask 077; openssl rand -hex 32 > ./private-anchor/anchor.seed)
   chmod 600 ./private-anchor/anchor.seed
   ```

2. Start a fresh preprod instance that records the key:

   ```bash
   ./showcase.sh up --profile light --network preprod --nodes 3 \
     --instance preprod-anchor --anchor-mode script \
     --anchor-key-file "$(pwd)/private-anchor/anchor.seed" \
     --confirm-public-anchor preprod
   ```

3. Wait until node 0 is synchronized far enough to see new preprod
   transactions. Inspect it without exposing the seed:

   ```bash
   ./showcase.sh config show --instance preprod-anchor
   ./showcase.sh status --instance preprod-anchor
   ./showcase.sh logs --node 0 --instance preprod-anchor
   ```

4. Turn anchoring on for `workflow-chain`:

   ```bash
   ./showcase.sh anchor workflow-chain --instance preprod-anchor \
     --confirm-public-anchor preprod
   ```

   It imports the key into node 0, restarts node 0, and prints the
   `addr_test...` anchor wallet address. Fund it from a separate preprod
   wallet. Prefer clean pure-ADA UTxOs with enough value for bootstrap,
   script execution/collateral, fees, and later anchors; more than one usable
   UTxO avoids a transaction builder being blocked while another input is
   spent. The command waits for the funds and then submits the bootstrap. If
   it stops waiting first, run the same command again; it continues and never
   submits a second bootstrap.

   Node 0 builds the Cardano transaction with the anchor wallet. The current
   app-chain members co-sign the script-anchor round at the current threshold.
   For 3 nodes the default is 2 signatures; for 1 node it is 1.

5. Finalize `workflow-chain` activity. Public-network cadence is one anchor
   per 30 workflow blocks to avoid needless fees, so a single composite run
   proves the flow but may not yet advance the next ordinary anchor. For a
   prompt rehearsal, run enough complete composite flows:

   ```bash
   ./showcase.sh load composite --count 6 --instance preprod-anchor
   ./showcase.sh verify all --instance preprod-anchor
   ```

6. Verify both L2 finality and L1 confirmation:

   ```bash
   curl -fsS \
     http://127.0.0.1:7070/api/v1/app-chain/chains/workflow-chain/status \
     | jq '{tipHeight,stateRoot,threshold,members,anchor}'
   ./showcase.sh ui --instance preprod-anchor
   ```

7. Restart normally; the anchor identity is reused:

   ```bash
   ./showcase.sh restart --instance preprod-anchor
   ./showcase.sh verify all --instance preprod-anchor
   ```

If bootstrap or an anchor fails with `TxBuildException`, first check node sync,
stable visibility of the funding UTxOs, pure-ADA input availability, collateral
and fee headroom, and current member readiness. Creating another usable UTxO
can resolve input contention, but do not create a new app-chain identity. See
[PREPROD_ANCHORING.md](PREPROD_ANCHORING.md) and
[TROUBLESHOOTING.md](TROUBLESHOOTING.md) for the shorter operator checklist.
