# Yano X multi-machine deployment

This directory documents the source contract compiled by the
`yano-x-deploy` tool. One manifest can provision nodes on Contabo, Hetzner
Cloud, and DigitalOcean, adopt existing Linux VMs, or combine all four.
Provider placement never changes the Yano consensus configuration.

## Fast path

Build the exact showcase ZIP using the repository build procedure, then:

```bash
tools/yano-deploy/bin/yano-x-deploy init ./preprod-cluster
# edit deployment.yaml and create the referenced secret files
tools/yano-deploy/bin/yano-x-deploy artifact import ./preprod-cluster \
  --file ./yano-showcase-<version>.zip
tools/yano-deploy/bin/yano-x-deploy validate ./preprod-cluster
tools/yano-deploy/bin/yano-x-deploy doctor ./preprod-cluster
tools/yano-deploy/bin/yano-x-deploy plan ./preprod-cluster
tools/yano-deploy/bin/yano-x-deploy apply ./preprod-cluster \
  --confirm <cluster-id-from-deployment.yaml>
tools/yano-deploy/bin/yano-x-deploy bootstrap-anchors ./preprod-cluster \
  --confirm-network preprod
tools/yano-deploy/bin/yano-x-deploy monitoring ./preprod-cluster
tools/yano-deploy/bin/yano-x-deploy status ./preprod-cluster
tools/yano-deploy/bin/yano-x-deploy wait ./preprod-cluster \
  --for l1-tip --timeout-seconds 3600
```

`doctor` validates the strict JSON Schema, artifact/import lock, generated-file
lock, required local executables, SSH/sudo reachability, operating system,
architecture, memory, disk capacity, and clock synchronization. Cloud hosts
that do not exist yet receive local checks during `doctor` and the full host
preflight immediately after provisioning.

`apply` is non-interactive after the exact cluster ID is supplied. It renders
immutable inputs, initializes the configured remote OpenTofu backend, rejects
any delete/replace action, applies cloud resources, resolves public addresses,
and runs the generated Ansible playbooks. It closes gateways before mutation,
preflights every host, deploys a content-addressed immutable release, rebuilds
the mesh, verifies node/observer health and cross-node consensus identities,
and only then starts and opens HTTPS ingress. Each successful phase is written
to `deployment.journal.json`, keyed to the exact render lock. Re-running apply
revalidates live state and safely repeats idempotent phases. For an
existing-VM-only cluster it skips OpenTofu and starts directly with host
preflight and configuration.

## Provider credentials

Do not put credentials in `deployment.yaml`, generated files, or command-line
arguments.

- Contabo: set `CNTB_OAUTH2_CLIENT_ID`, `CNTB_OAUTH2_CLIENT_SECRET`,
  `CNTB_OAUTH2_USER`, and `CNTB_OAUTH2_PASS`.
- Hetzner Cloud: set `HCLOUD_TOKEN`.
- DigitalOcean: set `DIGITALOCEAN_TOKEN`.
- Existing VMs: use the normal SSH agent or an Ansible-supported external
  credential source.

The exact provider versions belong in the manifest and the generated
`.terraform.lock.hcl` is retained with the OpenTofu working directory. Region,
image, product, and size values are provider inputs, not Yano enums; verify
them against the authenticated account before applying.

## Existing VM contract

An existing host must have a stable public address, SSH access from the
controller, a supported systemd Linux distribution, enough storage/RAM for the
selected profile, and permission for the automation account to use `sudo`.
The deployer configures packages, firewall, runtime user, directories,
artifact, configuration, and systemd. It never creates, reinstalls, snapshots,
or destroys an existing VM.

### Bootstrap a new existing VM

`ansible/bootstrap-existing-vms.yml` performs the one-time administrative
bootstrap for systemd-based Debian and Ubuntu hosts. It creates a locked
`admin` account, installs one controller-side SSH public key, grants validated
passwordless sudo, and can disable password and direct-root SSH login.

The hardening flag defaults to `false` deliberately. Run the playbook first
through the provider's temporary root access:

