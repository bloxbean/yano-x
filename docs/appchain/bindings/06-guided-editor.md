# 6. Author bindings in the Studio editor

[Previous: Operations and upgrades](05-operations-and-upgrades.md) ·
[Learning path](README.md)

Studio's **Bindings** page is a guided editor for the same composite binding
documents you write in YAML. Forms are backed by an authoring catalog exported
from your real plugin bundles, so you pick events, commands and fields instead
of typing them. Studio never compiles or runs anything: the version-matched CLI
validates and rehearses the document, and Studio explains the reports it writes.

Studio is static. It makes no network requests beyond its own files, installs
no plugins, signs nothing, submits nothing and stores nothing in the browser.
Download your work before closing the tab.

## 1. Open the editor and load a starter

Studio is the static site in the distribution's `studio/` directory. Serve it
locally, for example with `python3 -m http.server 8080 --directory studio`, open
the localhost URL and choose **Bindings** in the header. Browsers block Studio's
modules when the page is opened directly from disk. Choose **Registry to audit** under *Starter* and
select **Load starter**. The starter is the same document as
[your first workflow](01-first-workflow.md): a registry put appends an audit
entry in the same atomic cascade.

The page has four views of one draft:

| View | Use it to |
|---|---|
| **Form** | Edit components, bindings, conditions, mappings and limits |
| **YAML** | Read or edit the exact document text |
| **Graph** | See components and bindings in execution order; arrange the drawing |
| **Validate** | Get CLI commands, import CLI reports and read rehearsal outcomes |

Everything is reachable with the keyboard. The graph has a textual equivalent
below the drawing.

## 2. Choose the right catalog

The left column shows the authoring catalog. Studio ships a **bundled reference
catalog** for the first-party machines under the tutorial context only. For your
own chain, export a catalog from the exact plugin directory and context you will
deploy with:

```bash
./yano.sh appchain bindings catalog bindings.yaml \
  --plugins-directory /absolute/path/to/plugins --context context.json \
  > catalog.json
```

Probing constructs the installed plugins with their own code, unsandboxed, exactly
as compilation does: export catalogs only from bundles you trust. The command
describes the document's own components; add `--machine <selector>` for machines
you have not used yet, or `--all` for every installed selector.

Import `catalog.json` with **Import catalog JSON**. A catalog describes each
machine *for one exact configuration*: Studio never fills in or normalizes
settings. If you change a component's settings, the form says the configuration
is not described; export the catalog again for the edited document. Without a
matching descriptor, fields become free text and checks are limited.

## 3. Edit with forms

- **Components** are named instances in declaration order. *Rename…* previews
  every reference it updates. Renaming changes the component's state namespace and
  default topic, so it changes a deployed chain's committed profile.
- **Bindings** run in list order. *Move earlier* and *Move later* change the
  committed program.
- **Conditions** are field comparisons, state lookups or restricted CEL
  expressions. All of them must hold.
- **Mappings** list the target command's fields with their type, whether they are
  required, and whether they are *evidence*. Evidence must be copied directly from
  an event field. Sources are event fields, typed literals (bytes are explicit
  hexadecimal), documented functions nested at most two levels, or expressions.
- **Limits and activation** shows every committed limit. Empty means omitted:
  the default applies and is not written into your document.

Text values and expressions with line breaks are edited in multi-line fields.
Browser fields cannot hold carriage returns exactly, single-line fields cannot
hold line breaks, and name fields trim what you type. So a value with a carriage
return, or a name with a line break or surrounding spaces, is shown and edited as
a quoted JSON string instead, for example `"line 1\r\nline 2"`, and nothing is
lost. Typing in the YAML view keeps the document's line endings.

The first form edit of an imported document rewrites it as canonical YAML, which
drops comments. Studio asks first, and **Download original** always returns the
file exactly as imported. **Reset draft** discards every edit.

If you edit the YAML text into something that is not a valid document, the forms
keep the last valid draft and are disabled, and downloads are refused until you
fix the text or choose *Revert to the last valid draft*. A document that uses a
construct Studio cannot edit exactly opens read-only.

**Advisory checks** in the right column point at likely mistakes and open the
relevant form field. They never mean the document is valid.

## 4. Hand off to the CLI

Open **Validate**. Choose the scenario, for example *Validate and rehearse 1
block*, then download `bindings.yaml` and `bindings-handoff.txt`. The commands
use fixed file names only:

```bash
./yano.sh appchain bindings catalog bindings.yaml \
  --plugins-directory <plugins-directory> --context context.json > catalog.json
./yano.sh appchain bindings validate bindings.yaml \
  --plugins-directory <plugins-directory> --context context.json \
  --report validate-report.json
./yano.sh appchain bindings dry-run bindings.yaml \
  --plugins-directory <plugins-directory> --context context.json \
  --fixture fixture-1.json --report dry-run-report-1.json > result-1.json
```

`--report` writes an editor report beside the normal output; the normal output
and exit codes are unchanged. A report matches only a catalog exported by the same
CLI from the same plugin directory and context, so the catalog command comes
first: import its `catalog.json` before the reports. The bundled reference catalog
assists editing but will not match reports from your installation.

