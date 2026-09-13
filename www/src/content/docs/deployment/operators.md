---
title: "Deploy and operate remote VMs"
description: "The packaged tools/yano-deploy/bin/yano-x-deploy automates a qualified showcase profile on existing Linux VMs, Contabo, Hetzner Cloud, DigitalOcean, or…"
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/appchain/deployment/operators.md"
---
The packaged `tools/yano-deploy/bin/yano-x-deploy` automates a qualified showcase
profile on existing Linux VMs, Contabo, Hetzner Cloud, DigitalOcean, or mixed
placement. It generates OpenTofu and Ansible inputs, configures systemd and
storage, and checks readiness before opening ingress.

For a custom Studio application, export the same validated project to Ansible as
shown below. The remote showcase manifest remains a separate contract for cloud
provisioning and its curated anchored-settlement profile. Do not feed
`appchain.yaml` directly to `yano-x-deploy`.

## Automated showcase route

Use the exact JVM release and matching showcase ZIP. From the extracted JVM
release:

```bash
tools/yano-deploy/bin/yano-x-deploy init ./vm-cluster --nodes 3
# Edit vm-cluster/deployment.yaml: actual hosts, placement, network, and secret references.
# Complete the profile's settlement deployment record and bootstrap prerequisites.
tools/yano-deploy/bin/yano-x-deploy artifact import ./vm-cluster --file /path/to/yano-showcase.zip
tools/yano-deploy/bin/yano-x-deploy validate ./vm-cluster
tools/yano-deploy/bin/yano-x-deploy doctor ./vm-cluster
tools/yano-deploy/bin/yano-x-deploy plan ./vm-cluster
```

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

## Custom application route

In Studio, select host deployment, enter one public member key and hostname per
node in matching order, and download the application intent. Render and inspect:

```bash
./yano.sh appchain render ./application
./yano.sh appchain doctor ./application --distribution "$PWD"
```

Export an automated existing-VM deployment:

```bash
./yano.sh appchain gitops ./application --target ansible --output ./application-vms
cd ./application-vms
# Copy operator-vars.example.yaml outside the export and fill in exact archive,
# checksum, archive root, Java 25 home, SSH user, and private node environment directory.
ansible-playbook -i inventory.yaml deploy.yaml -e @/path/to/operator-vars.yaml --syntax-check
ansible-playbook -i inventory.yaml deploy.yaml -e @/path/to/operator-vars.yaml --check
ansible-playbook -i inventory.yaml deploy.yaml -e @/path/to/operator-vars.yaml
```

The export contains the same per-chain consensus configuration and member
placement as Studio/CLI. It verifies its generated-file lock, checks the supplied
archive checksum, rejects unmarked retained stores and different installed
revisions, copies the pinned runtime and private environment files, installs a
systemd service, and waits for HTTP readiness. No anchor or settlement is required
unless your selected recipes need one. Private keys are never embedded in the
export; each `nodeN.env` must match the corresponding public member key.

Prerequisites: reachable systemd Linux VMs, SSH/sudo, Java 25 at the configured
path, and appropriate existing firewall rules. The exporter does not provision
machines, configure public ingress, or bypass your network policy. Local Ansible
syntax checks and generated configuration tests are not live VM certification.

Services run as `yano`. Each node has sibling `chainstate`, `appchain-chainstate`,
and `appchain-indexers` paths under `/var/lib/yano/<project>/nodeN`. Inspect
`systemctl status yano-<project>` and `journalctl -u yano-<project>`. Complete
multi-chain drift, command finality/proof, restart/catch-up, and backup/restore
checks before handing the deployment to users.

Repeated apply of the same application and artifact reconciles that deployment.
A changed application or artifact is rejected; coordinated remote revision
upgrades remain a separate milestone. The local `appchain apply` command never
pretends to upgrade remote VMs. Expert operators can also use the generated
`scripts/start-node <index>` directly with their own service management.

## What to monitor

Watch per-chain finality progress, peer connectivity, root/identity agreement,
L1 freshness, disk capacity, process health, effect backlogs, and anchor status.
An empty infrastructure plan does not mean configuration or service changes are
absent. Treat a fixed proposer as an availability dependency even if enough
other validators remain online.
