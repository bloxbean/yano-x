# Yano X deployer

`yano-x-deploy` compiles one reviewed cluster manifest into OpenTofu and
Ansible inputs for Contabo, Hetzner Cloud, DigitalOcean, existing Linux VMs,
or a mixture of them.

```bash
yano-x-deploy init ./cluster
yano-x-deploy artifact import ./cluster --file ./yano-showcase-<version>.zip
yano-x-deploy validate ./cluster
yano-x-deploy render ./cluster
yano-x-deploy plan ./cluster
yano-x-deploy apply ./cluster --confirm <cluster-id>
yano-x-deploy bootstrap-anchors ./cluster --confirm-network preprod
yano-x-deploy gateway ./cluster
yano-x-deploy monitoring ./cluster
yano-x-deploy status ./cluster
```

`render` is offline. `plan` and `apply` require OpenTofu for cloud nodes;
`apply` also requires Ansible. Contabo credentials use the provider-native
`CNTB_OAUTH2_*` environment variables. Existing VMs are never created, reinstalled, or
destroyed by the tool. Credentials stay in provider environment variables and
member signing seeds are read from controller-side files referenced by the
manifest.

`spec.consensus.sequencer` is mandatory. Use `mode: fixed` with an explicit
validator `proposerNode` for the stable geographically distributed posture, or
select experimental `mode: rotating` with an optional `windowSlots` value.
The renderer records the choice in the deployment lock and installs identical
sequencer properties on every validator.

`bootstrap-anchors` is a separate, explicitly authorized public-network
operation. It fails fast without submitting a transaction unless every member
is L1-current and every hosted chain has its full peer mesh. By default it
reconciles all catalog chains sequentially, skips identities already confirmed
on L1, resumes a pending bootstrap, and waits a bounded time for each submitted
transaction. Use `--chain <chain-id>` only for targeted recovery.

One default `application.anchoring.seedFile` supplies the same fee-paying
anchor account to every chain. Optional `chainSeedFiles` entries replace it for
named chains without putting a seed value in inventory, locks, logs, or command
arguments:

```yaml
application:
  anchoring:
    seedFile: ../private-anchor/anchor.seed
    chainSeedFiles:
      orders-chain: ../private-anchor/orders.seed
```

The distribution also packages the provider-neutral, two-phase existing-VM
bootstrap playbook under `deployment/ansible`. It creates the administrative
account and key first, then requires a verified key-based admin connection
before it can disable password and direct-root SSH login.

See `deployment/README.md` and ADR-039 for the complete contract and safety
model.