For the starters, **Download tutorial context.json** gives a synthetic context and
**Download example fixtures** gives public, synthetic `fixture-N.json` blocks; the
distribution also ships them under `examples/bindings/fixtures/`. Their
authentication proofs are placeholders, which dry runs accept because they assume
authenticated inputs.

## 5. Read the results

Import the report files with **Import report files**. Import the fixture files
too, so Studio can confirm that each rehearsal used them. Importing a fixture
with the same file name replaces the earlier one, and rehearsals of the replaced
content become stale; the status line names blocks that are stale or unverified.
After rerunning the CLI, import the new report too. Earlier reports stay visible
as history. Studio links continuations by the preceding height and state digest,
so alternate reports at the same height do not disrupt a verified chain. A
continuation needs at least one matching predecessor whose own chain is verified.
The status line says what Studio can claim:

| Status | Meaning |
|---|---|
| Draft with local checks only | No CLI report describes this draft |
| Imported matching CLI report | A report says the CLI validated exactly this document, context, plugin catalog and tool |
| The CLI rejected exactly the current inputs | A matching report says compiling or validating the document failed; its diagnostics link to the form |
| Rehearsed source outcomes | Reports give the real engine's results for these exact, assumed fixture inputs, with every continued block verified |
| Stale or unverifiable report | Something differs; the report is history, not a result for this draft |

The status considers every imported report, in any order. A dry-run that failed
only on its fixture or continuation is listed separately and never counts as a
rejection of the document. For a composite opened from a blueprint the wording
never says "validated": the CLI checked the extracted composite under the context
you supplied, and project rendering, with the context it derives from the
blueprint, decides.

A report is an unauthenticated file: anyone can edit one. Studio decodes every
receipt from its canonical bytes and rejects reports that contradict themselves,
but a match only shows the same inputs were used. It is not an attestation, a
finality certificate or deployment approval.

For a validation failure, **Show in the form** opens the recorded location, or its
closest control, when the report matches. Rehearsals are shown block by block, including empty blocks,
with each block's assumed timestamp, prior root and pending effects. Every source
message says what happened:

- **Accepted**: every recorded step committed together in the rehearsal.
- **Rejected**: nothing from the cascade committed, including the source command.
  Planned steps are shown as *planned, not committed*.
- **Replay** or **duplicate**: the receipt belongs to an earlier message; nothing
  ran again.

Conditions show whether a binding ran, which clause was false, or where an
evaluation failed when the receipt proves it. A target component can reuse a
framework rejection code, so Studio never guesses a location the receipt does
not record.

## 6. Multi-block approvals

Load **Approval to audit**. A proposal and each vote are separate messages from
different senders in separate blocks, as in [approval workflows](03-approval-workflows.md).
Choose *Validate and rehearse 4 blocks*, or select **Download example fixtures**,
which saves `fixture-1.json` to `fixture-4.json` (a proposal, a first vote, an
empty block and a second vote) and selects four blocks. The commands chain each
block's `--prior-result` into the next. After importing the catalog, the four
reports and the four fixtures, Studio checks that heights are consecutive and that
each block continues the previous block's state. The example fixtures carry
placeholder authentication proofs and synthetic senders; two browser tabs or two
fixture senders are not two consensus members or real business actors.

## 7. Blueprints and layout

**Import appchain.yaml blueprint** opens a chain's inline `composite`. **Download
appchain.yaml** replaces only that composite, then re-reads the file to prove
that every other chain and field is unchanged; otherwise it refuses, and you can
download the composite document alone. An unedited blueprint downloads byte for
byte. Project rendering still validates the blueprint independently.

Graph positions are presentation only. Drag nodes or focus one and use the arrow
keys; **Download layout** saves a separate `bindings.layout.json`. Layout never
changes the document, its compiled program or its identity.

## 8. After export

- **The document goes back through the project workflow.** Use the downloaded
  file with `appchain bindings` commands, pass it to `appchain init --bindings`, or
  place it in a blueprint's `composite` field. Rendering copies it into the
  project; it is not a live include, and nothing Studio does changes a running
  chain.
- **Changing a deployed workflow is an upgrade.** Renaming a component or binding,
  reordering bindings or changing a limit changes the committed program. Follow
  [changing a workflow](05-operations-and-upgrades.md#changing-a-workflow) and the
  [retained-chain upgrade reference](../DECLARATIVE_BINDINGS_UPGRADES.md);
  `profile-check` is a reconstruction preflight, not proof of migration or replay
  safety.
- **Real actors need real proofs.** Dry runs assume authenticated inputs. For
  business actors in different organizations, use the
  [actor-signed policy path](03-approval-workflows.md#generate-real-governed-dpp-or-feed-bindings).

Return to the [learning path](README.md), or read the
[CLI reference](../DECLARATIVE_BINDINGS_CLI.md#editor-catalogs-and-reports) for
the catalog and report file formats.
