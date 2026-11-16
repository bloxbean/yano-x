# Yano X QA

- `framework/`: the independent framework gate and the packaged host matrix
  ([framework/README.md](framework/README.md)).
- `release-qa.sh` and `harness/`: the app-chain regression tests, run against the
  integrated Yano X JVM distribution. They moved here from Yano with the
  application-ledger product (ADR-064 D9).

## App-chain release QA

Build and extract the integrated JVM distribution (`distribution/jvm`), then pass the
absolute path of its `yano.jar`. The nodes run with the jar's directory as working
directory, so its `config/` (devnet profile and key files) must sit next to it.

```bash
JAR=/absolute/path/yano-x-jvm-<version>/yano.jar qa/release-qa.sh
JAR=/absolute/path/yano-x-jvm-<version>/yano.jar qa/release-qa.sh --only appchain-cluster
qa/release-qa.sh --list
```

| Test id | What it checks | Harness |
| --- | --- | --- |
| `appchain-cluster` | Two-node devnet cluster: roles, replicated blocks, MPF proofs, L1 anchor, L1 rollbacks with the follower up and down | `harness/appchain-cluster.sh` |
| `appchain-extensions` | Two chains: chain-scoped routes, query surface, SSE, webhook sink, admin API, membership, evidence, snapshot, metrics | `harness/appchain-extensions.sh` |
| `appchain-rotation-governance` | Rotating sequencer across L1-slot windows and a governed member-add | `harness/appchain-rotation-governance.sh` |
| `appchain-script-anchor` | Script-anchor bootstrap, co-signed advances, follower identity adoption, L1 deposit observations (`harness/Deposit.java`) | `harness/appchain-script-anchor.sh` |
| `appchain-standalone` | Standalone app ledger (`yano.mode=app-ledger`): three nodes, late join, restart, no Cardano state | `harness/appchain-standalone-cluster.sh` |

Results go to `qa/results/<run-id>/` (or `--out DIR`): `results.json`, `report.md`,
`logs/<id>.log` and the node data under `runs/`. Exit code 3 means pre-flight failed:
a missing tool, a missing `JAR` or a busy harness port.

The tests run one at a time on their own ports and only stop processes they started:
HTTP 7371/7372 and N2N 13641/13642 for the two-node tests, 9199 for the webhook sink,
HTTP 17771-17773 and N2N 23441-23443 for the standalone cluster. They never touch
developer nodes on 7070/13337 or the retained showcase on 7170-7172/14337-14339.
A harness can also run alone, for example
`JAR=... qa/harness/appchain-cluster.sh` (output under `qa/work/`), with
`HTTP_A`/`N2N_A`/`HTTP_B`/`N2N_B` overrides when those ports are taken.

`harness/common.sh` is X's bounded copy of the generic process and HTTP helpers of
Yano's harness (ADR-064 D8); Yano's L1, Haskell and native tests stay in Yano.
