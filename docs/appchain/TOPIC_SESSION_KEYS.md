# Protect a topic and give users session keys

Use a fresh devnet with the pinned Yano X distribution. The host must have the
topic registry enabled and governed membership configured. Its default creation
path is member governance; registrars and open claims require explicit genesis
settings. This guide uses governance for the top-level namespace and owner
commands for its children. See [the policy model](TOPIC_POLICIES.md).

## Prepare and sign a policy

```bash
./yano.sh appchain topics init games/ --output topics.yaml
```

Edit the chain ID and public owner keys in this governance creation document.
Keep the generated nonce fixed for this plan:

```bash
./yano.sh appchain topics plan --file topics.yaml --peer http://localhost:7170 \
  --output namespace-plan.json
./yano.sh appchain topics submit --plan namespace-plan.json --peer http://localhost:7170
```

The second command prints the command bytes and member governance endpoint.
Members submit that command through their enabled governance path. Wait for
`games/` to activate before planning its children. This is a governance action;
the printed command does not create the namespace by itself.

Then replace `topics.yaml` with a protected-child `Topics` document:

```yaml
schemaVersion: 1
kind: Topics
chainId: games
commands:
  - target: games/neon-rush/
    operation: create
    authority: games/
    nonce: '11111111111111111111111111111111'
    notAfter: 1793106000000
    owners:
      threshold: 1
      keys: ['<owner-public-key-64-hex>']
    policy:
      writers:
        threshold: 1
        keys: ['<issuer-public-key-64-hex>']
      delegate: ['<issuer-public-key-64-hex>']
      maxDelegationMs: 3600000
      maxPayloadBytes: 4096
      schemaCddl: 'root = { frames: 1200..9000, name: tstr .size (1..32) }'
```

Replace the public keys, choose a fresh 16-byte nonce and a future Unix
millisecond `notAfter` within the chain's command window. The schema above is
an illustrative ride payload, not the full Neon Rush game protocol.

```bash
./yano.sh appchain topics plan --file topics.yaml --peer http://localhost:7170 \
  --output plan.json
./yano.sh appchain topics sign --plan plan.json --key-file owner.seed \
  --output signed-plan.json
./yano.sh appchain topics submit --plan signed-plan.json --peer http://localhost:7170
```

Seed files contain a 32-byte Ed25519 seed as 64 lowercase hex characters.
Keep them outside served directories and restrict file permissions. Pass only
the file path to the CLI. For k-of-n owners, pass the signed plan to the next
owner and repeat `sign`; every signer verifies the same immutable statement.
`--api-key-env NAME` reads an optional node API credential from that environment
variable without putting its value in command arguments.

Governance plans export the command for members to submit through their
privileged `admin/topics/governance` route; the tool does not impersonate a
member. Wait for the threshold and activation height before writing. A changed
generation, head or authority, or an expired command, requires a fresh plan and
new signatures. Queued owner changes are inspected after activation rather
than predicting an uncertain policy.

Studio's **Topics** page imports the same unsigned plan, edits `topics.yaml`,
and exports matching command bytes. It never asks for an owner or issuer
private key. The resolver and entry inspector keep effective-policy proofs and
entry-only proofs distinct.

To revoke one credential, select **Revoke delegation**, paste its public
`delegationHex`, and use its exact scope as the target. The authority is an
owner namespace or `~issuer`; an issuer must still be an effective delegator
for that scope. An active owner record can revoke after retirement. Studio
checks the credential's signature, chain and generation before exporting the
unsigned plan for external signing. Revocation does not change the entry's
policy revision, so its plan has no policy-activation preview.

## Use a browser session

Import the dependency-free modules from `sdk/javascript/` into your example.
The application service validates the user's signed login proof before issuing
a credential for the newly created session public key:

```js
import {DelegatedSession, encodeCanonicalValue, submitFrame, unhex} from './topics.mjs';

const session = new DelegatedSession({
  chainId, generationId, scope: 'games/neon-rush/', issuers: [issuerPublicKey],
  requestDelegation: async request => {
    const proof = await signLoginRequest(request);
    const response = await requestCredential({...request, proof});
    return unhex(response.delegationHex);
  }
});
const topic = 'games/neon-rush/rides';
const frame = await session.frame(topic, encodeCanonicalValue({frames: 1800, name: 'rider'}));
await submitFrame({baseUrl: 'http://localhost:7170/api/v1', chainId, topic, frame});
```

The session key is a non-extractable WebCrypto key. A caller-supplied IndexedDB
store can retain it by structured cloning; without one, it lasts for this page.
The SDK verifies returned credentials and refreshes before expiry. Clear the
session on logout. The Java `TopicAuthoring` and `TopicClient` APIs provide the
same wire/signing operations for services; see the [Java client](../../sdk/client/README.md)
and [JavaScript client](../../sdk/javascript/README.md) source documentation.

For a manual test, `topics delegate --help` describes the file-based issuer
command. The public `--session-key` is safe to pass as an argument. Application
issuer keys belong in the delegation service, outside the static site and the
node process.

## Diagnose and verify

```bash
./yano.sh appchain topics resolve games/neon-rush/rides \
  --chain games --peer http://localhost:7170 --height 42
./yano.sh appchain topics doctor --bindings composite.yaml \
  --chain games --peer http://localhost:7170
./yano.sh appchain topics verify <messageId> \
  --bundle authorship.json --trusted-block trusted-block.json
```

The doctor checks active and queued topic policies, undeclared payload schemas,
coverage under unregistered-topic rejection and bindings that cross protected
topics. These diagnostics are advisory; runtime checks use the committed
candidate-height registry state on every member.

The [game-center reference](https://github.com/bloxbean/yano-x-examples/tree/main/21-anchor-game-center)
lives in `yano-x-examples`. Its delegation service checks the existing commander
profile and a fresh request signature before issuing a scoped one-hour session.
Browser replay still verifies claimed ride times. Topic authentication cannot
prove that a rider is human or that an input sequence was played in real time.
