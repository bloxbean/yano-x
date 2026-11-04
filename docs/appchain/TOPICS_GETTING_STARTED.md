# Get started with the topic registry

- **Goal:** create namespaces on a running chain, write to them with and without
  a signature, see each kind of refusal, and read a signed message back.
- **Before you start:** the Yano X JVM distribution, Java 25, `curl`, `jq`, `openssl`
  and `xxd`.
- **Time:** about 15 minutes.
- **Outcome:** a chain with an open namespace, a protected namespace, and a
  namespace with a size limit, all on three local nodes.

This guide covers direct writes with a writer's key. For browser users with
short-lived keys, continue with
[protect a topic and give users session keys](TOPIC_SESSION_KEYS.md).

## How the registry works

Every message has a topic. Without a registry, a topic is only a label, and any
message a member accepts can use any topic. The **topic registry** is
chain state that says who may write to which topics. Every member checks each
write against it, so one node cannot bypass the rules. You work with these parts:

| Part | What it is | Example |
|---|---|---|
| **Namespace** | A registry entry whose name ends in `/`. It covers every topic below it | `games/` |
| **Topic** | The label on a message. The longest matching namespace decides its policy | `games/private/x` |
| **Owners** | The keys that control a namespace: its policy, owners and children | 1 of `[owner key]` |
| **Policy** | The rules for writes: writers, payload size, schema | `writers: open` |
| **Writers** | Anyone (`open`), or a threshold of keys that must sign each write | 1 of `[writer key]` |

Three rules explain most of what you will see:

