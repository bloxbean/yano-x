---
title: "Using Yano X with AI agents"
description: "Give coding agents the documentation for your exact Yano X version. Common mistakes include bypassing the plugin catalog with raw ServiceLoader, using…"
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/site/ai-overview.md"
---
Give coding agents the documentation for your exact Yano X version. Common
mistakes include bypassing the plugin catalog with raw `ServiceLoader`, using
`Instant.now()` inside `apply()`, and using the obsolete plugin-directory
property. The guides below explain the supported contracts and checks.

So this site publishes everything an agent needs, in formats agents can ingest.

## TL;DR

| Artifact | When to use it |
|---|---|
| **[`/ai/starter-pack/`](/ai/starter-pack/)** | The single highest-leverage file. The extension ladder, determinism rules, the plugin lifecycle, the invariants that look like typos, and an error-to-fix table. Ingest this before generating anything. |
| **[`/llms.txt`](/llms.txt)** | A curated index following [llmstxt.org](https://llmstxt.org/), with the key facts inline. Small and agent-friendly. |
| **[`/llms-full.txt`](/llms-full.txt)** | Every page on this site concatenated as one markdown file — including all nine tutorials and the state-machine references. Ingest for full coverage. |
| **[`/ai/catalog.json`](/ai/catalog.json)** | Machine-readable recipes, capabilities, runtime artifacts, Gradle modules, configuration properties, and versions. Generated from the repository at build time, so it never drifts. |

All four are regenerated on every documentation build from the repository's own
catalogs and pages.

## Per-tool setup

### Claude Code

Save the starter pack next to your project's `CLAUDE.md`, and import it from
there. Appending the import keeps any `CLAUDE.md` you already have:

```bash
curl -fsS -o yano-x-starter-pack.md https://yano-x.io/ai/starter-pack.md
printf '\n@yano-x-starter-pack.md\n' >> CLAUDE.md
```

Claude Code reads `CLAUDE.md` at the start of every session and follows its
`@` imports, so the agent always has Yano X context. Re-run the `curl` line to
refresh the pack; the `@` line stays as it is.

For multi-project setups, reference the hosted version from your global
`~/.claude/CLAUDE.md`:

```markdown
When working in a Yano or Yano X project, follow the rules at
https://yano-x.io/ai/starter-pack/
```

### Cursor

Cursor project rules live in `.cursor/rules/` and use front matter to decide
when a rule applies. The raw starter pack has none: the site serves the page
body, without its front matter, behind a one-line version comment. Add the
front matter as you save it, so the rule applies to every request in this
project:

```bash
mkdir -p .cursor/rules
{
  printf '%s\n' '---' 'description: Yano X architecture rules and invariants' 'alwaysApply: true' '---' ''
  curl -fsS https://yano-x.io/ai/starter-pack.md
} > .cursor/rules/yano-x.mdc
```

### Continue (VS Code / JetBrains)

```json
{
  "contextProviders": [
    {
      "name": "url",
      "params": { "url": "https://yano-x.io/llms-full.txt" }
    }
  ]
}
```

### ChatGPT and Claude.ai on the web

For a one-off conversation, paste this at the start:

```text
I'm working with Yano X, the Java 25 JVM extension ecosystem for Yano app
ledgers (application-specific replicated ledgers that can anchor to Cardano;
Yano's tooling calls them app chains). Read the Yano X AI Starter
Pack at https://yano-x.io/ai/starter-pack and follow its rules strictly. In
particular:
- Dependency direction is strictly yano-x -> yano. Never propose a composite
  Gradle build or a source dependency on a Yano checkout.
- Every optional runtime behavior is a plugin activated through
  PluginProviderRegistry plus a schema-v1 manifest. Never raw ServiceLoader.
- Code inside apply() must be deterministic: no wall clock, no randomness, no
  ambient iteration order, no I/O. External work is an emitted effect.
- Packages are org.yanoproject.x.* even though artifacts are
  yano-x-*. The plugin directory property is yano.plugins.directory.
- Yano X is JVM-only. Never add GraalVM or native-image tasks.
```

For long-lived projects, attach `https://yano-x.io/llms-full.txt` to your
project files or custom GPT.

### Any agent with tool access

Point it at the catalog and let it read structured data instead of guessing:

```bash
curl -s https://yano-x.io/ai/catalog.json | jq '.recipes[].id'
curl -s https://yano-x.io/ai/catalog.json | jq '.capabilities[] | select(.category=="state")'
curl -s https://yano-x.io/ai/catalog.json | jq '.configuration[] | select(.scope=="CONSENSUS_SHARED")'
```

## The in-repo agent skill

Yano X also ships a first-party agent skill. The JVM distribution carries it in
`skills/configure-yano-appchain/`, and its source is in the repository:

```text
tooling/devtools/src/main/resources/appchain-dx/v1alpha1/skills/
  configure-yano-appchain/
    SKILL.md
    agents/openai.yaml
```

`configure-yano-appchain` covers creating, explaining, sealing, joining, and
diagnosing app ledger **applications** run by several operators, using the
version-matched setup CLI, recipe and capability catalogs, and binding tools.

**Where the skill and the starter pack overlap, the skill wins** — it is
version-matched to the binary you are running, and the starter pack quotes it
rather than competing with it. Its workflow:

1. Run `./yano.sh appchain recipes` and `./yano.sh appchain capabilities` before
   choosing features, and suggest `appchain try` to rehearse on one machine.
2. The coordinator runs `appchain new` (recipe answers with `--answer
   <chain>.<name>=<value>`) and edits only the setup draft
   (`<app>.draft.yaml`) before sealing.
3. Each operator runs `appchain request` on their own machine; private keys
   never travel, only the public member card. With `appchain invite --serve`,
   the coordinator can hand out join links that carry the draft, the cards and
   the sealed file instead.
4. The coordinator runs `appchain seal`. Never hand-edit the sealed `.chain`
   file or the node configuration `join` writes.
5. Each operator compares the chain fingerprint and key ids, then runs
   `appchain join` and `appchain start`; `appchain status` checks that every
   member is online and agrees, and prints the Cardano view and plugin catalog
   fingerprint the members compare.
6. Summarize chains and recipes, operators and threshold, the fingerprint,
   unresolved operator inputs, checks run, and warnings.

## Safety rules for agents

These are non-negotiable, and they apply to human contributors too:

- **Never** request, print, copy, infer, or commit secret values. Refer only to
  documented environment-variable or secret-provider names.
- **Never** invent configuration keys, values, defaults, recipes, or
  compatibility claims. If a capability is unavailable in the release, report it
  as unsupported rather than working around the sealed file.
- Keep the chain fingerprint, each chain's genesis id, the accepted plugin
  bundle digests, and a running node's reported identity distinct.
- Never skip the fingerprint check: `join --yes` requires
  `--expect-fingerprint`.
- Do not mutate a running node or call privileged runtime APIs unless the user
  explicitly asks.
- Treat custom-plugin metadata as `PARTIAL` coverage unless Yano reports `FULL`.
  Verify signed metadata and its runtime-manifest binding before trusting a
  third-party artifact.

## Why the catalog is generated

Every number, recipe id, capability id, artifact id, module path, and
configuration property on this site is read at build time from files the Gradle
build already maintains and release-gates:

| Site output | Source of truth |
|---|---|
| Versions | `gradle.properties` |
| Recipes | `appchain-recipe-catalog.json` |
| Capabilities and runtime artifacts | `appchain-capability-catalog.json` |
| Configuration properties | `appchain-first-party-metadata.json` |
| Distributions | `appchain-release-capability-index.json` |
| Gradle modules | `config/artifacts-v1.json` |

An agent reading `/ai/catalog.json` is reading the repository, one build step
removed. That is the whole point: hallucinated capability names are the most
common failure mode, and this makes the real list cheap to fetch.
