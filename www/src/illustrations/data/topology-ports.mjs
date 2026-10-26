// Ports by way of running: the single-host launcher (and the showcase that
// drives it), the one-machine `appchain try` rehearsal, and a real
// application with one node per operator. Facts come from cluster.sh,
// showcase.sh, the appchain-cli request, try and join code, and Yano's
// consensus guide.

const CLI = 'tooling/appchain-cli/src/main/java/org/yanoproject/x/appchain/cli';

const LAUNCHER = '`./yano.sh appchain cluster` and the showcase put node i on HTTP 7070 + i and node-to-node port '
  + '13337 + i, listening on 127.0.0.1. The launcher moves a busy default range and prints the new one; the '
  + 'showcase passes its bases explicitly, so a busy range stops it. Change them with `--http-base` and `--server-base`.';
const TRY = '`./yano.sh appchain try` gives alice, bob and carol the first free ports from 8080 (HTTP) and 13337 '
  + '(node-to-node), listening on 127.0.0.1, and prints every command it runs. A launcher cluster already holding '
  + '13337 just moves the rehearsal up.';
const OPERATOR = 'In a real application each operator chooses their ports at `request`; the defaults are HTTP 8080 and '
  + 'node-to-node 13337. With a public host name, the node listens on all interfaces. Other members connect to the '
  + 'node-to-node port; `status` and later members use the HTTP port. Open both only to the machines that need them.';

const node = (id, label, sub, kind, detail) => ({ id, label, sub, kind, detail });

export default {
  id: 'topology-ports',
  type: 'diagram',
  title: 'Nodes, chains, and ports',
  tag: 'Concept',
  hint: 'Select a node to see its port rule.',
  caption: 'A node has one HTTP port and one node-to-node port, whatever the number of chains it hosts. The port '
    + 'numbers depend on how you run it.',
  zones: [
    { id: 'launcher', kind: 'core', label: 'Cluster launcher, showcase', contains: ['l0', 'l1', 'l2'] },
    { id: 'rehearsal', kind: 'ledger', label: 'appchain try, one machine', contains: ['t0', 't1', 't2'] },
    { id: 'hosts', kind: 'external', label: 'One node per operator', contains: ['h0', 'h1', 'h2'] },
  ],
  blocks: [
    node('l0', 'node 0', 'http 7070 · n2n 13337', 'leader', `${LAUNCHER} Node 0 also produces the private devnet.`),
    node('l1', 'node 1', 'http 7071 · n2n 13338', 'member', LAUNCHER),
    node('l2', 'node 2', 'http 7072 · n2n 13339', 'member', LAUNCHER),
    node('t0', 'alice', 'http 8080 · n2n 13337', 'leader', `${TRY} Alice is the lead and produces the devnet.`),
    node('t1', 'bob', 'http 8081 · n2n 13338', 'member', TRY),
    node('t2', 'carol', 'http 8082 · n2n 13339', 'member', TRY),
    node('h0', 'alice', 'http 8080 · n2n 13337', 'member', OPERATOR),
    node('h1', 'bob', 'http 8080 · n2n 13337', 'member', OPERATOR),
    node('h2', 'carol', 'http 8080 · n2n 13337', 'member', OPERATOR),
    {
      id: 'chains', label: 'Every chain on a node shares its two ports', sub: 'REST: /api/v1/app-chain/chains/{chainId}/…',
      kind: 'core',
      detail: 'A node has one HTTP API for all its chains; the path carries the chain id, for example '
        + '`/api/v1/app-chain/chains/orders/messages`. By default, chain traffic between members also shares one '
        + 'node-to-node connection per pair of nodes.',
    },
  ],
  layouts: {
    wide: {
      width: 760,
      height: 388,
      zones: {
        launcher: [16, 16, 232, 284],
        rehearsal: [264, 16, 232, 284],
        hosts: [512, 16, 232, 284],
      },
      blocks: {
        l0: [32, 56, 200, 56],
        l1: [32, 128, 200, 56],
        l2: [32, 200, 200, 56],
        t0: [280, 56, 200, 56],
        t1: [280, 128, 200, 56],
        t2: [280, 200, 200, 56],
        h0: [528, 56, 200, 56],
        h1: [528, 128, 200, 56],
        h2: [528, 200, 200, 56],
        chains: [16, 316, 728, 56],
      },
    },
    narrow: {
      width: 380,
      height: 484,
      labels: Object.fromEntries([
        ['l0', 'http 7070\nn2n 13337'], ['l1', 'http 7071\nn2n 13338'], ['l2', 'http 7072\nn2n 13339'],
        ['t0', 'http 8080\nn2n 13337'], ['t1', 'http 8081\nn2n 13338'], ['t2', 'http 8082\nn2n 13339'],
        ['h0', 'http 8080\nn2n 13337'], ['h1', 'http 8080\nn2n 13337'], ['h2', 'http 8080\nn2n 13337'],
      ].map(([id, sub]) => [id, { sub }]).concat([
        ['chains', { label: 'Every chain on a node\nshares its two ports', sub: 'REST: …/app-chain/chains/{chainId}/…' }],
      ])),
      zones: {
        launcher: [8, 8, 364, 112],
        rehearsal: [8, 136, 364, 112],
        hosts: [8, 264, 364, 112],
      },
      blocks: {
        l0: [20, 44, 108, 64],
        l1: [136, 44, 108, 64],
        l2: [252, 44, 108, 64],
        t0: [20, 172, 108, 64],
        t1: [136, 172, 108, 64],
        t2: [252, 172, 108, 64],
        h0: [20, 300, 108, 64],
        h1: [136, 300, 108, 64],
        h2: [252, 300, 108, 64],
        chains: [8, 392, 364, 76],
      },
    },
  },
  legend: [
    ['leader', 'also produces the devnet'],
    ['member', 'member node'],
  ],
  sources: [
    { repo: 'yano-x', path: 'scripts/appchain-cluster/cluster.sh',
      anchors: ['HTTP_BASE="${YANO_CLUSTER_HTTP_BASE:-7070}"', 'SERVER_BASE="${YANO_CLUSTER_SERVER_BASE:-13337}"',
        'http_port()   { echo $(( HTTP_BASE + $1 )); }', 'server_port() { echo $(( SERVER_BASE + $1 )); }',
        '"-Dquarkus.http.host=127.0.0.1"', 'unavailable; using', 'app transport: shared (default'] },
    { repo: 'yano-x', path: 'examples/showcase/src/main/showcase/showcase.sh',
      anchors: ['HTTP_BASE=7070', 'SERVER_BASE=13337', '--http-base "$HTTP_BASE" --server-base "$SERVER_BASE"'] },
    { repo: 'yano-x', path: `${CLI}/TryCommand.java`,
      anchors: ['Ports.firstFree(13337, used)', 'Ports.firstFree(8080, used)'] },
    { repo: 'yano-x', path: `${CLI}/RequestCommand.java`,
      anchors: ['[--node-port 13337] [--http-port 8080]'] },
    { repo: 'yano-x', path: `${CLI}/NodeConfig.java`,
      anchors: ['local.put("quarkus.http.host", loopback ? "127.0.0.1" : "0.0.0.0");'] },
    { repo: 'yano', path: 'docs/APP_CHAIN_CONSENSUS_GUIDE.md',
      anchors: ['per-chain dispatch by chain-id', 'one TCP connection per peer pair'] },
  ],
};
