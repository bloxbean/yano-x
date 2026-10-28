---
title: "Protect a topic and give users session keys"
description: "Topic policies are switched on per chain when the application is sealed: add a topics: block to the chain in the draft (./yano.sh appchain new --topics…"
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/appchain/TOPIC_SESSION_KEYS.md"
---
Topic policies are switched on per chain when the application is sealed: add a
`topics:` block to the chain in the draft (`./yano.sh appchain new --topics
arcade=members` for a chain `arcade`, or answer the wizard's question), with
governed membership.
`create` chooses who creates top-level namespaces: the members by a vote
(`members`), named operators or keys (`registrars`), or anyone first come
(`anyone`). See [configure an application](/deployment/configure/) and
[the policy model](/concepts/topic-policies/). This guide creates a top-level namespace
by member vote and its children with owner signatures.

Run the commands from the directory that holds `yano-node/` (or pass
`--node-dir`).
They use your node, its API key and, when the application has one chain, that
chain. Elsewhere, add `--peer <url> --chain <id>`, and `--api-key-env NAME` to
read a node API credential from an environment variable.

## Create a namespace by member vote

```bash
./yano.sh appchain topics public-key owner.seed      # prints the owner's public key; never the seed
./yano.sh appchain topics init games/ --owner-key-file owner.seed
```

`init` reads the chain and picks who must approve: a top-level name follows the
chain's creation policy (a member vote, `~registrars` or `~claim`), and a name
under an existing namespace is signed by that namespace's owners, and a name that
already exists gets an `update` by its own owners. A signed change gets a
`notAfter` that fits the chain's command window. Outside a node directory, and
without `--peer` and `--chain`, `init` cannot read the chain and says the
authority is a guess. Check the owners and policy in `topics.yaml`, keep its
generated nonce, then:

```bash
./yano.sh appchain topics plan topics.yaml
```

`plan` checks the change against the chain, writes `topics.plan.json`, and
says `create games/: a member vote`. Send the plan to every operator. Each one
runs, in their own node directory:

```bash
./yano.sh appchain topics approve topics.plan.json
```

`approve` first shows what the plan asks the members to vote for and asks for a
yes (`--yes` in scripts, after checking it). Each vote goes through that
operator's own node; no tool votes on a member's behalf. It then waits until
enough operators have voted and the activation lag has passed, and prints
`create games/ … is in effect`. A chain makes blocks only from messages: on an
idle chain pass `--heartbeat`, or send traffic. `--no-wait` returns after
voting, and `--plan-sha256 <digest>` refuses a plan file whose bytes changed
since you checked it.

`approve` never votes twice, so run it again at any time to see where a vote
stands. The node records every vote it casts under `yano-node/topic-votes/`, so
a vote still waiting for a block, or one the chain refused, is not sent again.
A vote that never counted (it expired before enough operators voted) is
reported as lapsed; vote again with `--again`.

On a chain sealed with `create: registrars`, a top-level name is created with
`authority: ~registrars` and signed instead of voted; the members can still
create or recover one by vote. A registrar named by
operator in the draft signs with that operator's member key
(`yano-node/secrets/member.key`), which is also the node's consensus key; to keep
them apart, name a separate public key under `registrars:` instead. With
`create: anyone`, the new owners sign an `authority: ~claim` creation.

## Change a namespace with owner signatures

Write the change inside `games/` as a `Topics` document:

```yaml
schemaVersion: 1
kind: Topics
chainId: arcade
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
an illustrative ride payload, not the full Neon Rush game protocol. Studio's
**Topics** page writes the same document from a form.

```bash
./yano.sh appchain topics plan neon-rush.yaml
./yano.sh appchain topics sign neon-rush.plan.json --key-file owner.seed
./yano.sh appchain topics submit neon-rush.signed.json
```

`topics init games/neon-rush/ --output neon-rush.yaml` writes this with the
right authority. `plan`
names the keys that must sign, and `submit` waits until the change is accepted
and in effect (`--no-wait` returns at once; `--heartbeat` makes blocks on an idle
chain). Seed files contain a 32-byte Ed25519
seed as 64 lowercase hex characters. Keep them outside served directories and
restrict file permissions; pass only the file path. For k-of-n owners, send
`neon-rush.signed.json` to the next owner, who runs `sign` on it; every signer
verifies the same immutable statement, and `sign` says when enough have
signed. A changed generation, head or authority, or an expired command, needs
a fresh plan and new signatures. Queued owner changes are inspected after
activation rather than predicting an uncertain policy. `--json` prints the
machine-readable result of any of these commands instead.

To revoke one credential, write a `revoke` command with its public
`delegationHex` and use its exact scope as the target. The authority is an
owner namespace or `~issuer`; an issuer must still be an effective delegator
for that scope, and an active owner record can revoke after retirement. `plan`
checks the credential's signature, chain and generation. Revocation does not
change the entry's policy revision, so its plan has no policy-activation
preview.

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
same wire/signing operations for services; see the [Java client](https://github.com/bloxbean/yano-x/blob/main/sdk/client/README.md)
and [JavaScript client](https://github.com/bloxbean/yano-x/blob/main/sdk/javascript/README.md) source documentation.

For a manual test, `topics delegate --help` describes the file-based issuer
command. The public `--session-key` is safe to pass as an argument. Application
issuer keys belong in the delegation service, outside the static site and the
node process.

## Diagnose and verify

```bash
./yano.sh appchain topics resolve games/neon-rush/rides --height 42   # in words; --json for the proof
./yano.sh appchain topics doctor --bindings composite.yaml
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
