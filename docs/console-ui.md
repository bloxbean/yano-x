# App-chain console

The app-chain console is owned by Yano X (ADR-064). It is a separate single-page
application served at `/ui/app-chain/` from the `framework/rest` resources of
the integrated distribution, and it links back to Yano's generic console at
`/ui/`. Yano's console has no app-chain pages and no link to this one; open
`/ui/app-chain/` directly.

The console discovers capabilities, plugins and domain APIs through the
app-chain REST API and Yano's generic plugin contracts; there is no special
integration API. Yano X extensions must remain usable without the console.

## Capability panels

The app-chain console discovers optional capabilities from the selected chain's
status and, when authorized, confirms first-party bundles against the plugin
catalog. It does not assume that every deployment has effects or role-aware
approvals.

- An effects-enabled chain shows emitted effects, statistics, composed proofs,
  and confirmed requeue/cancel actions. Mutations require the appropriate
  operator API key.
- A `role-approvals` or `role-evidence` chain queries the proposal through the
  root-fixed committed-query surface and adds the decoded domain projection
  when available. The committed result remains visible if that convenience
  projection is unavailable.
- Every running app chain exposes **Operations**, **Capabilities**, and
  **Proofs**. Proofs is divided into **Message**, **State**, **Import and
  verify**, and **Advanced** workflows. The browser can SHA-256 the exact
  finalized message payload or
  included proof value and compare each with its own optional expected digest.
  A proof can be loaded at the current tip, loaded at the exact height of the
  latest anchor confirmed by this node, or pasted as JSON. **Verify proof**
  sends only the bounded key/value/proof/root fields to the connected node's
  release-matched MPF verifier and reports three facts independently:

  - whether the MPF path is mathematically valid for the expected root;
  - whether the proof envelope's root and optional height match that source;
  - where the expected root came from.

The State workflow discovers typed proof subjects from the chain's immutable
capability manifest and keeps physical state keys in Advanced. For
extension-specific trust labels and proof workflows, see the
[Proof Lab guide](appchain/PROOF_LAB.md).

`L1-confirmed by this node` means the node observed the anchor transaction and
bound its persisted confirmation back to the exact finalized app block. It is
not an independent Cardano lookup. For an audit decision, resolve the shown
transaction through an independent Cardano source, validate the metadata or
script datum/output, and pin the expected chain, membership, threshold, and
script identity. The browser digest remains a byte-integrity check rather than
finality, anchor, or MPF verification. Custom component-specific panels remain
data-only future catalog work; plugins cannot inject executable console code.

### EUTxO lifecycle

An `eutxo-ledger` chain exposes a reviewed EUTxO route at
`/ui/app-chain/eutxo/?chain=<chain-id>`. With the node-local
`indexer:eutxo-lifecycle` capability, it provides bounded transaction,
account, bridge, lineage, and optional validity views. A user can start from
an address, L1 deposit outpoint, app-message ID, L2 transaction ID, or
withdrawal claim and follow the canonical L1 deposit to L2 activity to L1
payout.

The indexer is a derived SQLite projection. The console displays its coverage,
checkpoint, finalized height, lag, and bridge-reconciliation diagnostics.
If it is disabled or unavailable, the page falls back to the committed
transaction-summary API; consensus and bridge execution are unaffected.
L1 transaction details are loaded lazily from the connected Yano node and
remain optional when retained L1 history is unavailable. The browser does not
decode arbitrary Cardano or proof CBOR and never reads the disposable demo
journal.

The Bridge tab's server-built CIP-30 deposit may select a wallet input that
contains ADA plus native assets. The vault output remains ADA-only and every
native asset is returned to the depositor as change; signing and submission
remain wallet-owned.

Operational configuration, endpoints, metrics, rebuild safety, and the
measured SQLite support envelope are documented in the
[EUTxO indexer runbook](../ledgers/eutxo/INDEXER_OPERATIONS.md).
