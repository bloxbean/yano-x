# Portable finalized application commitment (v1)

This is the portable envelope specified by [Yano ADR-063](https://github.com/bloxbean/yano/blob/feat/245-app-ledger-host-api/adr/063-standalone-application-ledger-and-host-api.md)
and [Yano #245](https://github.com/bloxbean/yano/issues/245). It lives in the existing
`yano-x-proof-contracts` artifact; no new module or runtime plugin is necessary.
`FinalizedAppCommitmentV1` and `CommitmentDestination` have no Yano implementation
or L1 SDK imports. The envelope wraps the existing block hash; it never replaces
AppBlock, its certificate, its serialization or its persisted representation.

## Canonical bytes

Integers are big-endian. Strings are exact UTF-8, with no normalization, a positive
32-bit byte length prefix and at most 1024 bytes. Blank strings, unpaired Unicode
surrogates and malformed UTF-8 are rejected. Every digest field is exactly 32 bytes.

| Order | Encoding |
| --- | --- |
| Domain | ASCII `yano-app-finalized-commitment-v1` followed by one zero byte |
| Version | 32-bit integer `1` |
| Chain ID | Length-prefixed UTF-8 |
| Commitment profile | Length-prefixed UTF-8 |
| State-format fingerprint | 32 bytes |
| Genesis ID | 32 bytes |
| Consensus-context digest | 32 bytes |
| Finalized height | Positive signed 64-bit integer |
| Existing AppBlock hash | 32 bytes |
| State root | 32 bytes |

The commitment digest is SHA-256 of these complete bytes, including the domain
and version. Unknown domains/versions, truncated fields and trailing bytes fail
closed. All byte-array inputs and accessors are defensively copied.

The published `META-INF/yano/contracts/commitment/v1/finalized-commitment.hex`
vector uses chain `orders`, profile `mpf-blake2b256-v1`, height 42, and fingerprints,
genesis, context, block hash and state root filled with bytes 01, 02, 03, 04 and 05
respectively. It is 236 bytes and its digest is
`6871ab20b6404d103d2a39200fa3ffe06a90789df20a64e70e074806469d43ee`.

## Independent destination records

`CommitmentDestination` identifies a destination by its network ID, adapter ID,
and a 32-byte digest of its canonical public configuration. Configuration identity
must distinguish independently configured destinations; never put secrets in it.
The v1 receipt/idempotency key is SHA-256 over:

1. ASCII `yano-app-commitment-receipt-v1` and one zero byte;
2. length-prefixed network ID and adapter ID, with the string rules above;
3. configuration digest;
4. finalized commitment digest.

Retries at the same destination produce the same key. A different network,
adapter, configuration, application chain/genesis or commitment produces a
different key. This prevents future independent publishers from overwriting one
another's status. It adds no publisher, receipt database, fanout, L1 integration
or alternative observer SPI.

The golden destination (`cardano:preprod`, `anchor:primary`, configuration digest
filled with byte 06) gives receipt key
`cb77b8347daf6f62be99f080dc68a277ab9e0cb5984ce6472086d28ae94d1ce3`.

## Trust and delivery

Only construct the envelope from already verified finalized history. The envelope
alone proves neither consensus finality nor settlement: verify its associated
certificate/evidence under the chain's member and state identities. External
publication remains asynchronous and idempotent. Publication failure must never
change application finality, and its status belongs outside canonical app state.
An anchored root is anchoring/timestamp evidence, not custody or trustless withdrawal.

Run `./gradlew :sdk:proof-contracts:test verifyArtifactInventory verifyJvmOnlyBuild`.
Golden vectors, strict decoding, defensive copying and destination isolation are
covered. Framework extraction, host integration and the oldest-host release matrix
remain separate acceptance gates; this contract alone does not complete Phase 2.

The extracted `AppChainGateway.finalizedCommitment(height)` exports this envelope
from an already finalized local block and the pinned state commitment identity.
It returns empty when either is unavailable, preserving compatibility with older
implementations that do not expose the identity. It refuses inconsistent
chain/height responses. The method is read-only: adapters decide independently
whether and where to publish. Verifiers still need the finality evidence and
an independently trusted chain context; the envelope is not a certificate.
