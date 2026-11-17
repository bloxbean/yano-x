# Yano X App-Chain Testkit

JUnit 5 extension for testing applications against an embedded, multi-node
app-chain cluster. Published as `org.yanoproject.x:yano-x-appchain-testkit`
(test scope). It replaces Yano's former `org.yanoproject:yano-appchain-core-testkit`;
the package moved from `org.yanoproject.appchain.testkit` to
`org.yanoproject.x.testkit`.

The testkit starts in-process nodes with:

- generated member keys
- temporary ledgers
- full-mesh socket connections
- node 0 as the sequencer
- configurable chain id, state machine, threshold, and block interval

## Usage

```java
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.x.testkit.AppChainCluster;
import org.yanoproject.x.testkit.AppChainClusterHandle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@AppChainCluster(nodes = 3)
class OrdersAppChainTest {

    @Test
    void ordersReplicate(AppChainClusterHandle cluster) throws Exception {
        String messageId = cluster.node(1)
                .submit("orders", "order-1".getBytes());

        cluster.awaitFinalized(messageId);

        assertThat(cluster.node(2)
                .stateProof(HexUtil.decodeHexString(messageId)))
                .isPresent();
    }
}
```

## Annotation Options

```java
@AppChainCluster(
    nodes = 3,
    chainId = "testkit-chain",
    stateMachine = "ordered-log",
    threshold = 0,
    blockIntervalMs = 300
)
```

- `nodes`: number of member nodes. Node 0 is the sequencer.
- `chainId`: app-chain id.
- `stateMachine`: built-in or ServiceLoader-provided state-machine id.
- `threshold`: finality threshold. `0` means all nodes must co-sign.
- `blockIntervalMs`: proposer tick interval.

## Cluster Handle

`AppChainClusterHandle` exposes:

- `node(index)`: an `AppChainGateway` for a node.
- `proposer()`: node 0.
- `nodes()`: all gateways.
- `memberPublicKeyHex(index)`: generated member key.
- `awaitFinalized(messageIdHex)`: wait for a message on all nodes.
- `awaitTip(height)`: wait for every node to reach a tip.

`AppChainTestProfiles` builds consensus profiles with typed effect limits, and
`AppChainTestStateCommitments` derives deterministic MPF state identities for
isolated test chains. Effect executor conformance lives in
[`sdk/effects-testkit`](../effects-testkit).

## Test

```bash
./gradlew :sdk:appchain-testkit:test
```

## Scope

The testkit is for application and state-machine tests. It is not a public
network simulator and does not model adversarial timing, Byzantine peers, L1
rollback, or long-running production load.
