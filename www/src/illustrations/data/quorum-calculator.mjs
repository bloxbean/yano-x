// Threshold presets checked against the three startup rules in Yano's
// ConsensusQuorum, in its evaluation order: the first failing rule stops the
// check, and its exception message is shown as the code.

const RULES = {
  range: '`1 ≤ t ≤ n`: the threshold is within the membership',
  safety: '`2t − n > f`: any two quorums share an honest member',
  liveness: '`t ≤ n − f`: a quorum remains with `f` members faulty',
};
const CODES = {
  range: 'consensus threshold must be within membership',
  safety: 'consensus quorums do not intersect in an honest member',
  liveness: 'consensus threshold cannot remain live under the configured fault bound',
};
const COLUMNS = ['Members `n`', 'Threshold `t`', 'Fault bound `f`', 'May be offline'];

/** One configuration as a step: rules in evaluation order, stopping at the first failure. */
function preset(n, t, f, title, text) {
  const results = {
    range: t >= 1 && t <= n,
    safety: 2 * t - n > f,
    liveness: t <= n - f,
  };
  let failed = false;
  const checks = Object.keys(RULES).map((rule) => {
    if (failed) return { label: RULES[rule], ok: null };
    if (!results[rule]) {
      failed = true;
      return { label: RULES[rule], ok: false, code: CODES[rule] };
    }
    return { label: RULES[rule], ok: true };
  });
  const offline = failed ? '— the chain does not start' : String(n - t);
  return {
    title,
    text,
    checks,
    state: { columns: COLUMNS, rows: [[String(n), String(t), String(f), offline]] },
  };
}

export default {
  id: 'quorum-calculator',
  type: 'steps',
  title: 'Threshold rules',
  tag: 'Rule check',
  intro: 'one threshold configuration per step, checked against the three rules a node enforces before it starts a '
    + 'chain. `n` is the member count, `t` the threshold, and `f` the setting `consensus.max-byzantine-members` '
    + '(default 0).',
  lanes: [],
  scenarios: [
    {
      id: 'crash',
      label: 'Crash faults (f = 0)',
      steps: [
        preset(3, 2, 0, '2 of 3',
          'The launcher default for three members, a majority. Any two quorums of 2 share a member, and the ledger '
            + 'keeps finalizing with one member offline.'),
        preset(3, 1, 0, '1 of 3',
          'Two members could each finalize a different block on their own, because two quorums of 1 need not '
            + 'share a member. The node refuses this configuration.'),
        preset(4, 2, 0, '2 of 4',
          'Half is not enough: members A and B could finalize one block while C and D finalize another. With '
            + '`f = 0`, the threshold must be more than half the members.'),
        preset(4, 3, 0, '3 of 4',
          'The launcher default for four members. One member may be offline.'),
        preset(5, 3, 0, '3 of 5',
          'The launcher default for five members. Two members may be offline.'),
        preset(1, 1, 0, '1 of 1',
          'A single member. The host’s default threshold is 1, which passes only for one member; for more '
            + 'members, set the threshold.'),
      ],
    },
    {
      id: 'byzantine',
      label: 'One Byzantine member (f = 1)',
      summary: 'With `f = 1`, safety must hold even if one member signs conflicting votes.',
      steps: [
        preset(4, 3, 1, '3 of 4, f = 1',
          'The classic `n = 3f + 1`, `t = 2f + 1` profile. Any two quorums of 3 share two members, so at least '
            + 'one honest member is in both. One member may be offline.'),
        preset(3, 2, 1, '2 of 3, f = 1',
          'Two quorums of 2 share only one member, and that member could be the dishonest one. A 2-of-3 ledger is '
            + 'crash-safe, but it does not tolerate a Byzantine member.'),
        preset(4, 4, 1, '4 of 4, f = 1',
          'Safe, but not live: if the one faulty member stops voting, only 3 remain, which is below the threshold.'),
        preset(5, 4, 1, '4 of 5, f = 1',
          'A stricter profile that also satisfies both rules. One member may be offline.'),
      ],
    },
  ],
  sources: [
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/consensus/ConsensusQuorum.java',
      anchors: ['threshold < 1 || threshold > members', '2L * threshold - members <= maxByzantineMembers',
        'threshold > members - maxByzantineMembers', 'return members - threshold;',
        'consensus threshold must be within membership', 'consensus quorums do not intersect in an honest member',
        'consensus threshold cannot remain live under the configured fault bound'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/MemberGroup.java',
      anchors: ['"consensus.max-byzantine-members", "0"'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/AppChainSubsystem.java',
      anchors: ['Validate the fresh-chain fault assumptions before persisting any'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/AppChainConfig.java',
      anchors: ['MAX_MEMBERS = 32', 'private int threshold = 1;'] },
    { repo: 'yano', path: 'adr/app-layer/036-certified-view-change-and-durable-l1-observation-delivery.md',
      anchors: ['2q - n > f', 'q <= n - f',
        'A two-of-three profile is crash-safe but must not be advertised as tolerating one Byzantine member.'] },
    { repo: 'yano-x', path: 'scripts/appchain-cluster/cluster.sh',
      anchors: ['default_threshold() { echo $(( $1 / 2 + 1 )); }'] },
  ],
};
