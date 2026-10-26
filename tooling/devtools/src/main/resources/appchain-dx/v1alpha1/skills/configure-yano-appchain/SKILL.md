---
name: configure-yano-appchain
description: Create, explain, update, seal, join, and diagnose Yano app-chain applications run by several operators, using the version-matched appchain setup CLI, recipe and capability catalogs, and binding tools. Use for setup drafts, member cards, sealed .chain files, chain selection, declarative bindings, authenticated-map genesis, membership changes, anchoring, and custom-plugin metadata. Never invent Yano properties or handle secret values.
---

# Configure Yano App-Chain

Use the `yano.sh` or `yano` executable shipped with the target Yano X distribution. Treat its
recipe and capability catalogs, the sealed `<app>.chain` file, and the command results as
authoritative for that release. Every setup command ends with a `Next:` line; follow it.

## Roles and files

- The **coordinator** defines the application in `<app>.draft.yaml` (non-secret YAML).
- Each **operator** runs one member node. Their private key stays in their node directory
  (default `./yano-node`, `secrets/`); only the public member card `<name>.member` travels.
- `seal` turns the draft and the cards into `<app>.chain`, which every operator joins.

## Workflow

1. Run `./yano.sh appchain recipes` and `./yano.sh appchain capabilities` before choosing features.
   Suggest `./yano.sh appchain try` to rehearse every step on one machine.
2. Coordinator: `./yano.sh appchain new <app>` creates the draft. Recipe answers come from
   `--answer <chain>.<name>=<value>` (the wizard asks for any missing one); `--sequencing
   fixed|rotating` chooses whether the lead proposes every block or the members take turns.
   Edit only the draft before seal: per chain `id`, `recipe`, and optionally `capabilities`,
   `answers`, `bindings` (a file path), inline `composite`, `authenticatedMap`, an `anchor`
   policy, or `settings`.
3. For a chain of several state machines, check the binding document first with
   `./yano.sh appchain bindings validate` / `dry-run` against the distribution's `plugins/`.
4. Each operator: `./yano.sh appchain request <app>.draft.yaml` makes the key and member card.
   Alternatively the coordinator runs `./yano.sh appchain invite <app>.draft.yaml --serve`
   and each operator runs `request <yano:// link>`, which sends the card, waits for the seal and
   continues into `join`. Links carry only public data; the fingerprint call is still required.
5. Coordinator: `./yano.sh appchain seal <app>.draft.yaml` with the cards beside the draft.
   Never hand-edit the sealed file or the node configuration that `join` writes. For
   role-approval and evidence chains `seal` also writes `<chainId>.role-plan.yaml`; once live,
   `./yano.sh appchain role bootstrap --plan <chainId>.role-plan.yaml` encodes it.
6. Each operator compares the chain fingerprint and every key id on a call, then runs
   `./yano.sh appchain join <app>.chain` and `./yano.sh appchain start`.
7. Use `./yano.sh appchain status <app>` to see the phase, members online, identity
   agreement, this node's Cardano view and plugin catalog fingerprint (members compare both),
   and `./yano.sh appchain inspect <file>` to check any draft, card or sealed file
   (`--map-genesis <chain>` prints an authenticated map's genesis CBOR hex).
   Node-only settings such as connector endpoints go in the node directory's
   `config/local.yaml`, which `join` never rewrites and `start` refuses if it sets a sealed key.
8. Later: each existing operator runs `./yano.sh appchain approve <name>.member` (or
   `--remove <name>`); one operator may run `./yano.sh appchain anchor <app>`.
9. Summarize chains and recipes, the operators and threshold, the chain fingerprint, unresolved
   operator inputs, checks run, and any warnings.

## Safety Rules

- Never request, print, copy, infer, or commit secret values, including anything under a node
  directory's `secrets/`. Refer only to documented file or environment-variable names.
- Never move a private key between machines; each operator runs `request` on their own node.
- Never invent configuration keys, values, defaults, recipes, or compatibility claims.
- Keep the chain fingerprint, each chain's genesis id, the accepted plugin bundle digests, and a
  running node's reported identity distinct.
- Never skip the fingerprint check: `join --yes` requires `--expect-fingerprint`.
- Do not mutate a running node, run `approve` or `anchor`, or call privileged runtime APIs
  unless the user explicitly asks. `anchor` spends Cardano funds on public networks.
- Treat custom-plugin metadata as PARTIAL coverage unless Yano reports FULL coverage. Verify
  signed metadata and runtime-manifest binding before trusting a third-party artifact.
- `seal` resolves only the distribution's own catalog and `plugins/`; custom signed component
  catalogs are not supported by `seal` in this release. Report such requests as unsupported.
- If a requested capability is unavailable in this release, report it as unsupported. Do not
  bypass the sealed file by hand-writing runtime configuration unless the user explicitly chooses
  an advanced unsupported workflow.

## Command Patterns

```bash
./yano.sh appchain try
./yano.sh appchain new <app> --network <network> --operators <a,b,c> --chain <id>=<recipe>
./yano.sh appchain new <app> --network <network> --operators <a,b,c> --chain <id>=bindings:<file>
./yano.sh appchain new <app> --chain <id>=<recipe> --answer <id>.<name>=<value>
./yano.sh appchain invite <app>.draft.yaml --serve --host <coordinator-host>
./yano.sh appchain request <app>.draft.yaml --name <operator> --host <public-host>
./yano.sh appchain request '<yano:// link>' --host <public-host>
./yano.sh appchain seal <app>.draft.yaml
./yano.sh appchain inspect <app>.chain
./yano.sh appchain join <app>.chain
./yano.sh appchain start
./yano.sh appchain status <app> [--json]
./yano.sh appchain approve <name>.member
./yano.sh appchain approve --remove <name> <app>
./yano.sh appchain anchor <app>
./yano.sh appchain stop
./yano.sh appchain bindings validate <bindings.yaml> --plugins-directory plugins --context <context.json>
./yano.sh appchain config explain <property>
./yano.sh appchain metadata verify <plugin-jar> --trust-key <key-id=public-key-hex>
```

Read command help (`--help`) from the version-matched executable when an option is uncertain.
