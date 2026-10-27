# Preprod L1 Anchoring

For the narrated devnet, metadata, one-node, and full preprod flows, start with
[ANCHORING_DEMO.md](ANCHORING_DEMO.md). This page is the compact preprod
operator checklist.

Preprod anchoring spends real test ADA and is explicit. The showcase never
accepts a mnemonic. It requires an owner-only file containing one lowercase
64-hex raw Ed25519 seed:

```bash
mkdir -m 700 ./private-anchor
openssl rand -hex 32 > ./private-anchor/anchor.seed
chmod 600 ./private-anchor/anchor.seed
```

Start:

```bash
./showcase.sh up --network preprod --nodes 3 --instance preprod-demo \
  --anchor-mode script \
  --anchor-key-file "$(pwd)/private-anchor/anchor.seed" \
  --confirm-public-anchor preprod
```

Every chain is sealed with the script anchor policy; nothing is anchored and
nothing is spent until you turn anchoring on for a chain. Node 0 becomes the
chain's anchor leader and transaction builder; members co-sign script-anchor
rounds at the current app-chain threshold. A mnemonic or CIP-1852 account is
not the raw seed this option expects.

Wait until the preprod node has synchronized far enough to observe new
transactions, then turn anchoring on for `workflow-chain`:

```bash
./showcase.sh anchor workflow-chain --instance preprod-demo \
  --confirm-public-anchor preprod
```

It imports the recorded key into node 0, restarts node 0, and prints the
anchor wallet address. Fund that address externally. Prefer multiple clean
pure-ADA UTxOs with sufficient fee and collateral headroom. The command waits
until the configured L1 stability view accepts the funding, then submits the
bootstrap once. If it stops waiting first, run the same command again.

To add registry anchoring later without resetting its history, or to cover
every chain of the instance:

```bash
./showcase.sh anchor registry-chain --instance preprod-demo \
  --confirm-public-anchor preprod
./showcase.sh anchor all --instance preprod-demo \
  --confirm-public-anchor preprod
```

Each script bootstrap is fee-paying. Supply enough independent pure-ADA UTxOs
for the desired chains; `anchor all` waits for each chain's L1 confirmation
before proceeding to avoid reusing an input prematurely.

Bootstrap mints the thread NFT and establishes the script/datum identity. It
is not a per-restart action. After it succeeds, submit finalized L2 activity:

```bash
./showcase.sh run composite --instance preprod-demo
./showcase.sh load composite --count 6 --instance preprod-demo
./showcase.sh status --instance preprod-demo
```

The public-network default is one ordinary anchor per 30 finalized
`workflow-chain` blocks, so one composite run may not reach the next cadence.

Verify the L2 height/root and finality certificate, Cardano transaction ID and
output reference, anchored high-water mark, and follower L1 observations in
the status UI. If bootstrap reports no usable UTxO, do not reset or create a
new identity: confirm the wallet address, funding transaction, node sync, and
stability depth, then run the same `anchor` command again.

For a faster local validation, a synchronized preprod chainstate may be copied
into a new disposable instance before first start. Never mutate or test in the
source demo directory itself, and never combine retained app-chain state with
a different L1 network or genesis.
