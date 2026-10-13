// One `./demo.sh publish` in the Evidence tutorial, default `evidence-v1-gated`
// profile, plus replay, a wrong version number, and a failed connector. Facts
// follow the evidence demo runner, harness, and status derivation; see `sources`.

const stage = {
  title: 'Stage',
  text: 'The runner copies the exact document bytes into the RustFS staging area and adds them to Kubo without '
    + 'pinning, which computes the CID. Nothing is on the ledger yet.',
  wires: [{ from: 'runner', to: 'conn', label: 'stage bytes' }],
  cards: {
    conn: { title: 'Staged', detail: 'SHA-256 + CID known' },
  },
};

const approve = {
  title: 'Approve',
  text: 'The runner registers the document hash, proposes an approval for the exact evidence command, and '
    + 'approves it, waiting for each message to be final. In this profile the approvers are members, and the '
    + 'demo requires one approval.',
  wires: [{ from: 'runner', to: 'ledger', label: 'registry + approval' }],
  cards: {
    ledger: { title: 'Approved', detail: 'for this exact command', tone: 'ok' },
  },
};

const release = {
  title: 'Release',
  text: 'The runner submits `evidence.release.v1`. The composite admits it only with that registry entry and '
    + 'approval, applies the document-trail append and the evidence version atomically, and emits two effects: '
    + '`object.put` and `ipfs.pin`. Status: `STORAGE_PENDING`.',
  wires: [{ from: 'runner', to: 'ledger', label: '`evidence.release.v1`' }],
  cards: {
    ledger: { title: 'Version 1', detail: '`STORAGE_PENDING`' },
  },
};

const store = {
  title: 'Store and pin',
  text: 'After finality, the effect owner copies the staged object into the versioned archive, which never '
    + 'overwrites a different object, and pins the CID in Kubo.',
  wires: [
    { from: 'ledger', to: 'fx', label: 'two final effects' },
    { from: 'fx', to: 'conn', label: 'copy + pin' },
  ],
  cards: {
    fx: { title: 'Executing', detail: '`object.put` · `ipfs.pin`' },
    conn: { title: 'Archived + pinned', detail: 'receipts returned', tone: 'ok' },
  },
};

const incorporate = {
  title: 'Incorporate',
  text: 'Both results return as `~fx/result`. Status is derived from the receipts: each must match the '
    + 'command’s size, SHA-256, destination fingerprint, and CID. Status: `STORAGE_READY`. The runner then '
    + 're-reads the archived object and the IPFS content itself.',
  wires: [{ from: 'fx', to: 'ledger', label: '2 × `~fx/result`' }],
  cards: {
    ledger: { title: 'Version 1', detail: '`STORAGE_READY`', tone: 'ok' },
  },
};

const notify = {
  title: 'Notify',
  text: 'With `--continuation direct`, the second storage result emits `kafka.publish` itself; in the default '
    + 'explicit mode the runner submits a notify command. The Kafka receipt records partition and offset. '
    + 'Status: `READY`.',
  wires: [{ from: 'fx', to: 'conn', label: '`kafka.publish`' }],
  cards: {
    conn: { title: 'Kafka event', detail: 'partition + offset', tone: 'ok' },
    ledger: { title: 'Version 1', detail: '`READY`', tone: 'final' },
  },
};

const anchor = {
  title: 'Agree and anchor',
  text: 'The runner waits until all three members hold the same `READY` state and a devnet script anchor '
    + 'covers it.',
  wires: [{ from: 'ledger', to: 'l1', label: 'anchor advance', tone: 'cardano' }],
  cards: {
    l1: { title: 'Anchored', detail: 'covers `READY`', tone: 'cardano' },
  },
};

const verify = {
  title: 'Verify',
  text: 'Finally, the runner checks finality, the anchor, the proofs, and Kafka, and prints '
    + '`PASS command=publish`. Run `./demo.sh verify` later to repeat the reads without writing anything.',
  checks: [
    { label: 'Members agree on the state', ok: true, code: 'THREE_NODE_STATE_AGREEMENT' },
    { label: 'Threshold finality of the workflow messages', ok: true, code: 'THRESHOLD_FINALITY_BUNDLES' },
    { label: 'The anchor transaction is visible to every member', ok: true,
      code: 'PORTABLE_ANCHOR_TXS_VISIBLE_ON_ALL_MEMBERS' },
    { label: 'The anchor datum commits to the certified root', ok: true,
      code: 'PORTABLE_ANCHOR_DATUM_COMMITMENTS_VERIFIED' },
    { label: 'State and effect proofs against one root', ok: true, code: 'COMPOSED_EFFECT_PROOFS' },
    { label: 'Kafka acknowledgement and event bytes', ok: true, code: 'KAFKA_ACKNOWLEDGEMENT_AND_EVENT' },
  ],
  cards: {
    runner: { title: 'PASS', detail: 'report in the Explorer', tone: 'final' },
  },
};

