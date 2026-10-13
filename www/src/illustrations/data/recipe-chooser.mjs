// Which release recipe to start from. The questions are guidance; every result's
// name, id, availability, maturity, capabilities and outcome are read from the
// release recipe catalog at build time, so a renamed or removed recipe fails the
// build instead of drifting.

import fs from 'node:fs';

const CATALOG = 'tooling/devtools/src/main/resources/appchain-dx/v1alpha1/appchain-recipe-catalog.json';
const recipes = Object.fromEntries(JSON.parse(fs.readFileSync(new URL(`../../../../${CATALOG}`, import.meta.url), 'utf8'))
  .recipes.map((recipe) => [recipe.id, recipe]));

const RECIPES_PAGE = { label: 'Recipe catalog', href: '/recipes/' };

function recipe(id, links = []) {
  const entry = recipes[id];
  if (!entry) throw new Error(`recipe-chooser: recipe "${id}" is not in ${CATALOG}`);
  return {
    result: {
      title: entry.name,
      text: entry.primaryOutcome ?? entry.description,
      facts: [
        ['Recipe', `\`${entry.id}\``],
        ['Availability', `${entry.availability} · ${entry.maturity}`],
        ['Capabilities', entry.capabilities.map((capability) => `\`${capability}\``).join(', ')],
      ],
      links: [...links, RECIPES_PAGE],
    },
  };
}

export default {
  id: 'recipe-chooser',
  type: 'chooser',
  title: 'Which recipe should I start from?',
  tag: 'Decision aid',
  intro: 'answer for your outcome. Each result is a recipe in this release’s catalog; `./yano.sh appchain recipes` '
    + 'lists the same set.',
  start: 'outcome',
  nodes: {
    outcome: {
      question: 'What should the ledger do?',
      options: [
        { label: 'Keep an append-only log of opaque records', next: 'audit-log' },
        { label: 'Keep keyed records, documents or collections with proofs', next: 'records' },
        { label: 'Approve things before they take effect', next: 'approvals' },
        { label: 'Connect existing machines: when X happens, check, then do Y', next: 'declarative-composite' },
        { label: 'Publish evidence to storage and notify other systems', next: 'evidence-ledger' },
        { label: 'Run a UTxO-style ledger or explore ZK settlement', next: 'eutxo' },
        { label: 'Something no stock machine models', next: 'custom-plugin' },
      ],
    },
    records: {
      question: 'What shape are the records?',
      options: [
        { label: 'One value per key, owned by its first writer', next: 'owned-registry' },
        { label: 'A trail of document hashes per product, case or shipment', next: 'document-trail' },
        { label: 'Several collections, with optional schema validation and policies', next: 'authenticated-map' },
      ],
    },
    approvals: {
      question: 'Who approves?',
      options: [
        { label: 'The member nodes themselves', next: 'approval-workflow' },
        { label: 'People or organizations, by business role', next: 'role-approval' },
      ],
    },
    eutxo: {
      question: 'Which kind of experiment?',
      help: 'These recipes are experimental. Use test networks and no real funds.',
      options: [
        { label: 'A test ledger funded at genesis', next: 'eutxo-ledger' },
        { label: 'Deposits from and withdrawals to Cardano', next: 'eutxo-cardano-bridge' },
        { label: 'ZeroJ validity proofs, for development', next: 'eutxo-zeroj-validity' },
        { label: 'The full ZeroJ testnet lifecycle', next: 'eutxo-zeroj-preview' },
      ],
    },
    'audit-log': recipe('audit-log', [{ label: 'Your first app ledger', href: '/tutorials/01-first-app-chain/' }]),
    'owned-registry': recipe('owned-registry', [{ label: 'kv-registry', href: '/state-machines/kv-registry/' }]),
    'document-trail': recipe('document-trail', [{ label: 'doc-trail', href: '/state-machines/doc-trail/' }]),
    'authenticated-map': recipe('authenticated-map',
      [{ label: 'authenticated-map', href: '/state-machines/authenticated-map/' }]),
    'approval-workflow': recipe('approval-workflow', [{ label: 'approvals', href: '/state-machines/approvals/' }]),
    'role-approval': recipe('role-approval',
      [{ label: 'Domain-role approvals', href: '/tutorials/05-domain-role-approvals/' }]),
    'declarative-composite': recipe('declarative-composite', [{ label: 'Bindings learning path', href: '/bindings/' }]),
    'evidence-ledger': recipe('evidence-ledger', [{ label: 'Evidence', href: '/products/evidence/' }]),
    'eutxo-ledger': recipe('eutxo-ledger', [{ label: 'eUTxO and ZK', href: '/products/eutxo-and-zk/' }]),
    'eutxo-cardano-bridge': recipe('eutxo-cardano-bridge', [{ label: 'eUTxO and ZK', href: '/products/eutxo-and-zk/' }]),
    'eutxo-zeroj-validity': recipe('eutxo-zeroj-validity', [{ label: 'eUTxO and ZK', href: '/products/eutxo-and-zk/' }]),
    'eutxo-zeroj-preview': recipe('eutxo-zeroj-preview', [{ label: 'eUTxO and ZK', href: '/products/eutxo-and-zk/' }]),
    'custom-plugin': recipe('custom-plugin', [{ label: 'The extension ladder', href: '/plugins/' }]),
  },
  sources: [
    { repo: 'yano-x', path: CATALOG,
      anchors: ['"id": "audit-log"', '"id": "owned-registry"', '"id": "document-trail"', '"id": "authenticated-map"',
        '"id": "approval-workflow"', '"id": "role-approval"', '"id": "declarative-composite"',
        '"id": "evidence-ledger"', '"id": "eutxo-ledger"', '"id": "eutxo-cardano-bridge"',
        '"id": "eutxo-zeroj-validity"', '"id": "eutxo-zeroj-preview"', '"id": "custom-plugin"',
        'A first-writer-owned key/value registry', 'Validator members propose, approve, reject',
        'Governed organizations, actors, and policies approve', 'funded by an explicit virtual genesis allocation',
        'stable deposits and claim-bound federated withdrawals'] },
  ],
};
