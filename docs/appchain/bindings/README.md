# Declarative bindings: developer learning path

Connect existing state machines without writing a Java coordinator. A binding
says: **when this component emits this event, check these conditions and build
that command**. The node executes a bounded, committed program identically on
every member.

For example, a registry update can append an audit entry. A later human vote
can complete an approval and update a record in the same atomic cascade. Your
application still owns its UI, command submission, business identities and keys.

This feature is experimental. Use a matching Yano X build containing declarative
bindings and its pinned Yano host; the upstream host release alone does not
install the optional X machines, compiler or Studio. The current source pins
Yano `0.1.0-pre17`. Do not treat this guide as a promise that every older Yano X
archive includes the feature.

## Choose your starting point

| Level | Chapter | What you will do |
|---|---|---|
| Beginner | [1. Your first workflow](01-first-workflow.md) | Put a registry value and automatically append an audit entry; validate and rehearse without running a node |
| Intermediate | [2. Conditions and mappings](02-conditions-and-mappings.md) | Filter events, convert types, use expressions and read participant state |
| Intermediate | [3. Approval workflows](03-approval-workflows.md) | Propose, approve twice, and carry state across blocks |
| Application developer | [4. Java integration](04-java-integration.md) | Submit normal commands with the Java APIs and inspect the workflow outcome |
| Advanced | [5. Operations and upgrades](05-operations-and-upgrades.md) | Diagnose rejections, discover proof keys, size budgets and plan safe evolution |
| Any level | [6. Author bindings in the Studio editor](06-guided-editor.md) | Build the same documents with catalog-guided forms, hand them to the CLI and read its reports |

Start with chapter 1; it needs Java 25 and an extracted matching JVM distribution,
but no Cardano funds, node cluster or private keys. Familiarity with YAML is enough.
For the surrounding app-chain concepts, see [the app-chain learning tracks](../README.md).

## Five concepts to keep separate

| Concept | Meaning in an application |
|---|---|
| Component | A named machine instance with its own state, configuration and ingress topic |
| Command | A request to that machine, encoded using its existing contract |
| Event | A typed result emitted by a successful transition, used to select bindings |
| Binding | Source event + optional conditions + target command or effect mapping |
| Receipt | The recorded outcome of one submitted source message and its derived cascade |

For a registry-to-audit binding the sequence is:

`client put command → registry transition → entry-put event → derived audit append`

The client submits only the put. The node derives the append. Both business
changes commit together or neither does; a finalized rejection still retains
its receipt. This atomic boundary is **one source message**, not a whole human
workflow spanning several votes or an external HTTP call.

## Do I need a separate bindings.yml?

No particular filename is required. There are two authoring arrangements:

- **Separate document:** keep `chain/bindings.yaml` (or any chosen filename) in
  source control. Use it with `appchain bindings` commands or pass it to
  `appchain init --recipe declarative-composite --bindings ...`.
- **Inline blueprint:** select the `declarative-composite` recipe and put the
  document body in that chain's `composite` field in `appchain.yaml`.

The standalone CLI document may contain the composite body directly or wrap it
in one `composite:` property. Do not pass a complete multi-chain `appchain.yaml`
to `appchain bindings compile`; that command expects the composite document.

When `init` imports a separate document it copies the definition into the
blueprint. It is **not** a live include: editing the original file afterwards
does not change the generated project. Rendering validates against installed
plugins, writes canonical binary IR into the chain configuration, and pins the
IR/profile/catalog identities in `appchain.lock`.

Nodes execute that committed IR, not a YAML file watched for changes. Editing
YAML or replacing a JAR does not update a running chain. See
[blueprint project authoring](../DECLARATIVE_BINDINGS_CLI.md#blueprint-projects)
and [upgrade planning](05-operations-and-upgrades.md#changing-a-workflow).

## Can I define bindings graphically?

Yes, with guided forms. Studio's **Bindings** page edits the same composite
document you would write in YAML: components, bindings, conditions, typed
mappings and limits, with a synchronized YAML view and dependency graph. An
authoring catalog exported from your plugin bundles supplies the events,
commands and fields. See [chapter 6](06-guided-editor.md).

The editor is not a separate workflow language or a drag-to-connect designer.
The YAML document and its compiled IR stay authoritative, graph positions are
presentation only, and Studio never compiles, runs or deploys anything: you hand
the document to the version-matched CLI and import its reports to see
diagnostics and rehearsal outcomes. Imported reports are unauthenticated files.

Studio also keeps its **read-only graph viewer** for a capability-manifest
snapshot, such as the one chapter 1 extracts from `validate` output. The CLI's `graph` command
exports Graphviz DOT. Neither graph authenticates chain identity or authorizes an
upgrade.

## When to use Java instead

Use bindings when existing catalog machines expose the commands and events you
need. Use a Java plugin for new state transitions or rules outside the bounded
language. Expressions cannot call arbitrary Java, query the network or create
authority. A condition is not a signature, and a derived command still passes
the target's authorization checks.

Reference material remains available for lookup:

- [Language, event execution, limits and rejection codes](../DECLARATIVE_BINDINGS.md)
- [CLI documents, context, fixtures and project generation](../DECLARATIVE_BINDINGS_CLI.md)
- [Retained-chain compatibility and profile preflight](../DECLARATIVE_BINDINGS_UPGRADES.md)

Next: [Your first workflow](01-first-workflow.md).