- **Topics are switched on at seal.** `topics.mode` is part of the chain's
  genesis, so an existing chain sealed without `topics:` cannot gain a registry.
  [Add topics to your own application](#add-topics-to-your-own-application)
  explains what to do instead.
- **Top-level names and children are approved differently.** A top-level name
  follows the chain's creation policy, by default a member vote. A child is
  signed by the parent namespace's owners, with no member vote.
- **Changes take effect later.** A change activates after the activation lag, 10
  app-chain blocks by default, and never applies to earlier messages. A chain
  makes blocks only from messages, so an idle chain needs traffic or `--heartbeat`.

For every policy field and refusal code, see
[topic policies and sessions](TOPIC_POLICIES.md).

## 1. Start a chain with topics

Run this from the distribution folder. `try` starts three nodes (alice, bob and
carol), seals an application with a chain called `arcade`, and sends one message:

```bash
./yano.sh appchain try --chain arcade=audit-log --topics arcade=members
```

> **✓ You should see** `Message … is final at height 1 on every node`.

Set these for the rest of the guide. The commands are written for bash; in zsh,
run `setopt sh_word_split interactive_comments` first:

```bash
ALICE="--node-dir appchain-try/alice/yano-node"
BOB="--node-dir appchain-try/bob/yano-node"
CAROL="--node-dir appchain-try/carol/yano-node"
BASE=$(jq -r .http appchain-try/alice/yano-node/node.json)
CHAIN="$BASE/api/v1/app-chain/chains/arcade"

./yano.sh appchain status $ALICE
```

> **✓ You should see** `chain arcade`, `height 1` and `members agree` on one line and, below it,
> `topics: namespaces created by member vote; unregistered topics open`.

The registry exists from the chain's first block. `try` has already sent that
first message. On your own chain, send one message before you continue.

## 2. Make an owner key and a writer key

Owners control a namespace, and writers only post to it. Use separate keys, so
that a service that can post cannot also change the rules:

```bash
(umask 077; openssl rand -hex 32 > owner.seed; openssl rand -hex 32 > writer.seed)

./yano.sh appchain topics public-key owner.seed
./yano.sh appchain topics public-key writer.seed
```

Each seed is a 32-byte Ed25519 seed as 64 hex characters, and each command
prints the matching public key. Copy the writer's public key for step 5. Keep
the seed files private and back up `owner.seed`, because it controls the
namespace. In production, keep seeds outside any served directory.

## 3. Create an open namespace by member vote

`init` reads the chain and writes a change file. `plan` checks that file against
the chain and writes the statement that members vote on:

```bash
./yano.sh appchain topics init games/ --owner-key-file owner.seed $ALICE
./yano.sh appchain topics plan topics.yaml $ALICE
```

> **✓ You should see** `create games/: a member vote`.

The change file looks like this. Keep the generated `nonce`:

```yaml
schemaVersion: 1
kind: "Topics"
chainId: "arcade"
commands:
- target: "games/"
  operation: "create"
  authority: "governance"
  nonce: "<generated>"
  owners:
    threshold: 1
    keys:
    - "<owner public key>"
  policy:
    writers: "open"
    schema: "none"
```

Each member then votes through their own node. Two of the three votes are enough
here, because the votes needed match the chain's block threshold, but every
operator should run the command. If enough votes are already in, as with the third
command here, `approve` casts nothing and waits. That command also sends the
heartbeat messages that make the 10 blocks:

```bash
./yano.sh appchain topics approve topics.plan.json --yes --no-wait $ALICE
./yano.sh appchain topics approve topics.plan.json --yes --no-wait $BOB
./yano.sh appchain topics approve topics.plan.json --yes --heartbeat $CAROL
```

> **✓ You should see** `create games/ is in effect`.

Drop `--yes` to read what you are voting for and confirm it. Each heartbeat is a
permanent entry on an `audit-log` chain. Heartbeats use a topic that no namespace
covers, so `--heartbeat` does not work on a chain with `unregistered: reject`. Send
traffic on a topic that an active namespace covers. The first namespace on such a
chain has none, so only further member votes can make its blocks.

Check the result. `resolve` tells you the policy a write would get next:

```bash
./yano.sh appchain topics resolve games/test $ALICE
./yano.sh appchain topics resolve other $ALICE
```

> **✓ You should see**
>
> ```text
> games/test → anyone may write, no schema, up to 65536 bytes (from games/ r1)
> other → no namespace covers it; anyone the members accept may write
> ```
>
> Each line is followed by a `proof:` line.

## 4. Post to the open namespace

An open topic takes an ordinary request, with no signature:

```bash
curl -sS -w '\nHTTP %{http_code}\n' -X POST "$CHAIN/messages" \
  -H 'Content-Type: application/json' \
  -d '{"topic":"games/test","body":"hello"}'
```

> **✓ You should see** `HTTP 202` with a `messageId`. This means the message was
> admitted, not that it is final yet.

## 5. Create a protected namespace with owner signatures

A child of `games/` needs a signature from the owners of `games/`, not a vote:

```bash
./yano.sh appchain topics init games/private/ --output private.yaml \
  --owner-key-file owner.seed $ALICE
```

In `private.yaml`, replace the `writers` line under `policy` with the writer's
public key from step 2. Use the writer's key here, not the owner's:

```yaml
  policy:
    writers:
      threshold: 1
      keys:
      - "<writer public key>"
    schema: "none"
```

Then plan, sign with the owner key, and submit:

```bash
./yano.sh appchain topics plan private.yaml $ALICE
./yano.sh appchain topics sign private.plan.json --key-file owner.seed $ALICE
./yano.sh appchain topics submit private.signed.json --heartbeat $ALICE
./yano.sh appchain topics resolve games/private/x $ALICE
```

> **✓ You should see** `games/private/ is in effect`, then
> `games/private/x → 1 of key … sign each write`, with the start of your writer
> key after `key`.

## 6. See an unsigned write refused

Send the request from step 4 to the protected topic:

```bash
curl -sS -w '\nHTTP %{http_code}\n' -X POST "$CHAIN/messages" \
  -H 'Content-Type: application/json' \
  -d '{"topic":"games/private/x","body":"hello"}'
```

> **✓ You should see** `{"code":"TOPIC_FRAME_INVALID"}` and `HTTP 400`.

A protected topic needs a **frame**: the payload plus a write proof with its
signatures. Plain text is not a frame, so the node refuses it. Nothing is written.

## 7. Post a signed message from Java

The CLI does not send signed writes, so applications use the Java or
[JavaScript](TOPIC_SESSION_KEYS.md#use-a-browser-session) client. Save this as
`SignedWrite.java` in the distribution folder:

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.client.TopicAuthoring;
import org.yanoproject.x.client.TopicClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

/** Usage: SignedWrite <base-url> <chain> <topic> <seed-file> <body> */
public class SignedWrite {
    public static void main(String[] args) throws Exception {
        String api = args[0] + "/api/v1";
        String chain = args[1];
        String topic = args[2];
        byte[] body = args[4].getBytes(StandardCharsets.UTF_8);

        // A write is signed for one chain generation, so read it from the node.
        String identity = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(api + "/app-chain/chains/" + chain + "/state/identity")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        String generationId = new ObjectMapper().readTree(identity).path("generationId").asText();

        TopicAuthoring.Signer signer = signer(Files.readString(Path.of(args[3])).strip());
        long notAfter = System.currentTimeMillis() + 5 * 60 * 1000;
        var request = TopicAuthoring.prepareWrite(chain, generationId, topic, body,
                TopicAuthoring.nonce(), notAfter, null); // null: no session credential

        AppChainClient client = AppChainClient.builder(api).chainId(chain).build();
        var result = new TopicClient(client).signAndSubmit(request, List.of(signer));
        System.out.println(result.messageId());
    }

    private static TopicAuthoring.Signer signer(String seedHex) {
        var key = new Ed25519PrivateKeyParameters(HexFormat.of().parseHex(seedHex), 0);
        String publicKey = HexFormat.of().formatHex(key.generatePublicKey().getEncoded());
        return new TopicAuthoring.Signer() {
            @Override
            public String publicKey() {
                return publicKey;
            }

            @Override
            public byte[] sign(byte[] statement) {
                var ed = new Ed25519Signer();
                ed.init(true, key);
                ed.update(statement, 0, statement.length);
                return ed.generateSignature();
            }
        };
    }
}
```

Sign with the wrong key first:

```bash
java -cp "tools/lib/*" SignedWrite.java "$BASE" arcade games/private/x \
  owner.seed "Signed from Java"
```

> **✓ You should see** an exception whose message ends in `HTTP 400 (TOPIC_WRITER_DENIED)`.
> The owner controls the namespace but is not one of its writers.

Then sign with the writer's key:

```bash
MESSAGE_ID=$(java -cp "tools/lib/*" SignedWrite.java "$BASE" arcade games/private/x \
  writer.seed "Signed from Java")
echo "$MESSAGE_ID"
```

> **✓ You should see** a 64-character message ID.

Your application can use the same `yano-x-client` jar from `tools/lib` or Maven.
Use your own signer in production, such as an HSM or a protected key file.

## 8. Read the message and decode it

A few seconds later, fetch the message:

```bash
curl -sS "$CHAIN/messages/$MESSAGE_ID" | jq
```

> **✓ You should see** `height`, `topic`, `sender` and `bodyHex`. Until the message
> is final, the lookup answers 404.

`bodyHex` is the message body in hex. On an open topic that is your text, so hex
decoding is enough:

```bash
TEXT_ID=$(curl -sS "$CHAIN/messages/by-topic/games%2Ftest" | jq -r '.messages[0].messageIdHex')
curl -sS "$CHAIN/messages/$TEXT_ID" | jq -r .bodyHex | xxd -r -p; echo
```

> **✓ You should see** `hello`.

Encode each `/` in the topic as `%2F` on the `by-topic` route. A literal `/`
answers 404. The route returns each message's height, index and ID.

On a protected topic, `bodyHex` is a CBOR frame, so you decode it in two steps:
hex to bytes, then bytes to a frame. Save this as `DecodeFrame.java`:

```java
import org.yanoproject.api.appchain.topics.TopicWireCodec;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public class DecodeFrame {
    public static void main(String[] args) {
        var frame = TopicWireCodec.decodeFrame(HexFormat.of().parseHex(args[0]));
        System.out.println(new String(frame.payload(), StandardCharsets.UTF_8));
        frame.proof().signatures().forEach(signature -> System.out.println("signed by " + signature.key()));
    }
}
```

```bash
java -cp "tools/lib/*" DecodeFrame.java \
  "$(curl -sS "$CHAIN/messages/$MESSAGE_ID" | jq -r .bodyHex)"
```

> **✓ You should see** `Signed from Java`, then `signed by` and your writer's
> public key.

A frame is a CBOR array, and each item's meaning comes from its position:

```text
[
  1,                      version
  h'…',                   payload: your bytes
  [                       write proof
    h'…',                 nonce, 16 bytes
    1793804787176,        notAfter, in milliseconds
    [[h'…', h'…']],       signatures: pairs of public key (32 bytes) and signature (64 bytes)
    null                  session credential, or null for a direct write
  ]
]
```

Two fields are easy to misread:

- **`sender` is not the writer.** It is the member node that submitted the
  message. The writer is the key inside the frame, and that is what the policy
  checks.
- **Decoding does not verify.** Members checked the signature when the message
  was written. To prove authorship later, use
  [`topics verify`](TOPIC_SESSION_KEYS.md#diagnose-and-verify).

## 9. Set a limit and change a policy

A policy can do more than name writers. Create a namespace that anyone can write
to, with a 32-byte limit:

```bash
./yano.sh appchain topics init games/scores/ --output scores.yaml \
  --owner-key-file owner.seed $ALICE
```

In `scores.yaml`, add this line under `policy`, indented like `schema`:

```yaml
    maxPayloadBytes: 32
```

```bash
./yano.sh appchain topics plan scores.yaml $ALICE
./yano.sh appchain topics sign scores.plan.json --key-file owner.seed $ALICE
./yano.sh appchain topics submit scores.signed.json --heartbeat $ALICE

curl -sS -w '\nHTTP %{http_code}\n' -X POST "$CHAIN/messages" \
  -H 'Content-Type: application/json' \
  -d '{"topic":"games/scores/today","body":"alice 100"}'
curl -sS -w '\nHTTP %{http_code}\n' -X POST "$CHAIN/messages" \
  -H 'Content-Type: application/json' \
  -d '{"topic":"games/scores/today","body":"this body is longer than thirty two bytes"}'
```

> **✓ You should see** `HTTP 202` for the first, and
> `{"code":"TOPIC_PAYLOAD_TOO_LARGE"}` with `HTTP 400` for the second.

To change a namespace later, run `init` on the same name. It writes an `update`
starting from the active policy, signed by that namespace's own owners:

```bash
./yano.sh appchain topics init games/scores/ --output scores-update.yaml \
  --owner-key-file owner.seed $ALICE
```

> **✓ You should see** `update games/scores/ by the owners of games/scores/`.

Edit only the fields you mean to change, then run `plan`, `sign` and `submit` as
above. A new revision applies only from its activation height, so earlier messages
stay valid under the policy they were written with.

## 10. Read the registry

All of these are reads:

```bash
curl -sS "$CHAIN/topics/config" | jq                                  # creation policy and limits
curl -sS "$CHAIN/topics/entries/games%2Fprivate%2F" | jq              # one entry, encode each /
curl -sS "$CHAIN/topics/resolve?topic=games/private/x" | jq           # effective policy at the tip
./yano.sh appchain topics resolve games/private/x --json $ALICE | jq  # the same, with proofs
```

`topics submit` and `topics approve` print a message ID.
`curl -sS "$CHAIN/topics/receipts/<messageId>"` shows the outcome of a signed
command, or of the vote that decided a member vote, such as
`{"code":"ACCEPTED","activation":24}`. An earlier vote and an ordinary message
answer `404`, and an `approve` that finds enough votes casts nothing and prints no
ID. See the [REST API](../site/reference-rest-api.md#topic-registry) for
every route.

## Clean up

```bash
./yano.sh appchain try --clean
rm -f owner.seed writer.seed topics.yaml topics.plan.json private.* scores*
rm -f SignedWrite.java DecodeFrame.java
```

## Add topics to your own application

An application sealed without `topics:` cannot switch them on, because that
setting is in its genesis. Seal a new application with the same operators, and run
it on the same nodes. The existing chain and its data do not change.

The coordinator creates the draft. Replace the operators with your application's
operators, and pick a chain ID the nodes do not already run, here `league`. On a
devnet the lead must stay the same:

```bash
./yano.sh appchain new arcade --output arcade-app --network devnet \
  --operators alice,bob,carol --chain league=audit-log \
  --topics league=members --threshold majority --yes
```

Send `arcade-app/arcade.draft.yaml` to every operator, including yourself. Each
operator runs `request`, which reuses their node's key, name and ports, and sends
the card back:

```bash
./yano.sh appchain request arcade-app/arcade.draft.yaml --output arcade-app
```

If your nodes are in other folders, such as the `try` nodes, add
`--node-dir <dir>` to `request`, `join`, `stop` and `start`. `join` asks you to
confirm the fingerprint; without a terminal, pass
`--expect-fingerprint <fingerprint>`.

With all the cards beside the draft, the coordinator seals, and sends
`arcade.chain` to every operator. Each operator compares the fingerprint, joins
and restarts once:

```bash
./yano.sh appchain seal arcade-app/arcade.draft.yaml                # coordinator
./yano.sh appchain join arcade-app/arcade.chain                     # every operator
./yano.sh appchain stop && ./yano.sh appchain start                 # every operator
```

A restart pauses block production on every chain the node runs if it leaves too
few members online, so restart one node at a time. Then send one message on the
new chain to make its first block:

```bash
curl -sS -X POST "$BASE/api/v1/app-chain/chains/league/messages" \
  -H 'Content-Type: application/json' -d '{"topic":"hello","body":"first"}'
```

On a chain with `unregistered: reject`, only this first message can use a topic
that no namespace covers. Follow this guide from
[step 2](#2-make-an-owner-key-and-a-writer-key), with `CHAIN` set to the new chain
and its ID in place of `arcade` in step 7. A node with more than one application
needs `--chain league` on `topics init` and `topics resolve`, and the application
name on status: `status arcade`. A
[read-only node](deployment/add-chain.md#follow-a-chain-without-joining-it) follows
the new chain with its own `request` and `join`, and no approval.

## Troubleshooting

| You see | What it means and what to do |
|---|---|
| `the chain has no committed topic registry yet …` | Run `status` (`status <app>` on a node with several applications). With no `topics:` line under the chain, it was sealed without topics, so [seal a new application](#add-topics-to-your-own-application). With that line and height 0, send one message. |
| `Unknown app chain: <name>` | The URL needs the chain ID, which is not the namespace. `status <app>` lists the chain IDs. |
| `TOPIC_FRAME_INVALID` | The topic is protected and the body is not a signed frame. Use a [client](#7-post-a-signed-message-from-java). |
| `TOPIC_WRITER_DENIED` | The signature is invalid, below the threshold, or not from a writer key. Check the key with `topics public-key <seed>` and `topics resolve <topic>`. An owner is not a writer unless it is also listed under `writers`. |
| `TOPIC_PAYLOAD_TOO_LARGE` | The payload is above `maxPayloadBytes`. `topics resolve` shows the limit. |
| `TOPIC_UNREGISTERED` | No namespace covers the topic, and the chain has `unregistered: reject`. Use a covered topic. |
| `TOPIC_PROOF_REPLAYED` | This signed write was already used. Treat it as a duplicate, not a new write. |
| `TOPIC_PROOF_EXPIRED` | The write's `notAfter` has passed or is too far ahead. Sign a new write. |
| `… still waiting for …` from `submit` or `approve` | Read the rest of the line. If votes are missing, ask the other operators to run `approve`. Otherwise the chain is not making blocks: send traffic, or run the command again with `--heartbeat`. It is safe to repeat. |
| HTTP `503`, `This node is not an active member …` | You posted through a read-only node. Post through a member; this refusal does not clear with a retry. |
| HTTP `404` from `messages/by-topic/games/test` | Encode the `/` in the topic: `messages/by-topic/games%2Ftest`. |
| `chainId: "REPLACE_CHAIN_ID"` in the file `init` wrote | `init` could not tell which chain you mean. Run it from the folder that holds `yano-node/`, or pass `--node-dir`, or `--peer` with `--chain`. A node that runs several chains also needs `--chain <id>`, even with `--node-dir`. `plan` fails until this is fixed. |

**Next:** [protect a topic and give users session keys](TOPIC_SESSION_KEYS.md), or
read the full [policy model](TOPIC_POLICIES.md).
