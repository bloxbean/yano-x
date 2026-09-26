/**
 * Starter documents shipped with the binding editor (ADR-031.2 M1) and their public example fixtures. The files
 * come from `examples/bindings/` (single source); fixtures use the fixed names of the editor's CLI handoff.
 */
export const STARTERS = Object.freeze([
  Object.freeze({id: 'registry-to-audit', file: 'registry-to-audit.yaml', label: 'Registry to audit', fixtures: 1,
    description: 'A registry put appends an audit entry in the same atomic cascade.'}),
  Object.freeze({id: 'approval-to-audit', file: 'approval-to-audit.yaml', label: 'Approval to audit', fixtures: 4,
    description: 'Each vote is a separate message; the approving vote appends an audit entry.'})]);
export const STARTER_BASE = 'assets/bindings/';
