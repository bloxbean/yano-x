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
its own genesis, state, and finality history. Yano's tooling calls an app
ledger an *app chain*, so commands and files say `appchain`.

## Nodes, chains, and ports

Each node has one HTTP port and one node-to-node (n2n) port, however many
chains it hosts. The numbers depend on how you run it: the cluster launcher and
the showcase start at `7070`, a generated project at `8080`.

<!-- illustration: topology-ports -->

| How you run it | HTTP | Node-to-node | Change it with |
|---|---|---|---|
| `./yano.sh appchain cluster`, local showcase | `7070` + node index | `13337` + node index | `--http-base`, `--server-base` |
| Generated project, one machine | `8080` + node index | `13337` + node index | `init --http-port-base`, `--server-port-base` |
| Generated project, one host per member | `8080` on each host | `13337` on each host | the hosts in `appchain.yaml` |

The cluster launcher moves a busy default range and prints the one it chose.
The showcase passes its ports explicitly, so it stops instead. A launcher
cluster and a generated project both default to node-to-node ports from
`13337`; stop one before starting the other, or choose other bases.

## Where to start

The current supported evaluation posture is local devnet. VM tooling is available
for qualification and operator evaluation; easier configuration does not change
the release-readiness assessment.
Public-network anchoring and settlement are explicit operations with separate
credentials and authorization.

**Next:** [start the local showcase](/start-here/quickstart/).
