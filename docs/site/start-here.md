# Your path through Yano X

Start with a working example. Learn one concept at a time, then bring your own application rules.

Yano X is the Java 25 extension ecosystem for **Yano app chains**: application ledgers that several members run together. Yano handles ordering, member finality, proofs, and optional Cardano anchors. Yano X adds state machines, workflows, connectors, and application tooling as JVM plugins.

## 1. See a chain work

Follow the [local showcase quickstart](/start-here/quickstart/). You will start three members on a private devnet, submit data, and compare their state. You need Java 25, Python 3, `curl`, and `jq`. You do not need a public-network wallet or test ADA.

**Ready to continue when:** you can submit an order, verify member agreement, stop the instance, and restart it with the same data.

If the idea is new, read [What is an app chain?](/start-here/what-is-an-app-chain/) first. If you only need Cardano data, transaction submission, or devnet testing, begin with [Yano](https://getyano.dev/).

## 2. Understand what you verified

A successful HTTP submission means the message was admitted; it does not yet mean the block is final or the application transition succeeded. Members certify app blocks under a configured threshold. A proof establishes a specific claim against a committed root. An optional Cardano anchor adds a separate L1 record; it does not make Cardano validators execute your application rules.

Work through [Registry and proofs](/tutorials/02-registry-and-proofs/), then consult [State and proofs](/concepts/state-and-proofs/) for the trust model.

**Ready to continue when:** you can explain the difference between submission, a finalized result, a proof, and an anchor.

## 3. Model your application

[Choose a recipe](/recipes/choosing-a-recipe/) for your outcome: shared records, document history, approvals, or another supported workflow. [Configure an application profile](/deployment/configure/) and validate it before running it. [Studio](/studio/) can help you explore and export a blueprint.

**Ready to continue when:** you know which state machine and capabilities your application needs. You do not need to write a plugin if an existing recipe fits.

## 4. Extend or operate when you need to

| Your next task | Continue here |
| --- | --- |
| Follow examples in order | [Guided tutorials](/tutorials/) |
| Combine existing rules or add custom Java logic | [Plugin framework](/plugins/) |
| Deliver webhooks or other external actions | [Effects](/concepts/effects/) |
| Plan a multi-node deployment | [Deployment guide](/deployment/) |
| Look up an API, artifact, or setting | [Reference shelf](/reference/shelf/) |
| Give a coding agent project context | [AI starter pack](/ai/starter-pack/) |

## Keep versions together

The documentation follows the current checkout. [Release downloads](/start-here/release-downloads/) contain their own exact host identity and plugin manifests; use those when running an older release. Yano X libraries use Maven group `org.yanoproject.x` and packages `org.yanoproject.x.*`. Yano host libraries use `org.yanoproject`. See the [generated version and module table](/reference/modules/) for this checkout.

Yano X is pre-release. Use the local devnet to learn, then review security, deployment, recovery, and domain requirements before planning production use.