```bash
cp ansible/bootstrap-inventory.example.yml ./bootstrap-inventory.yml
# Replace every documentation address with the five real public addresses.
ansible-playbook -i ./bootstrap-inventory.yml \
  ansible/bootstrap-existing-vms.yml \
  -u root --ask-pass \
  -e yano_admin_public_key_file=/absolute/path/to/yano_preprod_admin.pub
```

Keep the original root session open. Verify every host from a separate
terminal before changing SSH authentication:

```bash
ansible yano_bootstrap -i ./bootstrap-inventory.yml \
  -u admin --private-key /absolute/path/to/yano_preprod_admin \
  -b -m ansible.builtin.command -a 'id -u'
```

Every host must return `0`. Only then run the second phase through that same
verified admin account:

```bash
ansible-playbook -i ./bootstrap-inventory.yml \
  ansible/bootstrap-existing-vms.yml \
  -u admin --private-key /absolute/path/to/yano_preprod_admin \
  -e yano_admin_public_key_file=/absolute/path/to/yano_preprod_admin.pub \
  -e yano_harden_ssh=true
```

The second phase fails closed if the Ansible connection is not already using
the configured admin account. Re-run the verification after it reloads SSH.
Do not store provider root passwords or private keys in inventory.

All nodes use three separate retained stores:

- `/var/lib/yano/chainstate`
- `/var/lib/yano/appchain-chainstate`
- `/var/lib/yano/appchain-indexers`

## Runtime profile

Consensus sequencing is explicit in `deployment.yaml`. Fixed sequencing is the
stable initial setting for a geographically distributed cluster; the named
node must be a validator, and the renderer resolves it to the same member
public key on every host:

```yaml
consensus:
  threshold: 4
  sequencer:
    mode: fixed
    proposerNode: node-0
```

The fixed proposer is an availability role: app-chain progress pauses while it
is unavailable, although the other validators still independently verify and
co-sign its proposals. Rotating sequencing remains available only as an
explicit experimental choice:

```yaml
consensus:
  threshold: 4
  sequencer:
    mode: rotating
    windowSlots: 60
```

Changing this policy for a retained chain requires one reviewed manifest and a
coordinated restart of all members. It does not regenerate member identities,
genesis IDs, or retained app-chain stores.

`spec.l1.network` selects the Cardano network. `spec.l1.profile` optionally
selects the exact comma-separated Yano launch profile and must include that
network. When omitted it defaults to the network alone, so `network: preprod`
starts `start:preprod` with the trusted upstream configured by Yano's bundled
Preprod profile. The deployment separately enables the shared node server on
the declared P2P port because validator app-chain traffic requires it; this
does not enable the `relay` upstream-selection profile. Add behavior or
validation profiles only as an explicit, lock-pinned operator choice. The
renderer derives a bounded per-peer connection allowance from the active chain
count because dedicated transport uses one connection per chain; the default
node limit of five is insufficient for the 13-chain showcase.

The Yano build currently pinned by this pre-release deployment can retain a
stale dedicated-client lifecycle after an already-connected peer restarts.
After the serial configuration phase, apply therefore performs a temporary
compatibility step: it cleanly stops every member, starts all members in
parallel, and fails unless every hosted chain reports every other validator as
connected. This causes a bounded full-cluster interruption during apply and is
removed when the pinned Yano artifact includes reconnect-safe dedicated peer
lifecycle behavior. It never resets or replaces any retained store.

Artifact import verifies the showcase archive shape, both embedded
distribution identities and their exact Yano version match, both CycloneDX
SBOMs, the plugin-pack manifest and every bundle checksum, its 13-chain
catalog, and the whole-file SHA-256. `artifact.lock.json` is mandatory after
import; render and every lifecycle command reject any mismatch.
Render compiles authenticated-map genesis from the exact archive and selected
member public keys. The deployed profile activates all 13 demonstration chains,
replacing the packaged devnet-only settlement identity with the operator's
preprod deployment record and parameterized validators. SCRIPT anchoring is
enabled for every chain. Kafka, S3, IPFS, evidence, generic Cardano-effect, and
ZK bundles remain outside the active profile.

