---
title: "Run your node on a server"
description: "Your node runs as a systemd service, and status shows every member online."
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/appchain/deployment/operators.md"
---
- **Goal:** run your member node on your own Linux server, as a service that
  survives reboots, reachable by the other members.
- **Before you start:** a Linux VM with systemd, `sudo`, Java 25, and the same
  extracted Yano X JVM release the coordinator used. The cloud showcase route
  at the end also needs OpenTofu and Ansible.
- **Outcome:** your node runs as a systemd service, and `status` shows every
  member online.

Each operator deploys their own node. There is no cluster-wide export: the
coordinator never touches your server, and your private key never leaves it.
The steps are the same as in [Set up an application](/deployment/configure/); this page
covers what changes on a server.

## 1. Make your card with your public address

On your server, from the extracted Yano X directory:

```bash
./yano.sh appchain request supply.draft.yaml --host node-a.example.com
```

Use the host name or IP the **other members** reach your server at, not
`127.0.0.1`. With a public host, the node listens on all interfaces. The
default ports are node-to-node `13337` and HTTP `8080`; choose others with
`--node-port` and `--http-port`. If a NAT or proxy forwards a different port
to your server, keep the advertised one in the card and later run
`join --listen <port>` with the local one.

> **✓ You should see** `✓ Wrote ./alice.member  (key id …, reachable at
> node-a.example.com:13337)`.

If the coordinator sent you a join link instead of the draft, give the link
in place of the file, with the same options:

```bash
./yano.sh appchain request 'yano://coord.example.com:7099/…#…' --host node-a.example.com
```

It sends your card, waits for the seal, saves `supply.chain`, and continues
into `join` (step 3). On a server without a terminal session to keep open,
add `--no-wait` and run the same command again later to collect the sealed
file. See [join links](/deployment/configure/#easier-hand-out-join-links).

## 2. Open the ports

| Port | Who needs it | Why |
|---|---|---|
| Node-to-node, `13337` | The other members' servers | Blocks, votes, and catch-up |
| HTTP, `8080` | The other operators, and your own applications | `status` reads each member's chain status from it; on a devnet the other nodes download the devnet genesis from the lead's |
| Join links, `7099` | The operators, only while the coordinator runs `invite --serve` | Hands out the draft, collects the cards, and hands out the sealed file. Only the coordinator's machine needs it, and only during setup |

Open them only to the machines that need them. Write calls on the HTTP API
need your API key (`yano-node/secrets/api.key`); put TLS and a reverse proxy
in front before any client outside your network uses it. On a devnet the
lead's HTTP port also serves the devnet's unauthenticated time and rollback
controls, so keep a shared devnet on a trusted network.

## 3. Join and install the service

When the coordinator sends `supply.chain` and you have compared the
fingerprint on the call:

```bash
./yano.sh appchain join supply.chain
```

On Linux, `join` also writes a systemd unit into your node directory and
prints the command to install it:

> **✓ You should see**
>
> ```text
> ✓ Joined supply (94c9-2bad-…) in /home/alice/yano-x-jvm-<version>/yano-node
>   To run it as a service: sudo cp …/yano-node/yano-appchain-alice.service /etc/systemd/system/ && sudo systemctl enable --now yano-appchain-alice.service
> ```

Run that command. The tool never installs the unit itself. The service runs
as the user who ran `join`, and restarts the node if it fails.

Once the unit is installed and enabled, `./yano.sh appchain start` and
`stop`, and the restarts that `approve` and `anchor` make, go through
`systemctl`, so one node never has two managers. You can also use systemd
directly:

```bash
systemctl status yano-appchain-alice.service
journalctl -u yano-appchain-alice.service
```

On a devnet the lead starts first. On a public network the node syncs Cardano
before its chains make progress; the `Cardano:` line of `status` shows
`syncing: about N hours behind` until it reports `up to date`, and
`yano-node/logs/node.log` has the details.

## Node-only settings

`config/consensus.yaml` and `config/node.yaml` are rewritten by `join`. Put
anything that belongs to your node alone, such as connector endpoints for an
effect executor, in `config/local.yaml` in the node directory. `join` creates
it, never rewrites it, and its values win over `config/node.yaml`. `start`
refuses the file if it sets any sealed consensus setting, because every member
must run the sealed values. Keep credentials in the environment or a secret
provider rather than in the file. See
[optional connectors](/deployment/connectors/).

## 4. Check the application

```bash
./yano.sh appchain status supply
```

> **✓ You should see** `[Live]`, every member `online`, and `members agree`
> for every chain. An `offline` member is named, with the address `status`
> tried. Compare the `Plugins:` catalog fingerprint (and, on a devnet, the
> genesis in `Cardano:`) with the other operators: they must match.

## What to back up and monitor

- **Back up** the whole node directory: `secrets/` holds your member key, and
  `data/` holds the authoritative stores `chainstate` and
  `appchain-chainstate`. Do not copy an open RocksDB directory as a backup;
  stop the node first. With fixed sequencing the lead's key cannot be
  replaced.
- **Monitor** `status` (members online, heights, agreement, anchoring,
  Cardano sync), the node log, disk space, and process health. If fewer members than the
  threshold are online, the chains stop finalizing until enough return; that
  is the safety the threshold buys. With fixed sequencing, if the lead is
  offline the others still finalize through a certified view change, but each
  block first waits for a round timeout, so watch the lead.

## Showcase on cloud VMs

The packaged `tools/yano-deploy/bin/yano-x-deploy` deploys the curated
showcase profile, not your own application, to existing Linux VMs, Contabo,
Hetzner Cloud, DigitalOcean, or mixed placement. It generates OpenTofu and
Ansible inputs, configures systemd and storage, and checks readiness before
opening ingress. From the extracted JVM release:

```bash
tools/yano-deploy/bin/yano-x-deploy init ./vm-cluster --nodes 3
# Edit vm-cluster/deployment.yaml: actual hosts, placement, network, and secret references.
# Complete the profile's settlement deployment record and bootstrap prerequisites.
tools/yano-deploy/bin/yano-x-deploy artifact import ./vm-cluster \
  --file /path/to/yano-x-jvm-<version>.zip
tools/yano-deploy/bin/yano-x-deploy validate ./vm-cluster
tools/yano-deploy/bin/yano-x-deploy doctor ./vm-cluster
tools/yano-deploy/bin/yano-x-deploy plan ./vm-cluster
```

> **✓ You should see** `Imported showcase archive sha256:...` after the import.
> The import refuses an archive without `yano.sh`, `yano.jar`, and the
> `examples/showcase/` profile.

Read the [full operator runbook](https://github.com/bloxbean/yano-x/blob/main/deployment/README.md) for SSH bootstrap,
provider credentials, OS and capacity checks, network policy, and manifest fields.
The current profile requires Preprod anchoring and settlement configuration.
Public addresses in generated examples are documentation placeholders.

Apply only to the intended reviewed cluster:

```bash
tools/yano-deploy/bin/yano-x-deploy apply ./vm-cluster --confirm <cluster-id>
tools/yano-deploy/bin/yano-x-deploy status ./vm-cluster
tools/yano-deploy/bin/yano-x-deploy wait ./vm-cluster --for l1-tip --timeout-seconds 3600
```

Apply can cause a full-cluster interruption for mesh recovery with the pinned
host. It is not a zero-downtime rolling upgrade. Anchoring and settlement
bootstrap are separate operations; authorize public-network transactions explicitly.

**Next:** [from demonstration to production](/deployment/production/).
