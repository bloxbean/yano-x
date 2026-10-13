// Which extension to reach for, smallest first. Rungs that change what the
// ledger computes: configuration, declarative bindings, a Java composite, a
// state-machine plugin. Contributions around the ledger are described with the
// trust tier the host derives from their contribution kind (ADR-011.2).

const LADDER = { label: 'The extension ladder', href: '/plugins/' };

export default {
  id: 'extension-ladder',
  type: 'chooser',
  title: 'Which extension do you need?',
  tag: 'Decision aid',
  intro: 'start with the smallest extension that models your outcome. Each answer says what joins chain identity and '
    + 'how you change it later.',
  start: 'what',
  nodes: {
    what: {
      question: 'What do you need to change?',
      options: [
        { label: 'What the ledger records, and how commands change it', next: 'stock' },
        { label: 'Something around the ledger: actions, block streams, Cardano facts, read APIs or keys', next: 'outside' },
      ],
    },
    stock: {
      question: 'Does a stock machine or recipe already model the outcome?',
      options: [
        { label: 'Yes', next: 'config' },
        { label: 'No', next: 'existing' },
      ],
    },
    existing: {
      question: 'Do existing machines already have the commands and events you need?',
      help: 'For example: when a registry put happens, append an audit entry; when an approval passes, apply a map write.',
      options: [
        { label: 'Yes, I need to connect them', next: 'bounded' },
        { label: 'No, I need new state or new transition rules', next: 'state-machine' },
      ],
    },
    bounded: {
      question: 'Can bindings express the coordination?',
      help: 'Bindings map typed event fields to commands, check conditions and lookups, and attach forbid-only '
        + 'admission rules, with restricted CEL. They cannot call Java, query the network, loop, or create authority.',
      options: [
        { label: 'Yes', next: 'bindings' },
        { label: 'No, it needs coordination outside that language', next: 'java-composite' },
      ],
    },
    outside: {
      question: 'Which of these?',
      options: [
        { label: 'Perform an external action after a decision is final', next: 'executor' },
        { label: 'Deliver finalized blocks to another system', next: 'sink' },
        { label: 'Bring Cardano deposits or metadata into the ledger', next: 'observer' },
        { label: 'Change how proposers are chosen', next: 'sequencer' },
        { label: 'Serve a read API, or keep member keys outside the node', next: 'local' },
      ],
    },
    config: {
      result: {
        title: 'Configuration only',
        text: 'Select a stock machine or recipe, identically on every member. No JAR, no build.',
        facts: [
          ['Code', 'None'],
          ['Joins chain identity', 'The selected machine or profile and its committed settings'],
          ['To change it later', 'A fresh chain, or a governed activation where the setting allows one'],
        ],
        links: [
          { label: 'Choosing a recipe', href: '/recipes/choosing-a-recipe/' },
          { label: 'Recipe catalog', href: '/recipes/' },
        ],
      },
    },
    bindings: {
      result: {
        title: 'Declarative bindings',
        text: 'The bundled `declarative-composite` machine connects existing machines. You write YAML; the tooling '
          + 'compiles it to canonical IR that every member executes.',
        facts: [
          ['Code', 'None: YAML compiled to IR'],
          ['Joins chain identity', 'The IR, component settings and limits, in the composite profile digest'],
          ['To change it later', 'A new workflow generation by governed activation; preflight with `bindings profile-check`'],
        ],
        links: [{ label: 'Bindings learning path', href: '/bindings/' }],
      },
    },
    'java-composite': {
      result: {
        title: 'A Java composite plugin',
        text: 'A small provider that arranges existing components: their order, topics, quotas and workflow '
          + 'transitions. It is short, but it is consensus code.',
        facts: [
          ['Code', 'A small, reviewed Java provider'],
          ['Joins chain identity', 'The profile id and digest; component order is committed profile data'],
          ['To change it later', 'Stage the reviewed bundle on every member, then a governed activation'],
        ],
        links: [LADDER, { label: 'Consensus rules', href: '/plugins/consensus-rules/' }],
      },
    },
    'state-machine': {
      result: {
        title: 'A state-machine plugin',
        text: 'Implement `AppStateMachine` and contribute it through `AppStateMachineProvider`. Your `apply` runs on '
          + 'every member and must be deterministic.',
        facts: [
          ['Code', 'A codec, admission checks and a deterministic `apply`'],
          ['Joins chain identity', 'The machine id and committed settings; every member runs the same bundle'],
          ['To change it later', 'A governed activation, or a new id and namespace; never member by member'],
        ],
        links: [
          { label: 'Tutorial 8', href: '/tutorials/08-plugins-and-composites/' },
          { label: 'Consensus rules', href: '/plugins/consensus-rules/' },
        ],
      },
    },
    executor: {
      result: {
        title: 'An effect executor',
        text: 'Performs an authorized external action after the effect’s finality gate, outside `apply`. With result '
          + 'policy `CHAIN`, the outcome comes back as a member-signed `~fx/result` message.',
        facts: [
          ['Contribution', '`AppEffectExecutorFactory`, trust tier `PRIVILEGED_LOCAL`'],
          ['Reaches state', 'Only through the ordered `~fx/result` input'],
          ['Configuration', 'Endpoints and secrets stay node-local'],
        ],
        links: [{ label: 'Effects', href: '/concepts/effects/' }],
      },
    },
    sink: {
      result: {
        title: 'A finalized-stream sink',
        text: 'Receives finalized blocks and delivers them to another system, such as Kafka or a webhook. Nothing '
          + 'flows back into the ledger.',
        facts: [
          ['Contribution', '`FinalizedStreamSinkFactory`, trust tier `AUXILIARY_LOCAL`'],
          ['Reaches state', 'No'],
        ],
        links: [{ label: 'SPI and manifest', href: '/plugins/spi-and-manifest/' }],
      },
    },
    observer: {
      result: {
        title: 'An L1 observer',
        text: 'Watches Cardano for deposits or metadata labels. Observations enter as `~l1/*` messages that every '
          + 'member re-derives from its own L1 view before it votes.',
        facts: [
          ['Contribution', '`L1ObserverProvider`, trust tier `CONSENSUS`'],
          ['Reaches state', 'Yes, as ordered, certified inputs'],
          ['Joins chain identity', 'Observer profiles are committed in the consensus-context digest'],
        ],
        links: [{ label: 'External observations', href: '/concepts/observations/' }],
      },
    },
    sequencer: {
      result: {
        title: 'A sequencer mode',
        text: 'Decides who may propose at each height and whose proposals are acceptable. It cannot weaken '
          + 'finality: certificates and vote locks are enforced by the framework.',
        facts: [
          ['Contribution', '`SequencerModeProvider`, trust tier `CONSENSUS`'],
          ['Reaches state', 'No, but every member must run the same mode'],
        ],
        links: [{ label: 'Consensus and finality', href: '/concepts/consensus-and-finality/' }],
      },
    },
    local: {
      result: {
        title: 'A domain API or a signer',
        text: 'A domain API serves bounded reads over committed state; keep it read-only unless commands still enter '
          + 'through authenticated submission. A signer keeps the member key in external custody.',
        facts: [
          ['Contribution', '`DomainApiProvider` or `SignerProviderFactory`, trust tier `PRIVILEGED_LOCAL`'],
          ['Reaches state', 'No'],
        ],
        links: [{ label: 'SPI and manifest', href: '/plugins/spi-and-manifest/' }],
      },
    },
  },
  sources: [
    { repo: 'yano-x', path: 'tooling/devtools/src/main/resources/appchain-dx/v1alpha1/appchain-recipe-catalog.json',
      anchors: ['"id": "declarative-composite"', 'Compile bounded event bindings into one committed atomic composite profile.'] },
    { repo: 'yano', path: 'adr/app-layer/011.2-manifested-bundle-catalog.md',
      anchors: ['| `app-state-machine` | `AppStateMachineProvider` | `id()` | CONSENSUS |',
        '| `sequencer-mode` | `SequencerModeProvider` | `id()` | CONSENSUS |',
        '| `l1-observer` | `L1ObserverProvider` | `type()` | CONSENSUS |',
        '| `signer-provider` | `SignerProviderFactory` | `scheme()` | PRIVILEGED_LOCAL |',
        '| `effect-executor` | `AppEffectExecutorFactory` | `scheme()` | PRIVILEGED_LOCAL |',
        '| `finalized-sink` | `FinalizedStreamSinkFactory` | `scheme()` | AUXILIARY_LOCAL |',
        '| `domain-api` | `DomainApiProvider` | `id()` (equal to bundle id) | PRIVILEGED_LOCAL'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/sequencer/SequencerMode.java',
      anchors: ['who may propose now, and whose proposals', 'It can never weaken finality',
        'All members of a chain must run the same mode id (fail-closed).'] },
    { repo: 'yano', path: 'docs/APP_CHAIN_CONSENSUS_GUIDE.md',
      anchors: ['every `~l1/*` observation message re-derives from the node\'s own L1',
        'commits genesis, membership, quorum, and consensus/observer profiles'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/AppStateMachine.java',
      anchors: ['a member-attested {@code ~fx/result}',
        'void apply(AppBlockExecutionContext context, AppStateWriter writer, AppEffectEmitter effects);'] },
    { repo: 'yano-x', path: 'docs/site/concepts-effects.md',
      anchors: ['Executed `CHAIN` outcomes re-enter as member-signed `~fx/result` messages'] },
    { repo: 'yano-x', path: 'docs/appchain/bindings/README.md',
      anchors: ['Expressions cannot call arbitrary Java, query the network or create', 'authority'] },
  ],
};