Cardano History observations are selected declaratively. Omitting
`cardanoHistory` retains the low-cost protocol-parameters-only default. Every
explicit list must include protocol parameters; stake and governance may be
selected independently or together:

```yaml
application:
  profile: distributed-showcase-preprod-anchored-settlement-v1
  cardanoHistory:
    l1Observations:
      - l1-epoch-params-v1
      - l1-epoch-stake-v1
      - l1-epoch-governance-v1
    genesisId: <64-lower-case-hex>
    sourceSnapshotRetentionEpochs: 400
```

The renderer maps the four valid combinations to the canonical
`params-only-v1`, `params-stake-v1`, `params-governance-v1`, or `full-v1`
presets and records the selection in `deployment.lock.json`. Stake and DRep
distributions use the canonical 25,000-entry chunks and Cardano History's
bounded 6 MiB message/8 MiB block limits. Selecting either large dataset also
enables its required authenticated-snapshot series in the node-local
`/var/lib/yano/appchain-snapshot-archives` store. Source snapshot retention
accepts 2..1000 epochs; size it for the full L1 replay window that observers
must traverse. Changing observations or `genesisId` changes consensus-selected
configuration and therefore requires a reviewed app-chain reset. A full L1
history rebuild additionally requires the `all` reset scope below.

## Reset retained state

Every render includes `ansible/reset.yml`, wrapped by the normal CLI. It is reusable for existing-host or
provisioned clusters because all targets come from that render's resolved
inventory. The playbook refuses to mutate anything unless its immutable
cluster ID and an exact scope are supplied. It deletes only the selected paths
below `/var/lib/yano`; it preserves binaries, configuration, credentials,
member keys, and infrastructure.

Reset only app-chain authoritative state, derived indexes/effects, and local
snapshot archives:

```bash
tools/yano-deploy/bin/yano-x-deploy reset ./preprod-cluster \
  --scope appchain --confirm <cluster-id>
```

Reset those stores plus L1 `chainstate` so Cardano resynchronizes from genesis:

```bash
tools/yano-deploy/bin/yano-x-deploy reset ./preprod-cluster \
  --scope all --confirm <cluster-id>
```

Nodes remain stopped by default, which is the safe choice when applying a new
artifact or chain identity immediately afterward. Add
`--start` only when the installed configuration is already the intended
configuration; the playbook then waits for readiness and verifies the complete
cross-node app-chain mesh.

The exact application profile is
`distributed-showcase-preprod-anchored-settlement-v1`. Render verifies that the
owner-only settlement seed matches the public deployment record and federation,
then installs that seed only on the declared effect owner. The anchor seed is
used only for the declared anchor leader. By default that one account pays for
all chain anchors. `anchoring.chainSeedFiles` may map selected catalog chain IDs
to separate controller-side seed files; every chain not listed continues to use
`anchoring.seedFile`. Neither seed values nor their contents enter the shared
YAML, deployment lock, logs, command arguments, or OpenTofu state.

SCRIPT identity bootstrap is deliberately separate from `apply`. Run
`bootstrap-anchors` after funding the configured account or accounts. The
generated `bootstrap-anchors.yml` checks that the anchor leader and at least the
configured consensus threshold are at the L1 tip, and that the full app-chain
mesh is connected, before spending. A failed preflight aborts the entire
bootstrap playbook; it cannot continue on the leader.
The referenced `spec.access.api.apiKeyFile` supplies the unscoped full key for
privileged bootstrap calls. Merely configuring this key does not enable broad
authentication for public read and submit endpoints.
After confirming the 13-chain mesh is complete, it bootstraps all uninitialized chains in catalog
order. It does not wait for an hours-long initial sync: readiness failure
submits nothing and the same command can be rerun later. Once a transaction is
submitted it waits a bounded time for L1 confirmation before moving to the
next chain, avoiding shared-wallet input races. Already bootstrapped and
currently pending chains are resumed idempotently. `--chain <chain-id>` narrows
the operation for recovery; the default is all chains.

