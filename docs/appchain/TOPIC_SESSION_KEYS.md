# Protect a topic and give users session keys

Topic policies are switched on per chain when the application is sealed: add a
`topics:` block to the chain in the draft (`./yano.sh appchain new --topics
arcade=members` for a chain `arcade`, or answer the wizard's question), with
governed membership.
`create` chooses who creates top-level namespaces: the members by a vote
(`members`), named operators or keys (`registrars`), or anyone first come
(`anyone`). See [configure an application](deployment/configure.md) and
[the policy model](TOPIC_POLICIES.md). This guide creates a top-level namespace
by member vote and its children with owner signatures.

Run the commands from the directory that holds `yano-node/` (or pass
`--node-dir`).
They use your node, its API key and, when the application has one chain, that
chain. Elsewhere, add `--peer <url>` (and `--chain <id>` to `init`, `resolve`,
`delegate` and `doctor`; `plan`, `sign` and `submit` read the chain from the
file), and
`--api-key-env NAME` to read a node API credential from an environment
variable.

A new chain has no registry until its first block. If `status` shows height 0,
send one message first (step 7 of [configure an application](deployment/configure.md)).
Owners sign with an Ed25519 seed file: 64 lowercase hex characters, readable
only by you, outside any served directory. To make one:

```bash
(umask 077; openssl rand -hex 32 > owner.seed)
```

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
yes in a terminal; in a script it stops and asks for `--yes`, after you have
checked the change. Each vote goes through that operator's own node; no tool
votes on a member's behalf. Votes from the chain's block threshold of members
are enough (two of three with `threshold: majority`). `approve` then waits
until the activation lag has passed and prints `create games/ … is in effect`;
an operator who runs it after that only waits, and casts no vote. A chain makes blocks only from messages: on an
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

The simplest start is `topics init games/neon-rush/ --output neon-rush.yaml
--owner-key-file owner.seed`, which fills in the authority, the owner key, a
fresh nonce and a `notAfter` one hour ahead. The document looks like this:

```yaml
schemaVersion: 1
kind: Topics
chainId: arcade
commands:
  - target: games/neon-rush/
    operation: create
    authority: games/
    nonce: '<fresh 32 hex characters>'
    notAfter: <Unix milliseconds, in the future and within 7 days>
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

Replace the public keys (`topics public-key <seed file>` prints one). The
command window is the chain's `max-command-ms`, seven days by default. The
schema above is an illustrative ride payload, not the full Neon Rush game
protocol. Studio's **Topics** page writes the same document from a form.

**To change an existing entry**, `init` writes an `update` that starts from the
entry's active policy, so planned unchanged it changes nothing; edit only the
fields you change. `plan` and `sign` list every effective-policy field that
changes, old and new, and flag a change that removes writer keys, delegators
or the schema.

```bash
./yano.sh appchain topics plan neon-rush.yaml
./yano.sh appchain topics sign neon-rush.plan.json --key-file owner.seed
./yano.sh appchain topics submit neon-rush.signed.json
```

`plan` names the keys that must sign, and `submit` waits until the change is accepted
and in effect (`--no-wait` returns at once; `--heartbeat` makes blocks on an idle
chain). Seed files contain a 32-byte Ed25519
seed as 64 lowercase hex characters. Keep them outside served directories and
restrict file permissions; pass only the file path. For k-of-n owners, send
`neon-rush.signed.json` to the next owner, who runs `sign` on it; every signer
verifies the same immutable statement, and `sign` says when enough have
signed. A changed generation, head or authority, or an expired command, needs
a fresh plan and new signatures. `plan` checks against the active policy only:
if an owner change is queued, wait until it is in effect, then plan. `--json`
prints the machine-readable result of any of these commands instead; with
`submit` it waits like the plain command unless you add `--no-wait`, prints
progress on standard error and the JSON when done.

To revoke one credential, write a `revoke` command with its public
`delegationHex` and use its exact scope as the target:

```yaml
commands:
  - target: games/neon-rush/
    operation: revoke
    authority: '~issuer'          # or an owner namespace, such as games/
    nonce: '<fresh 32 hex characters>'
    notAfter: <Unix milliseconds>
    delegationHex: '<the credential, as delegate printed it>'
```

The authority is an owner namespace or `~issuer`; an issuer must still be an
effective delegator for that scope, and an active owner record can revoke after
retirement. `plan` checks the credential's signature, chain and generation.
Then `sign` and `submit` as above. A revocation takes effect in the next block,
without the activation lag, and does not change the entry's policy revision.
A write with a revoked credential is refused with `TOPIC_DELEGATION_INVALID`.

## Use a browser session

Import the dependency-free modules from `sdk/javascript/` into your example.
In the JVM distribution they are in `studio/assets/topics/`; copy that folder.
Read the chain's `generationId` from
`GET /api/v1/app-chain/chains/<chainId>/state/identity` and pin it.
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
await submitFrame({baseUrl: 'http://<node>:<http-port>/api/v1', chainId, topic, frame});
```

The session key is a non-extractable WebCrypto key. A caller-supplied IndexedDB
store can retain it by structured cloning; without one, it lasts for this page.
The SDK verifies returned credentials and refreshes before expiry. Clear the
session on logout. The Java `TopicAuthoring` and `TopicClient` APIs provide the
same wire/signing operations for services; see the [Java client](../../sdk/client/README.md)
and [JavaScript client](../../sdk/javascript/README.md) source documentation.

For a manual test, `topics delegate --help` describes the file-based issuer
command. It prints the credential as JSON (`delegationHex`). The public
`--session-key` is safe to pass as an argument. In a node directory or with
`--peer`, `delegate` first checks the scope's policy: the issuer must be a
writer of a 1-of-n set and listed under `delegate`, and the lifetime within
`maxDelegationMs`. With `--generation` and no node it cannot check, and says
so; members still check every write. Application
issuer keys belong in the delegation service, outside the static site and the
node process.

## Diagnose and verify

```bash
./yano.sh appchain topics resolve games/neon-rush/rides --height 42   # in words; --json for the proof
./yano.sh appchain topics doctor --bindings <your composite bindings file>
./yano.sh appchain topics verify <messageId> \
  --bundle authorship.json --trusted-block trusted-block.json
```

`resolve` shows the policy a write landing in that block gets (by default the
next block), with its proofs checked. The doctor checks your composite
bindings against active and queued topic policies: undeclared payload schemas,
coverage under unregistered-topic rejection and bindings that cross protected
topics. These diagnostics are advisory; runtime checks use the committed
candidate-height registry state on every member.

`verify` checks who wrote a finalized message, offline, against a block you
trust. No command assembles its inputs yet; build them from the REST API of
any member (paths below `/api/v1/app-chain/chains/<chainId>`, message at
height `h`):

| Input | Contents |
|---|---|
| `authorship.json` | `{"schemaVersion": 1, "kind": "TopicAuthorshipBundle", "message", "messageProof", "policyProof", "additionalStateProofs"}` |
| `message` | The message's `version`, `messageId`, `chainId`, `topic`, `sender`, `senderSeq`, `expiresAt`, `bodyHex`, `authScheme` and `authProofHex`, all in hex: `suppliedMessage` of `GET /messages/<id>/proof-package`, whose fields are base64 |
| `messageProof` | `GET /messages/<id>/proof` |
| `policyProof` | `GET /topics/proof?topic=<topic>&height=<h>`, the `proof` that `resolve --json` prints |
| `additionalStateProofs` | `[{keyHex, presence, valueHex, proofWireHex}]` from `GET /state/proof/<keyHex>?height=<h-1>` for `~yano/topics/v1/used/<write statement hash>`, and also `~yano/topics/v1/revoked/<delegationHash>` for a delegated write and `~yano/topics/v1/schema/<hash>` when the policy has a schema |
| `trusted-block.json` | `{chainId, profile, genesisId, height, blockHash, messagesRoot, parentStateRoot, timestamp}`: block `h` from a source you trust, `parentStateRoot` from block `h-1`, `profile` and `genesisId` from `GET /state/identity` |

`verify` refuses a bundle that leaves any of these out. A success reports the
writer kind, the signers or issuer, and `availability: NOT_PROVEN`: it proves
authorship under the trusted block, not that every member still serves the
message.

The [game-center reference](https://github.com/bloxbean/yano-x-examples/tree/main/21-anchor-game-center)
lives in `yano-x-examples`. Its delegation service checks the existing commander
profile and a fresh request signature before issuing a scoped one-hour session.
Browser replay still verifies claimed ride times. Topic authentication cannot
prove that a rider is human or that an input sequence was played in real time.
