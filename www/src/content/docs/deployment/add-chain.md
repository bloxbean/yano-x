---
title: "Add a chain without resetting your application"
description: "An independent chain can be added to a generated local JVM host project through a reviewed configuration revision and controlled restart. Existing chains…"
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/appchain/deployment/add-chain.md"
---
An independent chain can be added to a generated local JVM host project through
a reviewed configuration revision and controlled restart. Existing chains retain
their genesis, state, member policy, and history. Adding without a process restart
requires future upstream host lifecycle support.

## 1. Propose the addition

From the extracted, matching Yano X JVM release:

```bash
./yano.sh appchain chain add ./my-application \
  --chain-id documents --recipe document-trail
./yano.sh appchain plan ./my-application
```

`chain add` copies shared node placement from the existing project and edits only
`appchain.yaml`. The active configuration is unchanged. It accepts repeatable
`--capability` and non-secret `--answer name=value` options. A more complex genesis
profile can be authored directly in the blueprint.

Review the JSON plan. It should show existing chains as `UNCHANGED`, the new
chain as `ADD`, no blockers, and activation `STOP_RENDER_RESTART`. Save its digest.
The digest binds both the old lock and exact proposed blueprint; edits require a
new plan. Changes to existing consensus values, genesis, release, network, or
catalog are blocked. Optional/custom bundle installation requires a separately
qualified deployment; this apply route does not install plugins.

## 2. Record your verification reference and stop

Before stopping, save an old message ID, its finalized height, chain identity,
root, and proof. Take a recoverable backup using the supported procedure for the
runtime; do not copy an open RocksDB directory as a backup. Use a maintenance
window and stop clients that submit new work.

```bash
./my-application/scripts/stop
./yano.sh appchain apply ./my-application --plan <digest-from-plan>
./my-application/scripts/start
```

Apply refuses running local nodes, checks old generated files for manual edits,
checks node-local settings, stages the new configuration, and installs its lock
last. It never deletes or resets application or L1 data. It does not submit
application commands, execute anchor bootstrap, or install new plugin bundles.

This command supports same-machine host projects. A VM or Compose rollout needs
coordinated operator automation; it is not silently treated as a local rollout.

## 3. Verify both old and new chains

Run multi-chain `appchain drift` with every member as shown in the
[configuration guide](/deployment/configure/). Retrieve the old message and proof at the
saved height, then submit an appropriate typed command to the new chain. Verify
its finalized business result and root agreement on every member. Document-trail
commands use canonical CBOR; see [the state-machine guide](/state-machines/doc-trail/).

Check old-chain records after another stop/start. Compare proofs at the same
finalized height when observers or other traffic continue to advance the tip.

## Interrupted apply

An apply operation records `.deployment/pending.json` and a staged revision.
Startup refuses a pending operation. Restore the reviewed blueprint if it was
edited, then repeat apply using the **same plan digest**. The command accepts only
previous or staged bytes for each generated file; unexpected edits fail closed.
Keep the pending journal and staged revision until recovery finishes.

After new-chain work finalizes, repair forward. Do not roll back the new chain's
history, remove its stores, or replace old genesis identities. The previous lock
is diagnostic evidence, not a rollback of application state.
