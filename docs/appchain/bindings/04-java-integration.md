# 4. Java integration

[Previous: Approval workflows](03-approval-workflows.md) ·
[Learning path](README.md) · [Next: Operations and upgrades](05-operations-and-upgrades.md)

A Java application submits ordinary commands to a deployed component's ingress
topic. The committed binding program decides which follow-up commands run.
Your application does not need to instantiate a composite engine or implement
the same workflow again in Java.

These examples target the current experimental binding APIs.
Use a matching, locally published Yano X version when these APIs are not in a
released artifact. Do not infer availability from an older SDK or copy a
historical example's local version number. The node also needs the exact Yano
host build and matching JVM ZIP required by its plugin bundles; current
declarative admission requires host plugin API level 11.

## Separate command use from binding authoring

There are two different jobs:

| Job | Application developer uses | Result |
|---|---|---|
| Author the workflow | YAML, `appchain bindings`, explicit catalog/context | Reviewed IR and a pinned deployment profile |
| Use the deployed workflow | `AppChainClient` and public command codecs | Source message ID, committed receipt, state/proofs |

Changing a Java request's business data does not change the workflow. Changing
YAML does not update a running node. Keep those release procedures separate;
see [operations and upgrades](05-operations-and-upgrades.md).

The examples below assume an already deployed registry-to-audit workflow from
[conditions and mappings](02-conditions-and-mappings.md), or the `reviews`/`audit`
workflow from [approval workflows](03-approval-workflows.md). The offline fixture
files themselves do not deploy either workflow.

## Add the public client dependency

For a separate Java 25 application, this Groovy Gradle fragment selects an
explicit version supplied through `-PyanoXVersion=...` or your application's
`gradle.properties`:

```groovy
plugins {
    id 'application'
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenLocal() // For the exact locally published experimental build.
    mavenCentral()
}

def yanoXVersion = providers.gradleProperty('yanoXVersion').get()

dependencies {
    implementation "org.yanoproject.x:yano-x-client:${yanoXVersion}"
}

application {
    mainClass = 'WorkflowClient'
}
```

Choose the version actually published for the matching build, not a guessed
release. The client declares API dependencies on `yano-x-stdlib-contracts` and
`yano-x-composite-contracts`, which provide the codecs used here. Its publication
also pins host API/proof dependencies. Resolve that complete published graph;
do not independently mix host and X versions or depend on a sibling checkout.
See [build and test](../../BUILD_AND_TEST.md) and
[distribution builds](../../BUILD_DISTRIBUTIONS.md) for coordinated publication.

The client's dependency is a library. Installing it in your application does
not activate node plugins. Runtime bundles belong in the node's manifested
plugin directory; you do not need `composition/runtime` or devtools compiler
classes on the application's classpath.

## Submit once, then read the receipt

Save the following class as `src/main/java/WorkflowClient.java` alongside your
`build.gradle`. This small complete class exposes two operations. `put` submits the same
registry bytes as the CLI learning fixture; `receipt` reads the result later.
Pass your node's API base URL (ending in `/api/v1`), chain ID, and operation as
arguments. For `receipt`, add the source message ID printed by `put`.

