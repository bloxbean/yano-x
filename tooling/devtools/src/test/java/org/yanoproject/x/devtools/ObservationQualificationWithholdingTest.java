package org.yanoproject.x.devtools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationQualificationWithholdingTest {
    private static final int BASE = 19337;

    @Test
    void requiresEveryDirectedLinkRatherThanJustConnectedPeerCounts() {
        var states = states(2);
        assertThat(ObservationQualificationWithholding.linksMatch(states, 2, BASE)).isTrue();
        assertThat(ObservationQualificationWithholding.linksMatch(states, 1, BASE)).isFalse();
        var peers = (ObjectNode) states.getFirst().path("peers");
        // Same connected count, but the withholder can still send on this directed link.
        peers.put("127.0.0.1:" + (BASE + 2), true);
        peers.put("127.0.0.1:" + (BASE + 1), false);
        assertThat(ObservationQualificationWithholding.linksMatch(states, 2, BASE)).isFalse();
    }

    @Test
    void rejectsBypassAddressesUnknownPeerFieldsAndInvalidRanges() {
        var states = states(-1);
        assertThat(ObservationQualificationWithholding.linksMatch(states, -1, BASE)).isTrue();
        assertThat(ObservationQualificationWithholding.linksMatch(states, -1, BASE + 1)).isFalse();
        assertThat(ObservationQualificationWithholding.linksMatch(states, -2, BASE)).isFalse();
        assertThat(ObservationQualificationWithholding.linksMatch(states, 5, BASE)).isFalse();
        assertThat(ObservationQualificationWithholding.linksMatch(states, -1, 65512)).isFalse();
        var peers = (ObjectNode) states.getFirst().path("peers");
        peers.remove("127.0.0.1:" + (BASE + 1));
        peers.put("127.0.0.1:18338", true);
        assertThat(ObservationQualificationWithholding.linksMatch(states, -1, BASE)).isFalse();
        peers.remove("127.0.0.1:18338");
        peers.put("127.0.0.1:" + (BASE + 1), "true");
        assertThat(ObservationQualificationWithholding.linksMatch(states, -1, BASE)).isFalse();
    }

    private static List<JsonNode> states(int isolated) {
        var json = new ObjectMapper();
        List<JsonNode> states = new ArrayList<>();
        for (int node = 0; node < 5; node++) {
            ObjectNode state = json.createObjectNode();
            ObjectNode peers = state.putObject("peers");
            for (int target = 0; target < 5; target++) {
                if (node != target) peers.put("127.0.0.1:" + (BASE + 5 * node + target),
                        node != isolated && target != isolated);
            }
            states.add(state);
        }
        return states;
    }
}
