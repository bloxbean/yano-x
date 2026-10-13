# Tutorial 9 — From Local Demo to a Permissioned Pilot

[Open a pilot starting point in App-Chain Studio](../../../tooling/studio/src/main/web/index.html#recipe=audit-log&network=preprod&members=3&finality=all&sequencing=rotating&runtime=jvm&deployment=host&name=permissioned-pilot&chainId=permissioned-pilot)

- **Goal:** turn a successful tutorial into a reviewable project and a
  deployment plan.
- **You'll learn:** what the project files hold, where each secret lives, the
  status line each project command prints, and which decisions to record
  before traffic.
- **Before you start:** an extracted Yano X JVM distribution and Java 25. The
  commands run offline; nothing here starts a node or spends test ADA.
  Tutorial 1 helps but is not required.
- **Level:** advanced; for platform, security, and application leads
- **Time:** about 20 minutes for the commands, then a planning session
- **Outcome:** a validated pilot project, a doctor report against your exact
  release, and a written plan that does not inherit local-demo assumptions.

This is a decision checklist rather than one launch command. Yano remains
pre-release. The target is a controlled permissioned pilot, not an unqualified
production or public-chain claim.

## 1. Generate a reviewable pilot project

The project commands move you from remembered YAML options to a blueprint,
rendered configuration, and a lock file. Each one prints a status line you can
check.

<!-- illustration: pilot-secrets-and-render -->
1. **Initialize.** `init` writes `appchain.yaml`, the generated files, and
   `appchain.lock`. Without member keys it prints `PROJECT_INITIALIZED` with
   the acknowledgement `PUBLIC_MEMBER_IDENTITIES_REQUIRED_BEFORE_START`.
2. **Sort the secrets.** Private keys never enter the project; the generated
   `secrets/nodeN.env.example` files only name the values each member supplies.
3. **Pin identities.** Add each member's public key and host to
   `appchain.yaml`.
4. **Render.** `render` regenerates the derived files and prints
   `PROJECT_RENDERED`.
5. **Validate.** `config validate --mode project` prints `VALID_PROJECT`.
6. **Doctor.** `doctor --distribution` checks the project against your exact
   release and ends with `DOCTOR_OK`, `DOCTOR_WARNINGS`, or `DOCTOR_FAILED`.
7. **Export for VMs.** `gitops --target ansible` prints `GITOPS_EXPORTED`.
<!-- /illustration -->

List what this release offers, then generate the project:

```bash
./yano.sh appchain recipes
./yano.sh appchain capabilities

./yano.sh appchain init \
  --recipe audit-log \
  --network preprod \
  --members 3 \
  --finality all \
  --sequencing rotating \
  --runtime jvm \
  --deployment host \
  --name permissioned-pilot \
  --chain-id permissioned-pilot \
  --output permissioned-pilot \
  --non-interactive
```

> **✓ You should see** a line starting with `PROJECT_INITIALIZED` that ends
> with `acknowledgements=[PUBLIC_MEMBER_IDENTITIES_REQUIRED_BEFORE_START]`.

The generated runtime files are nested YAML:

- `config/shared-consensus.yaml` holds the values every member must share;
- `config/nodes/nodeN.yaml` holds node-local ports, peers, paths, and secret
  references; and
- `appchain.lock` records the exact resolved values and the digests of the
  generated files.

Customize `appchain.yaml`, not those derived files. Edit it to add the real
member public keys and deployment hosts, in matching order, then regenerate
and check the project:

```bash
./yano.sh appchain render permissioned-pilot
./yano.sh appchain config validate --mode project permissioned-pilot
./yano.sh appchain doctor permissioned-pilot \
  --distribution /path/to/yano-x-jvm-<version>
```

> **✓ You should see** `PROJECT_RENDERED`, then `VALID_PROJECT`, then the doctor
> report. Its last line is `DOCTOR_OK`, `DOCTOR_WARNINGS`, or `DOCTOR_FAILED`.

Read the doctor report line by line. Each line is a status, a check, and a
detail. `FAIL` blocks a start. `WARN` and `PENDING` are a to-do list:

- Until real public keys are pinned, the report warns about
  `PUBLIC_MEMBER_IDENTITIES_REQUIRED_BEFORE_START`, and `IDENTITIES_READY`
  stays `PENDING`. The generated start scripts refuse to start the project.
- This project's rotating sequencer needs a synchronized L1 slot view, so
  `EXTERNAL_TARGETS_READY` stays `PENDING` until you provide one. The result
  is `DOCTOR_WARNINGS`, not `DOCTOR_OK`.

`./yano.sh` is the public command for both paths. Internally, project and
configuration commands use the separately packaged `appchain-devtools`
engine, while `./yano.sh appchain cluster ...` invokes the bundled single-host
launcher directly. Do not invoke the internal executable. Cluster startup does
not run `init`, `render`, or project validation. Use the generated project for
a multi-machine bootstrap, and the cluster command for the packaged local
acceptance environment.

## 2. Freeze the application contract

Record and review:

- chain id and network;
- member public keys, threshold, proposer/sequencer mode;
- state-machine or composite profile id and digest;
- deterministic limits and activation schedule;
- command/state/effect contract versions;
- anchor mode, validator identity, cadence, and stability depth; and
- effect outcome trust policy.

All consensus-affecting values must be identical and profile-committed where
required. Node-local YAML drift must not decide application semantics.

## 3. Separate every identity and secret

Each member supplies its own `secrets/nodeN.env` from the generated
`secrets/nodeN.env.example`. The project's `.gitignore` excludes
`secrets/*.env`, and the Ansible export never embeds private keys.

| Material | Purpose | Recommended owner/storage |
|---|---|---|
| Member signing key | App-block votes and envelopes | One per member, KMS/HSM/secret manager |
| Business actor key | Domain authorization | Actor organization or delegated signing service |
| API key | REST authorization/scopes | API gateway/secret manager |
| Anchor wallet key | Cardano fees/collateral | Capped hot wallet on anchor leader |
| Connector credential | Kafka/S3/IPFS/API access | Executor host only |
| TLS key/trust roots | Transport identity | Platform PKI |

Never copy the deterministic demo keys, launcher API key, sample actor seeds,
or local connector credentials into a shared environment.

## 4. Choose the trust statement

Document what the system actually proves:

- threshold members finalized exact application state;
- actor signatures authorized exact statements under a governed policy;
- an MPF proof connects a record to a state root;
- an L1 anchor connects a certified descendant to Cardano; and
- an effect result is a member/executor attestation unless independently
  verified.

Do not claim that an inspection, oracle value, shipment, API response, or
payment outcome is independently true unless a separate auditor/source check
establishes it.

## 5. Replace demo infrastructure deliberately

| Demo component | Pilot decision |
|---|---|
| RustFS | AWS S3 or reviewed compatible service; versioning/retention policy |
| Single Kubo | Managed/redundant IPFS pinning and retrieval policy |
| Local Kafka | TLS/mTLS/SASL cluster, ACLs, consumer deduplication |
| Local webhook | Named allow-listed target, authentication, idempotent receiver |
| Devnet anchor wallet | Dedicated funded preview/preprod key with spend cap |
| Docker-local secrets | KMS/HSM/Vault or orchestrator secret mounts |

Changing an executor destination does not change deterministic effect intent,
but it does change operational identity, reconciliation, and credentials.

## 6. Establish operations before traffic

- Health/readiness for every member and plugin.
- Root/profile parity gate across members.
- Metrics and alerts for app lag, finality, pool pressure, anchor lag, effect
  backlog/age/retries/parking, sink lag, and disk pressure.
- Snapshot, restore, member onboarding, and retained-state identity runbooks.
- Member-key and actor-key rotation/revocation exercises.
- Connector outage and executor crash recovery.
- Anchor-leader failure/recovery.
- Evidence/proof archival before configured pruning horizons.
- Explicit maintenance and governed-upgrade process.

## 7. Run acceptance in layers

1. Clean deterministic unit/conformance suites.
2. Three-member packaged local cluster.
3. Restart one member and prove catch-up/root parity.
4. Stop/restart the full deployment from retained state.
5. Connector fault matrix and duplicate-boundary tests.
6. Load/soak test with recorded topology, rate, payloads, duration, lag,
   resources, and failures.
7. Preview/preprod anchor smoke with independent L1 verification.
8. Restore rehearsal from the retained backup procedure.

Keep acceptance artifacts with the release rather than relying on screenshots
or a remembered manual session.

## 8. Know the current escalation gates

- Material Cardano funds require the production action hardening tracked for
  `cardano.payment` and native assets.
- Semi-trusted members/executors require governed result-signer policy and
  independent outcome auditing before receipts are marketed as independently
  verified.
- Regulated personal data requires encryption/erasure guidance; immutable
  object/IPFS/on-chain digests are not a deletion mechanism.
- Public validator participation is outside the current permissioned model.
  Consensus recovers from an offline leader through a certified view change,
  but by default it assumes no dishonest members. Choose the threshold for the
  faults you must survive; see Yano's
  [consensus guide](https://github.com/bloxbean/yano/blob/main/docs/APP_CHAIN_CONSENSUS_GUIDE.md).

## 9. Choose the deployment shape

- **JVM distribution:** supports plugin-directory installation and is the
  simplest extensible pilot shape.
- **Yano native image:** runs the core ordered-log capability only. Yano X
  plugins are JVM-only and cannot be installed into that image; choose the JVM
  distribution for a Yano X pilot.
- **Embedded library:** appropriate when an application team owns lifecycle,
  configuration, APIs, and dependency integration in one Java service.

## Troubleshooting

| You see | What it means and what to do |
|---|---|
| `Generated file has manual edits: ...; move the change into appchain.yaml or reconcile it explicitly` | A generated file was edited by hand. Move the change into `appchain.yaml` and render again. |
| `DOCTOR_FAILED` with a `distribution-index` failure | The `--distribution` you pointed at is not the release the project was generated for. Use the matching release. |
| `DOCTOR_FAILED` with a `tool-version` failure | You ran `doctor` from a different release than the one the project pins. Use that release's `yano.sh`. |
| `Ansible export requires a JVM host project with one public key and hostname per member` | Pin every member's public key and host in `appchain.yaml`, then render again. |

## Go deeper

- [Full user/operations guide](../../APP_CHAIN_USER_GUIDE.md)
- [Profile-governance runbook](../../APP_CHAIN_PROFILE_GOVERNANCE.md)
- [Canonical app-layer open items](../../../adr/app-layer/open_item.md)
- [Evidence flow and trust limits](../../EVIDENCE_CHAIN_DEMO.md)
- [Domain-role production signing and recovery](../../APP_CHAIN_DOMAIN_ROLES.md)

**Next:** plan a real deployment with [Deploy Yano X](../deployment/README.md).