```java
import java.util.Arrays;
import java.util.HexFormat;

import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;
import org.yanoproject.x.stdlib.contracts.KvRegistryContract;

public final class WorkflowClient {
    public static void main(String[] args) {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "Usage: WorkflowClient <api-base-url> <chain-id> <put|receipt> [message-id]");
        }
        var builder = AppChainClient.builder(args[0]).chainId(args[1]);
        String apiKey = System.getenv("YANO_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            builder.apiKey(apiKey);
        }
        AppChainClient client = builder.build();

        switch (args[2]) {
            case "put" -> {
                byte[] body = KvRegistryContract.put(new byte[]{1, 2}, new byte[]{3, 4});
                var submitted = client.submit("records.command.v1", body);
                System.out.println("Submitted source message: " + submitted.messageId());
            }
            case "receipt" -> {
                if (args.length != 4) {
                    throw new IllegalArgumentException("receipt requires a source message ID");
                }
                readReceipt(client, args[3]);
            }
            default -> throw new IllegalArgumentException("Unknown operation: " + args[2]);
        }
    }

    private static void readReceipt(AppChainClient client, String sourceId) {
        byte[] id = HexFormat.of().parseHex(sourceId);
        if (id.length != 32) {
            throw new IllegalArgumentException("Source message ID must contain 32 bytes");
        }
        String canonicalId = HexFormat.of().formatHex(id);
        var result = client.query(
                "composite/binding-receipt-v1/" + canonicalId, new byte[0]);
        if (result.payload().length == 0) {
            System.out.println("No receipt at committed height " + result.committedHeight());
            return;
        }
        BindingReceiptV1 receipt = BindingReceiptV1.decode(result.payload());
        if (!Arrays.equals(id, receipt.sourceMessageId())) {
            throw new IllegalStateException("Receipt source ID mismatch");
        }
        System.out.printf("Source block %d; read at committed height %d; accepted=%s%n",
                receipt.height(), result.committedHeight(), receipt.accepted());
        if (!receipt.accepted()) {
            System.out.printf("Rejected step %s: %s%n", receipt.failedStepOrdinal(), receipt.code());
        }
        for (var step : receipt.steps()) {
            System.out.printf("Step %d: component=%s status=%s binding=%s%n",
                    step.ordinal(), step.targetComponentId(), step.status(), step.bindingId());
        }
    }
}
```

Use `put` once and keep its message ID. After processing, use `receipt` with
that ID. For the two-byte value above, the condition is true and a successful
receipt contains the registry source and derived audit append. The source's
`bindingId` is null because it was submitted externally.