API exposure is `disabled`, `allowlist`, or `public-https`. A node is exposed
only when it has both the `api-gateway` role and its own `hostname`. Yano HTTP
always remains bound to loopback. The deployment installs a checksum-pinned
Traefik gateway: `public-https` obtains and renews a Let's Encrypt certificate,
while `allowlist` uses supplied TLS certificate/key files and requires a Yano
API-key file. `proxy: cloudflare` restricts origin ports 80/443 to Cloudflare's
published proxy ranges. App-chain P2P port 13337 is limited to the declared
peer node addresses in both generated cloud and host firewalls. A node carrying
the explicit `l1-bootstrap` role is the only exception and accepts public P2P
ingress; this role does not change validator membership.

SSH access defaults to `mode: allowlist` with explicit non-wildcard source
CIDRs. Operators whose management address is genuinely dynamic may explicitly
select `mode: public-key-only` with an empty `sourceCidrs` list. That mode
opens key-only SSH on IPv4 and IPv6, and apply fails before
changing the firewall unless effective sshd configuration enables public-key
authentication while disabling password, keyboard-interactive, and direct
root login. Existing VM deployments manage the host firewall only; any cloud
or data-center firewall attached outside this deployment remains externally
managed. API exposure is unaffected and stays disabled unless separately
configured. Cloudflare-proxied API hostnames are not valid P2P endpoints;
validators retain their direct advertised IP or DNS addresses on port 13337.
CIDR values are parsed as numeric IPv4/IPv6 networks before they can reach a
generated firewall command.

The public API gateway uses a per-client token bucket. Its default is 20
requests per second with a burst of 40 and can be adjusted declaratively:

```yaml
access:
  api:
    exposure: public-https
    proxy: cloudflare
    sourceCidrs: []
    rateLimit: {average: 200, burst: 400}
    cors:
      allowedOrigins:
        - http://127.0.0.1:4173
```

`yano-x-deploy gateway ./cluster` reconciles only the generated Traefik route;
it does not restart Yano or alter retained state. Higher values reduce gateway
protection, and Yano's independent pending-pool backpressure can still return
HTTP 429 for message submissions.

Browser applications hosted on another origin require an exact CORS allow-list. HTTPS origins are
accepted, while plain HTTP is limited to `localhost`, `127.0.0.1`, and `::1` for local development.
The gateway permits `GET`, `HEAD`, `POST`, and `OPTIONS`, plus `Accept`, `Content-Type`, and the
optional `X-API-Key` request header. Keep this list empty for same-origin product UI hosting.

## Optional central Prometheus

Set exactly one validator's `roles` to include `monitoring`, then configure the
matching owner under `spec.monitoring`:

```yaml
monitoring:
  mode: central-prometheus
  node: node-0
  hostname: metrics-showcase.example.org
  exposure: public-https
  proxy: cloudflare
  retention: 15d
  retentionSize: 2GB
  scrapePort: 9091
```

Normal `apply` reconciles monitoring after validator convergence. The focused
`monitoring` command can add or repair monitoring without restarting Yano or
resetting retained state. It installs Docker and Compose only on the selected
owner, runs the digest-pinned Prometheus image on host networking with its web
listener restricted to `127.0.0.1:9090`, and persists history in the labeled
`yano-prometheus-data` volume. Every validator exposes only `/q/metrics` on the
dedicated scrape port; Traefik and UFW accept that port only from the selected
owner address.

The metrics hostname routes through Traefik on the owner. Each node's plain
`/ui/observability/` route redirects to the same central metrics origin, and
Prometheus CORS is restricted to the declared node API hostnames. The gateway
also extends the console's `connect-src 'self'` policy with only that configured
metrics origin; a cross-origin Prometheus URL otherwise fails in the browser
before CORS is evaluated. `public-https` is an explicit Preprod/showcase
exposure and is not an authentication control; put the hostname behind
Cloudflare Access or an equivalent separate control before using this mode for
sensitive or production monitoring.

See [ADR-039](../adr/039-geographically-distributed-deployment-automation.md)
for the trust model, provider evidence, and qualification work that still
requires real cloud accounts and an explicitly named paid deployment.