export default {
  id: 'evidence-pipeline',
  type: 'steps',
  title: 'Publish one piece of evidence',
  intro: 'one `./demo.sh publish` in the default `evidence-v1-gated` profile on devnet. The “What if” scenarios '
    + 'cover replay, a wrong version number, and a failed connector.',
  lanes: [
    { id: 'runner', label: 'Demo runner', note: '`./demo.sh`', kind: 'client' },
    { id: 'ledger', label: 'App ledger', note: 'three members', kind: 'member' },
    { id: 'fx', label: 'Effect owner', note: 'one executor', kind: 'runtime' },
    { id: 'conn', label: 'Connectors', note: 'RustFS · Kubo · Kafka', kind: 'external' },
    { id: 'l1', label: 'Cardano', note: 'devnet anchor', kind: 'cardano' },
  ],
  scenarios: [
    {
      id: 'publish',
      label: 'Publish version 1',
      steps: [stage, approve, release, store, incorporate, notify, anchor, verify],
    },
    {
      id: 'replay',
      label: 'Replay a version',
      summary: '`./demo.sh replay` resubmits a release that already happened.',
      steps: [{
        title: 'Compare the bytes',
        text: 'The runner reads version 2 from the members and compares your file with the recorded SHA-256 and '
          + 'size. Different bytes stop here with `REPLAY_INPUT_MISMATCH`.',
        wires: [{ from: 'ledger', to: 'runner', label: 'version 2 record' }],
        cards: { runner: { title: 'Bytes match', detail: 'same digest and size', tone: 'ok' } },
      }, {
        title: 'Release again',
        text: 'The runner submits the same release command, and it becomes final like any other message.',
        wires: [{ from: 'runner', to: 'ledger', label: '`evidence.release.v1` again' }],
        cards: { ledger: { title: 'Message final', detail: 'deterministic no-op' } },
      }, {
        title: 'Nothing changes',
        text: 'The evidence record and its effects are unchanged, and the Kafka end offset has not moved. The '
          + 'runner then repeats the read-only verification.',
        checks: [{ label: 'Replay finalized as a no-op', ok: true, code: 'REPLAY_FINALIZED_AS_DETERMINISTIC_NOOP' }],
        cards: {
          ledger: { title: 'Version 2', detail: 'unchanged · `READY`', tone: 'final' },
          conn: { title: 'No new writes', detail: 'same Kafka offset' },
          runner: { title: 'PASS', detail: '`command=replay`', tone: 'final' },
        },
      }],
    },
    {
      id: 'wrong-version',
      label: 'Republish the wrong version',
      summary: 'Versions are immutable and strictly sequential.',
      steps: [{
        title: 'Read the head',
        text: 'You run `republish --business-version 3`, but the latest version is 1. The runner reads the head '
          + 'record before it stages anything.',
        wires: [{ from: 'ledger', to: 'runner', label: 'latest version: 1' }],
        cards: { ledger: { title: 'Head', detail: 'latest version 1' } },
      }, {
        title: 'Refuse',
        text: 'The only acceptable next version is 2, so the runner stops with `VERSION_CONFLICT`. Other '
          + 'refusals: `publish` of an existing id gives `EVIDENCE_ALREADY_EXISTS`, and a republish while the '
          + 'previous version is still in progress gives `PRIOR_VERSION_NOT_TERMINAL`.',
        checks: [{ label: 'The version is exactly the latest plus one', ok: false, code: 'VERSION_CONFLICT' }],
        cards: { runner: { title: 'FAIL', detail: '`VERSION_CONFLICT`', tone: 'fail' } },
      }],
    },
    {
      id: 'connector-fails',
      label: 'One connector fails',
      summary: 'Status is derived from receipts, never asserted.',
      steps: [stage, approve, release, {
        ...store,
        text: 'After finality, the effect owner archives the object, but Kubo rejects the pin.',
        wires: [
          { from: 'ledger', to: 'fx', label: 'two final effects' },
          { from: 'fx', to: 'conn', label: 'copy + pin', tone: 'fail' },
        ],
        cards: {
          fx: { title: 'Executed', detail: 'pin FAILED', tone: 'fail' },
          conn: { title: 'Archived only', detail: 'no pin', tone: 'fail' },
        },
      }, {
        title: 'Derive the status',
        text: 'One storage result is a matching confirmation and the other is not, so the status is `PARTIAL`. '
          + 'The runner stops with `FAIL code=STORAGE_FAILED`. `PARTIAL` is terminal, so the owner may republish '
          + 'the document as the next version.',
        wires: [{ from: 'fx', to: 'ledger', label: '2 × `~fx/result`' }],
        state: {
          caption: 'Storage status from the two receipts',
          columns: ['`object.put`', '`ipfs.pin`', 'Status'],
          rows: [
            ['confirmed', 'confirmed', '`STORAGE_READY`'],
            ['confirmed', 'failed', '`PARTIAL`'],
            ['expired', 'failed', '`EXPIRED`'],
            ['failed', 'failed', '`STORAGE_FAILED`'],
          ],
          highlight: [1],
        },
        cards: {
          ledger: { title: 'Version 1', detail: '`PARTIAL`', tone: 'fail' },
          runner: { title: 'FAIL', detail: '`STORAGE_FAILED`', tone: 'fail' },
        },
      }],
    },
  ],
  legend: [
    ['client', 'demo runner'],
    ['member', 'app ledger members'],
    ['runtime', 'effect execution'],
    ['external', 'connectors'],
    ['cardano', 'Cardano'],
    ['final', 'final or verified'],
    ['fail', 'failed or refused'],
  ],
  sources: [
    { repo: 'yano-x',
      path: 'products/evidence/demo-runner/src/main/java/org/yanoproject/x/examples/evidence/demo/EvidenceScenario.java',
      anchors: ['environment.s3.stage(relativeKey, document, digest);', 'environment.kubo.addUnpinned(document);',
        'EvidenceReleasePrerequisiteCommandsV1.registryPut(', 'approvalId, evidenceCommand, 1, 0',
        'EvidenceReleasePrerequisiteCommandsV1.approvalApprove(approvalId)', 'EvidenceReleaseCommandV1.TOPIC',
        'submitNotificationForLoad(primary, config, request.evidenceId(), version);',
        '"THREE_NODE_STATE_AGREEMENT"', '"THRESHOLD_FINALITY_BUNDLES"',
        '"PORTABLE_ANCHOR_TXS_VISIBLE_ON_ALL_MEMBERS"', '"PORTABLE_ANCHOR_DATUM_COMMITMENTS_VERIFIED"',
        '"COMPOSED_EFFECT_PROOFS"', '"KAFKA_ACKNOWLEDGEMENT_AND_EVENT"',
        '"REPLAY_FINALIZED_AS_DETERMINISTIC_NOOP"', 'environment.kafka.endOffset() != kafkaEnd',
        'DemoError.REPLAY_INPUT_MISMATCH', 'DemoError.VERSION_CONFLICT', 'DemoError.EVIDENCE_ALREADY_EXISTS',
        'DemoError.PRIOR_VERSION_NOT_TERMINAL', 'case PARTIAL, STORAGE_FAILED, EXPIRED -> true;',
        'throw new DemoException(DemoError.STORAGE_FAILED);'] },
    { repo: 'yano-x',
      path: 'products/evidence/demo-runner/src/main/java/org/yanoproject/x/examples/evidence/demo/EvidenceDemoMain.java',
      anchors: ['output.println("PASS command=" + request.operation().name().toLowerCase()'] },
    { repo: 'yano-x',
      path: 'products/evidence/contracts/src/main/java/org/yanoproject/x/examples/evidence/state/EvidenceStatus.java',
      anchors: ['STORAGE_PENDING,', 'STORAGE_READY,', 'PARTIAL,', 'STORAGE_FAILED,', 'EXPIRED,', 'READY,',
        'case PARTIAL, STORAGE_FAILED, EXPIRED, READY,'] },
    { repo: 'yano-x', path: 'products/evidence/harness/README.md',
      anchors: ['The composite applies the document-trail append and', 'evidence submission atomically',
        'directly from the second incorporated', '`evidence-staging`'] },
    { repo: 'yano-x', path: 'docs/APP_CHAIN_USER_GUIDE.md',
      anchors: ['it does not overwrite or resurrect a conflicting key'] },
    { repo: 'yano-x', path: 'products/evidence/harness/config/common.env',
      anchors: ['DEMO_MACHINE_MODE=composite', 'DEMO_CONTINUATION_MODE=explicit'] },
  ],
};
