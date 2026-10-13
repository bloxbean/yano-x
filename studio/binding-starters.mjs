/**
 * Starter documents shipped with the binding editor (ADR-031.2 M1) and their public example fixtures. The files
 * come from `examples/bindings/` (single source); fixtures use the fixed names of the editor's CLI handoff.
 */
export const STARTERS = Object.freeze([
  Object.freeze({id: 'registry-to-audit', file: 'registry-to-audit.yaml', label: 'Registry to audit', fixtures: 1,
    description: 'A registry put appends an audit entry in the same atomic cascade.'}),
  Object.freeze({id: 'approval-to-audit', file: 'approval-to-audit.yaml', label: 'Approval to audit', fixtures: 4,
    description: 'Each vote is a separate message; the approving vote appends an audit entry.'}),
  Object.freeze({id: 'balances-transfer-limit', file: 'balances-transfer-limit.yaml', label: 'Transfer limit rule',
    fixtures: 3, description: 'An admission rule refuses transfers above a limit, before a block (the third fixture); '
      + 'mint is not affected.'}),
  Object.freeze({id: 'procurement-admission', file: 'procurement-admission.yaml', label: 'Procurement admission rules',
    fixtures: 4, description: 'Rules at several depths: registered suppliers only, a minimum quorum, and an audit '
      + 'trail that accepts entries only through the approval binding.'})]);
export const STARTER_BASE = 'assets/bindings/';
