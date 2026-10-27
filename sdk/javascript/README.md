# Browser topic client

Dependency-free ES modules for Yano topic-policy v1. Import `topics.mjs` from a
secure browser context that supports WebCrypto Ed25519. The same modules work
in Node with WebCrypto. No npm publication or runtime plugin is required.

```js
import { DelegatedSession, submitFrame, topicRequest, unhex } from './topics.mjs';

const session = new DelegatedSession({
  chainId, generationId, scope: 'games/neon-rush/', issuers: [issuerPublicKey],
  requestDelegation: async request => {
    // Include the application's signed login proof. Never send a private key.
    const result = await topicRequest(delegationEndpoint, { ...request, loginProof });
    return unhex(result.delegationHex);
  },
});
const topic = 'games/neon-rush/rides';
const frame = await session.frame(topic, canonicalRidePayload);
const accepted = await submitFrame({ baseUrl: 'https://node.example/api/v1', chainId, topic, frame });
```

`canonicalRidePayload` is an application's encoded `Uint8Array`.
`encodeCanonicalValue({ frames: 1500, ... })` encodes bounded application maps
and arrays using core's canonical ordering, or an application may use its own
codec. Native CBOR integers include `-2^64..2^64-1`; use `BigInt` beyond the
JavaScript safe range. Floats and tags are not supported schema types.
The wire CBOR
codec encodes the topic protocol's unsigned integers, byte strings, text,
booleans, nulls and arrays; it does not interpret application payload bytes.
`encodeWire` / `decodeWire` implement the frozen topic structures. Integers above
JavaScript's safe integer range are decoded as `BigInt`, never rounded. Session
timestamps must fit JavaScript's safe integer range.

`signWrite` supports direct threshold signing through caller-owned
`{publicKey, sign(bytes)}` objects. It sorts keys, checks every signature, and
rejects duplicate keys. `issueDelegation` is for application servers or an
external issuer signer. `signRegistry` signs canonical command bytes for the
CLI/Studio authoring flow. Long-lived issuer and owner keys belong outside a
static site and outside the Yano node.

`DelegatedSession` creates a non-extractable private `CryptoKey`, sends only the
public key to `requestDelegation`, verifies the returned credential's issuer,
scope, chain, generation and lifetime, and refreshes it before expiry. Its
optional store has asynchronous `get(key)`, `set(key, value)`, `delete(key)`
methods and must support structured cloning of `CryptoKey` (for example
IndexedDB). JSON/localStorage cannot store the private key. Without a store,
the session lasts only for the page lifetime. Call `clear()` on logout; it
waits for an in-flight refresh before deleting the credential. Storage keys
include the chain generation, so a reset cannot reuse an old session.

`topicRequest` bounds responses to 64 KiB, uses a 15-second deadline, refuses
redirects and omits ambient browser credentials. `TopicError.status` and
`TopicError.code` expose bounded refusal details without reflecting arbitrary
server text. An explicit `signal` and `timeoutMs` (up to 60 seconds) may be
provided. `submitFrame` also binds the response to the requested chain/topic.

`verifyFrameSignatures` and `verifyDelegation` verify signatures, not current
authority, revocation, replay status or finality. Complete offline authorship
verification requires independently trusted roots and the effective-policy
proof; use `appchain topics verify` for that bundle.

Run the client gates with:

```sh
node --test sdk/javascript/test/*.test.mjs
```

The test corpus contains all 33 valid and 23 malformed frozen core wire
vectors, deterministic signing/hash checks, the core adversarial Ed25519
vectors, bounded transport and session refresh tests. See
[vector provenance](test/README.md). BLAKE2b-256 follows RFC 7693 (WebCrypto
does not provide this hash); Ed25519 operations use WebCrypto.
