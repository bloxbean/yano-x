---
title: Using Yano X with AI agents
description: How to point Claude Code, Cursor, Continue, ChatGPT, or any coding agent at Yano X — the starter pack, llms.txt, the machine-readable catalog, and the in-repo agent skill.
sidebar:
  order: 1
---

Yano X is new enough that no model has trained on it. An agent asked to "write a
Yano plugin" will confidently invent a `ServiceLoader` entry point, put
`Instant.now()` inside `apply()`, and name the plugin directory property
`yaci.plugins.directory`. All three are wrong, and none of them fail loudly.

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

Drop a `CLAUDE.md` into the root of your project:

```bash
curl -o CLAUDE.md https://yanox.dev/ai/starter-pack.md
```

Claude Code reads `CLAUDE.md` at the start of every session, so the agent always
has Yano X context.

For multi-project setups, reference the hosted version from your global
`~/.claude/CLAUDE.md`:

```markdown
When working in a Yano or Yano X project, follow the rules at
https://yanox.dev/ai/starter-pack/
```

### Cursor

```bash
mkdir -p .cursor/rules
curl -o .cursor/rules/yano-x.mdc https://yanox.dev/ai/starter-pack.md
```

Cursor applies rules in `.cursor/rules/` automatically when working in the
project.

### Continue (VS Code / JetBrains)

```json
{
  "contextProviders": [
    {
      "name": "url",
      "params": { "url": "https://yanox.dev/llms-full.txt" }
    }
  ]
}
```

### ChatGPT and Claude.ai on the web

For a one-off conversation, paste this at the start:

```text
I'm working with Yano X, the Java 25 JVM extension ecosystem for Yano app chains
(application-specific replicated ledgers on Cardano). Read the Yano X AI Starter
Pack at https://yanox.dev/ai/starter-pack and follow its rules strictly. In
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

For long-lived projects, attach `https://yanox.dev/llms-full.txt` to your
project files or custom GPT.

### Any agent with tool access

Point it at the catalog and let it read structured data instead of guessing:

```bash
curl -s https://yanox.dev/ai/catalog.json | jq '.recipes[].id'
curl -s https://yanox.dev/ai/catalog.json | jq '.capabilities[] | select(.category=="state")'
curl -s https://yanox.dev/ai/catalog.json | jq '.configuration[] | select(.scope=="CONSENSUS_SHARED")'
```

## The in-repo agent skill

Yano X also ships a first-party agent skill in the distribution itself:

```text
tooling/devtools/src/main/resources/appchain-dx/v1alpha1/skills/
  configure-yano-appchain/
    SKILL.md
    agents/openai.yaml
```

`configure-yano-appchain` covers creating, explaining, updating, validating, and
diagnosing app-chain **projects** using the version-matched blueprint, capability
catalog, and CLI.

**Where the skill and the starter pack overlap, the skill wins** — it is
version-matched to the binary you are running, and the starter pack quotes it
rather than competing with it. Its workflow:

1. Run `./yano.sh appchain recipes` and `./yano.sh appchain capabilities` before
   choosing features.
2. Create or edit only `appchain.yaml`. Never hand-edit generated runtime files.
3. Run `appchain render`, then `appchain config validate --mode project`.
4. Run `appchain doctor` before startup or delivery.
5. Use `appchain diff` before applying a blueprint change, and `appchain drift`
   against running nodes.
6. Summarize selected capabilities, generated files, unresolved operator inputs,
   validation coverage, and warnings.

## Safety rules for agents

These are non-negotiable, and they apply to human contributors too:

- **Never** request, print, copy, infer, or commit secret values. Refer only to
  documented environment-variable or secret-provider names.
- **Never** invent configuration keys, values, defaults, recipes, or
  compatibility claims. If a capability is unavailable in the release, report it
  as unsupported rather than working around the blueprint.
- Keep blueprint, resolved-config, release, plugin-catalog, and consensus
  identities distinct.
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