With Java 25 and Gradle installed (or your project's existing Gradle wrapper),
run from the Java application directory. Set `YANO_X_VERSION` to the exact
published version selected above, and replace the API URL and chain
ID with your actual deployment values; the example port is not a promise about
your node configuration. These commands **submit to a running chain**, unlike
the offline tutorials:

```bash
gradle run -PyanoXVersion="$YANO_X_VERSION" \
  --args='http://localhost:8080/api/v1 my-chain put'
gradle run -PyanoXVersion="$YANO_X_VERSION" \
  --args='http://localhost:8080/api/v1 my-chain receipt <source-message-id-hex>'
```

Set `YANO_API_KEY` through your application's secret-management mechanism when
the node requires it; do not commit it into this source or binding YAML. Use the
node's configured HTTPS endpoint outside a local development environment.

An empty payload means no receipt was found in that committed snapshot. It
does not prove rejection or justify blindly resubmitting. In an application UI,
keep a pending state and query with a bounded retry/backoff policy; report
transport errors separately from a decoded rejected receipt. This sample lets
SDK errors propagate rather than disguising them as “still pending.”

The real methods used are `submit(String, byte[])`, returning
`AppChainClient.SubmitResult`, and `query(String, byte[])`, returning
`AppChainClient.QueryResult`. Query requires an explicit chain ID. The SDK
decodes the HTTP `payloadHex` into `payload()` bytes and retains snapshot
`committedHeight()` and `stateRoot()` metadata. Do not hex-decode `payload()` a
second time. The receipt query takes empty parameters and returns canonical
receipt CBOR or empty bytes, not a JSON business object.

## Encode the component command, not arbitrary JSON

The public codec import for registry writes is
`org.yanoproject.x.stdlib.contracts.KvRegistryContract`. Other stock command
codecs include `ApprovalsContract` and `DocTrailContract` in the same package.
For a configured `AppChainClient client`, these are ordinary submission calls:

```java
import org.yanoproject.x.stdlib.contracts.ApprovalsContract;

// Propose now; inspect this submission's receipt before presenting it as pending.
var proposal = client.submit("reviews.command.v1",
        ApprovalsContract.propose("a", new byte[]{1}, 2, 0));

// A later human decision, submitted through the appropriate member's ingress.
var vote = client.submit("reviews.command.v1", ApprovalsContract.approve("a"));
```

These two statements illustrate separate user actions, not an automatic
approval loop. The second distinct member must submit its own later vote.
Ordinary REST submission is signed by the ingress node's member key. Two HTTP
users or two SDK objects pointing at the same member do not become two distinct
voters. Actor-signed governed approvals carry their own business authorization
inside the command; see the [governed recipe discussion](03-approval-workflows.md#generate-real-governed-dpp-or-feed-bindings).

`ApprovalsContract.propose` takes an item ID, payload bytes, required vote count,
and `deadlineMillis`; zero means no deadline in this example. Component topics
come from the deployed YAML. Do not substitute
`ApprovalsContract.DEFAULT_TOPIC` (`approvals.command.v1`) for
`reviews.command.v1` unless your configured ingress actually uses it.

For direct document-trail use, the encoder is
`DocTrailContract.append(String entityId, byte[] entryHash, String reference)`.
In this workflow, however, the binding performs that append after approval.
Submitting another append from the client would be a separate command, not
completion of the existing atomic cascade.

`submitText` and `submitTyped` also exist in the generic SDK, but they do not
change the receiving component's wire contract. JSON representing an order is
not automatically a registry put. Wrap application bytes with the correct
command codec; use the declared application schema for governed map actions.

## Treat acceptance, execution, and proof as separate results

`submit` succeeds on HTTP 202. That confirms submission into the host processing
path, not the success of any derived action. A finalized receipt's `accepted()`
is the cascade outcome. A `PLANNED` step inside a rejected receipt did not
commit. In the approval workflow, an accepted proposal receipt means the
proposal succeeded, not that approval has already happened.

An authenticated proof is a further step. Query bytes and snapshot metadata
alone do not prove finality to a caller that does not trust the serving node.
The supported receipt-key query returns raw physical key bytes. With `client`
and a canonical source `messageId`, the public SDK can retrieve a state proof:

```java
var keyResult = client.query(
        "composite/binding-receipt-key-v1/" + messageId, new byte[0]);
byte[] receiptKey = keyResult.payload();
var proof = client.proof(receiptKey);
```

Key discovery works even if the receipt does not exist; it proves no presence
claim. `proof` returns an `Optional<AppChainClient.Proof>`, and a returned proof
must still be checked for presence and verified against independently trusted
chain identity and finality/root information. `client.proof(receiptKey, height)`
requests a positive retained height when comparing evidence from an exact
snapshot. Sequential current-state calls can observe different heights.

Use the [registry and proofs tutorial](../tutorials/02-registry-and-proofs.md)
and [anchor verification tutorial](../tutorials/07-anchors-and-verification.md)
for that trust boundary. There is no need to import an internal engine namespace
encoder just to discover a receipt key. Derived commands are not independently
signed finalized messages, so a source-message inclusion proof and a binding
receipt state proof answer different questions.

If the host rejects admission with `COMMAND_PAYLOAD_TOO_LARGE` or
`COMMAND_WORK_EXCEEDED`, fix the command/profile problem before retrying. If a
finalized receipt rejects dynamically, retain it, correct the cause, and submit
a fresh message only when appropriate. Same-ID replay retains the original
terminal receipt. A fresh ID does not bypass authorization, business idempotency,
or one-use approval consumption. See the
[submission/retry reference](../DECLARATIVE_BINDINGS.md#submission-validity-and-retry).

## Source anchors for the API

The dependency coordinates are defined in `config/artifacts-v1.json`; transitive
contract dependencies are declared in `sdk/client/build.gradle`. The methods
above come from `AppChainClient`, `KvRegistryContract`, `ApprovalsContract`,
`DocTrailContract`, and `BindingReceiptV1`. Receipt and receipt-key query behavior
is implemented by `CompositeStateMachine.query`.

Existing verification sources include `AppChainClientSubmitTest`,
`AppChainClientQueryTest`, and `DeclarativeBindingsRuntimeTest`, which exercises
approval/audit receipts and persisted restart through the host. The snippets
here are source-checked examples; this page does not claim a new build or test
run. The broader [app-chain user guide](../../APP_CHAIN_USER_GUIDE.md) covers
the remaining SDK and transport features.

[Next: Operate and evolve a pinned workflow](05-operations-and-upgrades.md)
