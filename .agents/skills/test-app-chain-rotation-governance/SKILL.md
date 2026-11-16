---
name: test-app-chain-rotation-governance
description: Regression test: two-node Yano X devnet app chain with a rotating sequencer and governed membership; verify proposership rotates across L1-slot windows and a governed member-add activates identically on both nodes. Runs Yano X release-QA test appchain-rotation-governance.
---

# App-chain rotation and governance

## Run it

```bash
JAR=/absolute/path/yano.jar qa/release-qa.sh --only appchain-rotation-governance
```

`JAR` is the absolute path of `yano.jar` inside an extracted integrated Yano X JVM
distribution (built by `distribution/jvm`); the script does not build it. If no such
distribution exists, say so and stop.

Run it with `run_in_background` and wait for the completion notification; do not poll.
It runs on the isolated harness ports listed in `qa/README.md` and only kills processes
it started. Nodes already running on 7070/13337 and the retained showcase on 7170-7172
are never touched. Expected time: about 4-6 minutes.

If it exits 3, pre-flight failed (a missing tool, a missing `JAR` or a busy harness port): report what it
printed and stop. Do not kill whatever holds the port.

## What it checks

- Rotating mode with a positive window, proposer in {A, B}, no split votes; peers connected.
- Tips identical; every block has 2 or more certificate signatures; at least 2 distinct
  proposers across blocks.
- One approval alone changes nothing; the governed member-add activates on both nodes
  (3 members) at the same activation height.
- Ordinary messages still finalize afterwards; final tips identical.

Harness: `qa/harness/appchain-rotation-governance.sh` (window 50 slots, about 10 s at 0.2 s slots).

## Notes

B logs a few "empty chain state" ERRORs while it starts; they are expected.
On a slow host, widen the rotation window with `WINDOW=<slots>` (default 50, about 10 s).

## Report

Read `qa/results/<run-id>/report.md` (the newest directory under `qa/results/`) and reply with:

- the verdict for each test id and its duration;
- the PASS/FAIL check lines the report shows for it;
- for any failure: the verdict reason, the failed checks, and the log paths
  `qa/results/<run-id>/logs/<id>.log` and `qa/results/<run-id>/runs/` (node logs).

To investigate a failure, read those logs before re-running. Do not change the harness
to make a test pass; report what failed. The full suite is
`JAR=<absolute yano.jar> qa/release-qa.sh` (see `qa/README.md`).
