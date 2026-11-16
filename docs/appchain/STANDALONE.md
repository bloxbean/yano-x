# Standalone application ledgers

In the integrated Yano X distribution, `yano.mode=app-ledger` selects the
application-ledger runtime. The default remains `cardano`; existing integrated
configurations and databases keep their behavior. The mode itself is a generic
Yano host without Cardano chainstate; the app-only network comes from the X
root plugin, which the integrated distribution activates through
`yano.plugins.required`. A bare Yano node fails startup in this mode with
"app-ledger mode requires a plugin that provides an app-only network".
Standalone runs the existing application consensus and storage engine, without
opening Cardano chainstate or starting Cardano sync, ledger, UTxO or production
services. It uses Yaci protocols 100 and 103 and keepalive over dedicated peers.

## Configuration

Keep the existing `yano.app-chain.*` keys, including indexed `chains[i]` entries.
For example, these host settings accompany the ordinary chain configuration:

```properties
yano.mode=app-ledger
yano.auto-sync-start=true
yano.server.enabled=true
yano.server.port=13337
yano.block-producer.enabled=false
yano.bootstrap.enabled=false
yano.dev-mode=false
yano.app-chain.enabled=true
yano.app-chain.transport.mode=dedicated
yano.app-chain.network-magic=424242
yano.app-chain.storage.path=./standalone-app-state
yano.app-chain.chain-id=my-application
yano.app-chain.signing-key=${APP_SIGNING_KEY}
yano.app-chain.members=${APP_MEMBER_PUBLIC_KEYS}
yano.app-chain.sequencer.proposer=${APP_PROPOSER_PUBLIC_KEY}
yano.app-chain.threshold=2
yano.app-chain.peers=${APP_PEERS}
yano.app-chain.state.commitment-profile=mpf-blake2b256-v1
yano.app-chain.state.format-fingerprint=91ee14091200f1e24659112d640e877e9177779dcc81dd06117f013e9190082b
yano.app-chain.state.genesis-id=${APP_GENESIS_ID}
yano.app-chain.l1.stability-depth=0
yano.app-chain.anchor.enabled=false
```

Supply each node's own signing key and peers, and the same chain ID, member set,
threshold, proposer, state identity and network magic to every member. Choose a
new explicit 32-byte genesis ID and fresh application storage. Existing API-key
configuration (`yano.app-chain.api.keys`) and message/proof endpoints still apply.
Use a distinct unsigned 32-bit application network magic; known Cardano network
identities are rejected. A peer offering Cardano/hybrid handshake versions cannot
join an app-only session even if its magic matches.

The host binds `consensus.operating-profile=standalone-v1` into consensus identity
and persists a profile marker. Standalone proposals and certified catch-up blocks
must carry zero L1 slot and an empty L1 hash; reserved Cardano observations are
rejected. Existing legacy databases, including empty databases, cannot be adopted
as standalone chains. A retained standalone database cannot be opened in Cardano
mode. Take snapshots and restore within the same profile and genesis identity.

L1 anchors, Cardano observers, L1 retention, L1 stability/proof requirements and
the L1-slot-based rotating sequencer are unavailable. Keep fixed proposership or
a compatible independent sequencer plugin. Generic observations, state machines,
app-final effects, proofs, domain APIs and local read models use the existing
plugin contracts. Cardano-specific HTTP operations return `503` with
`error=cardano_unavailable`; library calls fail explicitly. No placeholder Cardano
chain is synthesized. Cardano network/genesis configuration does not select the
standalone application's identity.

## Qualification

The framework tests cover real TCP consensus, catch-up and restart, and signed
invalid-L1 input rejection (`./gradlew frameworkCheck`, see the
[framework gate](../../qa/framework/README.md)). Release QA runs the app-chain
harnesses against the integrated distribution jar (`qa/release-qa.sh`, category
`appchain`).
