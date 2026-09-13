---
title: "Deploy Yano X"
description: "Start with a local demonstration, configure an application when you are ready, and use the operator workflow when you need remote machines."
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/appchain/deployment/README.md"
---
Start with a local demonstration, configure an application when you are ready,
and use the operator workflow when you need remote machines.

| Your goal | Start here | What you get |
|---|---|---|
| See Yano X working | [Local showcase](/start-here/quickstart/) | Three nodes, useful data, proofs, and a console |
| Choose your own chains | [Configure an application](/deployment/configure/) | Studio or CLI → one application profile → local cluster |
| Grow an existing local application | [Add a chain](/deployment/add-chain/) | Reviewed addition, controlled restart, retained history |
| Run remote VMs | [Operator deployment](/deployment/operators/) | Custom-profile Ansible export or cloud showcase automation |

Yano X is JVM-only and requires Java 25. Chain count and node count are separate:
several independent chains can run on the same member nodes. Each chain keeps
its own genesis, state, and finality history.

The current supported evaluation posture is local devnet. VM tooling is available
for qualification and operator evaluation; easier configuration does not change
the release-readiness assessment.
Public-network anchoring and settlement are explicit operations with separate
credentials and authorization.
