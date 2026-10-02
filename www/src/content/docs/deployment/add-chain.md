---
title: "Add a chain without resetting your application"
description: "The new chain runs beside the old ones, and an old message still proves against its saved root."
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/appchain/deployment/add-chain.md"
---
- **Goal:** add an independent chain to a running local project and keep the
  history of the chains it already has.
- **Before you start:** a generated local JVM host project, such as the one from
  [Configure your application](/deployment/configure/), and the matching Yano X JVM
  release.
- **Time:** about 15 minutes, including a short maintenance stop.
- **Outcome:** the new chain runs beside the old ones, and an old message still
  proves against its saved root.

An independent chain can be added to a generated local JVM host project through
a reviewed configuration revision and controlled restart. Existing chains retain
their genesis, state, member policy, and history. Adding without a process restart
requires future upstream host lifecycle support.

<!-- illustration: chain-add-lifecycle -->
1. **Propose.** `chain add` edits only `appchain.yaml` and prints
   `CHAIN_PROPOSED`.
2. **Plan.** `plan` prints `PLAN_READY`, each chain's action, and a digest to
   save.
3. **Stop.** Record an old message, height, root, and proof, take a backup, and
   stop the nodes.
4. **Apply.** `apply --plan <digest>` checks the plan, installs the new
   revision with the lock last, and prints `APPLIED`.
5. **Start.** The start script refuses a pending apply or an unapplied lock,
   then starts the nodes.
6. **Verify.** `drift` prints `DRIFT_OK`; the old proof and a new-chain command
   both check out.
<!-- /illustration -->

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

> **✓ You should see** `CHAIN_PROPOSED: appchain.yaml updated.` from `chain add`,
> then a JSON plan with `"status" : "PLAN_READY"`.

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

> **✓ You should see** `APPLIED: configuration revision installed; retained state
> preserved.`, then `Started 3 ready node processes; see logs/`.

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
Startup refuses a pending operation with `Resume pending apply first`. Restore the reviewed blueprint if it was
edited, then repeat apply using the **same plan digest**. The command accepts only
previous or staged bytes for each generated file; unexpected edits fail closed.
Keep the pending journal and staged revision until recovery finishes.

After new-chain work finalizes, repair forward. Do not roll back the new chain's
history, remove its stores, or replace old genesis identities. The previous lock
is diagnostic evidence, not a rollback of application state.

## Troubleshooting

| You see | What it means and what to do |
|---|---|
| `"status" : "PLAN_BLOCKED"` with `existing consensus or genesis changes require a separate migration` | The blueprint also changes an existing chain. Restore its values; this route only adds chains. |
| `Plan is stale; run plan again` | `appchain.yaml` or the lock changed after you planned. Review a new plan and apply its digest. |
| `Stop all project nodes with scripts/stop before applying` | Run `./my-application/scripts/stop` first. |
| `Resume the pending operation with its original plan digest` | An earlier apply was interrupted. Repeat it with that apply's digest. |

**Next:** [operate remote VMs](/deployment/operators/).
