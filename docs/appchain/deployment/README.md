# Deploy Yano X

Start with a local demonstration, rehearse a shared application on one
machine, then set it up for real with the other operators.

| Your goal | Start here | What you get |
|---|---|---|
| See Yano X working | [Local showcase](quickstart.md) | Three nodes, useful data, proofs, and a console |
| Rehearse a shared application | [`./yano.sh appchain try`](configure.md#1-practise-on-one-machine) | Every real setup step, on one machine, with three simulated operators |
| Run an application with other operators | [Set up an application](configure.md) | Each operator runs their own node; everyone checks they have the same chain |
| Add or remove a member, or add another application | [Grow and change it](add-chain.md) | One command per operator, without resetting anything |
| Run your node on a server | [Run your node on a server](operators.md) | A systemd service, open ports, and the cloud route for the showcase |

Yano X is JVM-only and requires Java 25. Yano's tooling calls an app ledger an
*app chain*, so commands and files say `appchain`.

## Who does what

A real app ledger is run by several organizations. Each one is an
**operator**: it runs one member node and holds that node's private key, which
never leaves its machine. One person, the **coordinator**, defines the
application and collects everyone's public member card. The coordinator is
usually also an operator. The first operator listed is the **lead**.

Only public files travel between people: the draft, the member cards, and the
sealed `.chain` file. Send them however you like, or let
[join links](configure.md#easier-hand-out-join-links) carry them. Everyone
checks they have the same chain by comparing a short **chain fingerprint** on
a call.

An application can hold several chains. Each chain keeps its own genesis,
state, and finality history, and all of them run on the same member nodes.

## Nodes, chains, and ports

Each node has one HTTP port and one node-to-node (n2n) port, however many
chains it hosts.

<!-- illustration: topology-ports -->

| How you run it | HTTP | Node-to-node | Change it with |
|---|---|---|---|
| `./yano.sh appchain cluster`, local showcase | `7070` + node index | `13337` + node index | `--http-base`, `--server-base` |
| `./yano.sh appchain try` | first free from `8080` | first free from `13337` | chosen for you and printed |
| One node per operator | `8080` on each host | `13337` on each host | `request --http-port`, `--node-port` |

The cluster launcher moves a busy default range and prints the one it chose.
The showcase passes its ports explicitly, so it stops instead. While the
coordinator hands out join links, `invite --serve` also listens on port `7099`
(`--port` changes it), which the operators must be able to reach.

## Where to start

The supported evaluation posture is a local devnet. Public networks follow the
same steps, but anchoring and settlement there are explicit operations that
spend real test ADA; easier setup does not change
[the release-readiness assessment](../../../adr/045-yano-x-release-readiness.md).

**Next:** [start the local showcase](quickstart.md), or
[rehearse a shared application](configure.md#1-practise-on-one-machine).
