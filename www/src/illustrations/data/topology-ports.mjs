// Ports by deployment shape: the single-host launcher (and the showcase that
// drives it), a generated project on one machine, and a generated project
// with one host per member. Facts come from cluster.sh, showcase.sh, the
// project renderer, and Yano's consensus guide.

const LAUNCHER = '`./yano.sh appchain cluster` and the showcase put node i on HTTP 7070 + i and node-to-node port '
  + '13337 + i, listening on 127.0.0.1. The launcher moves a busy default range and prints the new one; the '
  + 'showcase passes its bases explicitly, so a busy range stops it. Change them with `--http-base` and `--server-base`.';
const PROJECT = 'A generated host project on one machine puts node i on HTTP 8080 + i and node-to-node port 13337 + i, '
  + 'listening on 127.0.0.1. Choose other bases with `init --http-port-base` and `--server-port-base`. With '
  + '`--deployment docker-compose`, each container listens on 8080 and 13337, published on the host as 8080 + i and '
  + '13337 + i. The launcher also starts at 13337, so stop its cluster first or choose other bases.';
const HOSTS = 'When `appchain.yaml` lists one host per member, every node uses HTTP 8080 and node-to-node port 13337 on '
  + 'its own host and listens on all interfaces. Its peers are the other hosts on 13337. Open these ports only to '
  + 'the machines and clients that need them.';

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
    { id: 'project', kind: 'ledger', label: 'Project, one machine', contains: ['p0', 'p1', 'p2'] },
    { id: 'hosts', kind: 'external', label: 'Project, one host each', contains: ['h0', 'h1', 'h2'] },
  ],
  blocks: [
    node('l0', 'node 0', 'http 7070 · n2n 13337', 'leader', `${LAUNCHER} Node 0 also produces the private devnet.`),
    node('l1', 'node 1', 'http 7071 · n2n 13338', 'member', LAUNCHER),
    node('l2', 'node 2', 'http 7072 · n2n 13339', 'member', LAUNCHER),
    node('p0', 'node 0', 'http 8080 · n2n 13337', 'member', PROJECT),
    node('p1', 'node 1', 'http 8081 · n2n 13338', 'member', PROJECT),
    node('p2', 'node 2', 'http 8082 · n2n 13339', 'member', PROJECT),
    node('h0', 'node 0', 'http 8080 · n2n 13337', 'member', HOSTS),
    node('h1', 'node 1', 'http 8080 · n2n 13337', 'member', HOSTS),
    node('h2', 'node 2', 'http 8080 · n2n 13337', 'member', HOSTS),
    {
      id: 'chains', label: 'Every chain on a node shares its two ports', sub: 'REST: /api/v1/app-chain/chains/{chainId}/…',
      kind: 'core',
      detail: 'A node has one HTTP API for all its chains; the path carries the chain id, for example '
        + '`/api/v1/app-chain/chains/orders-chain/messages`. By default, chain traffic between members also shares one '
        + 'node-to-node connection per pair of nodes.',
    },
  ],
  layouts: {
    wide: {
      width: 760,
      height: 388,
      zones: {
        launcher: [16, 16, 232, 284],
        project: [264, 16, 232, 284],
        hosts: [512, 16, 232, 284],
      },
      blocks: {
        l0: [32, 56, 200, 56],
        l1: [32, 128, 200, 56],
        l2: [32, 200, 200, 56],
        p0: [280, 56, 200, 56],
        p1: [280, 128, 200, 56],
        p2: [280, 200, 200, 56],
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
        ['p0', 'http 8080\nn2n 13337'], ['p1', 'http 8081\nn2n 13338'], ['p2', 'http 8082\nn2n 13339'],
        ['h0', 'http 8080\nn2n 13337'], ['h1', 'http 8080\nn2n 13337'], ['h2', 'http 8080\nn2n 13337'],
      ].map(([id, sub]) => [id, { sub }]).concat([
        ['chains', { label: 'Every chain on a node\nshares its two ports', sub: 'REST: …/app-chain/chains/{chainId}/…' }],
      ])),
      zones: {
        launcher: [8, 8, 364, 112],
        project: [8, 136, 364, 112],
        hosts: [8, 264, 364, 112],
      },
      blocks: {
        l0: [20, 44, 108, 64],
        l1: [136, 44, 108, 64],
        l2: [252, 44, 108, 64],
        p0: [20, 172, 108, 64],
        p1: [136, 172, 108, 64],
        p2: [252, 172, 108, 64],
        h0: [20, 300, 108, 64],
        h1: [136, 300, 108, 64],
        h2: [252, 300, 108, 64],
        chains: [8, 392, 364, 76],
      },
    },
  },
  legend: [
    ['leader', 'node 0 also produces the devnet'],
    ['member', 'member node'],
  ],
  sources: [
    { repo: 'yano-x', path: 'scripts/appchain-cluster/cluster.sh',
      anchors: ['HTTP_BASE="${YANO_CLUSTER_HTTP_BASE:-7070}"', 'SERVER_BASE="${YANO_CLUSTER_SERVER_BASE:-13337}"',
        'http_port()   { echo $(( HTTP_BASE + $1 )); }', 'server_port() { echo $(( SERVER_BASE + $1 )); }',
        '"-Dquarkus.http.host=127.0.0.1"', 'unavailable; using', 'app transport: shared (default'] },
    { repo: 'yano-x', path: 'examples/showcase/src/main/showcase/showcase.sh',
      anchors: ['HTTP_BASE=7070', 'SERVER_BASE=13337', '--http-base "$HTTP_BASE" --server-base "$SERVER_BASE"'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/AppChainProjectRenderer.java',
      anchors: ['int httpBase = topology.httpPortBase() == null ? 8080 : topology.httpPortBase();',
        'int serverBase = topology.serverPortBase() == null ? 13337 : topology.serverPortBase();',
        ': distributed ? serverBase : serverBase + node;', ': distributed ? httpBase : httpBase + node;',
        'values.put("quarkus.http.host", distributed || compose || kubernetes ? "0.0.0.0" : "127.0.0.1");',
        '.append(httpBase + index).append(":8080', '.append(serverBase + index).append(":13337'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/AppChainProjectCli.java',
      anchors: ['--http-port-base <port>', 'same-machine HTTP base (default 8080)', '--server-port-base <port>',
        'same-machine n2n base (default 13337)'] },
    { repo: 'yano', path: 'docs/APP_CHAIN_CONSENSUS_GUIDE.md',
      anchors: ['per-chain dispatch by chain-id', 'one TCP connection per peer pair'] },
  ],
};
