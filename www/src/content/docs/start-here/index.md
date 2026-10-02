---
title: "Your path through Yano X"
description: "Start with a working example. Learn one concept at a time, then bring your own application rules."
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/site/start-here.md"
---
Start with a working example. Learn one concept at a time, then bring your own application rules.

Yano X is the Java 25 extension ecosystem for **Yano app ledgers**: application ledgers that several members run together. Yano handles ordering, member finality, proofs, and optional Cardano anchors. Yano X adds state machines, workflows, connectors, and application tooling as JVM plugins. Yano's tooling calls an app ledger an *app chain*, so commands and settings use `appchain` and `app-chain`.

## 1. See a ledger work

**Time:** about 15 minutes after the download.

Follow the [local showcase quickstart](/start-here/quickstart/). You will start three members on a private devnet, submit data, and compare their state. You need Java 25, Python 3, `curl`, and `jq`. You do not need a public-network wallet or test ADA.

**Ready to continue when:** you can submit an order, verify member agreement, stop the instance, and restart it with the same data.

If the idea is new, read [What is an app ledger?](/start-here/what-is-an-app-ledger/) first. If you only need Cardano data, transaction submission, or devnet testing, begin with [Yano](https://getyano.dev/).

## 2. Understand what you verified

**Time:** about 30 minutes: 15 for the tutorial and 15 for the concepts.

Four results look similar but establish different things:

<!-- illustration: what-did-you-verify -->

- **202 Accepted:** one member queued the message. It is not yet ordered, final, or successful.
- **Final:** a threshold of members re-executed and certified the block. The command itself may still have been a no-op, so read the application's result.
- **Proof:** a record is, or is not, in the state under one root. It proves more only if you trusted that root independently.
- **Anchor:** Cardano records the ledger's height, block hash, and state root. Cardano does not run your application rules.

<!-- /illustration -->

Work through [Registry and proofs](/tutorials/02-registry-and-proofs/), then read [State and proofs](/concepts/state-and-proofs/) for the trust model.

**Ready to continue when:** you can explain the difference between submission, a finalized result, a proof, and an anchor.

## 3. Model your application

**Time:** plan on an hour to choose a recipe and validate a profile.

[Choose a recipe](/recipes/choosing-a-recipe/) for your outcome: shared records, document history, approvals, or another supported workflow. [Configure an application profile](/deployment/configure/) and validate it before running it. [Studio](/studio/) can help you explore and export a blueprint.

**Ready to continue when:** you know which state machine and capabilities your application needs. You do not need to write a plugin if an existing recipe fits.

## 4. Extend or operate when you need to

Most tutorials state their time at the top.

| Your next task | Continue here |
| --- | --- |
| Follow examples in order | [Guided tutorials](/tutorials/) |
| Connect existing machines using YAML | [Declarative bindings: beginner to advanced](/bindings/) |
| Combine existing rules or add custom Java logic | [Plugin framework](/plugins/) |
| Deliver webhooks or other external actions | [Effects](/concepts/effects/) |
| Plan a multi-node deployment | [Deployment guide](/deployment/) |
| Look up an API, artifact, or setting | [Reference shelf](/reference/shelf/) |
| Give a coding agent project context | [AI starter pack](/ai/starter-pack/) |

## Keep versions together

The documentation follows the current checkout. [Release downloads](/start-here/release-downloads/) contain their own exact host identity and plugin manifests; use those when running an older release. Yano X libraries use Maven group `org.yanoproject.x` and packages `org.yanoproject.x.*`. Yano host libraries use `org.yanoproject`. See the [generated version and module table](/reference/modules/) for this checkout.

Yano X is pre-release. Use the local devnet to learn, then review security, deployment, recovery, and domain requirements before planning production use.
