package org.yanoproject.x.appchain.cli;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Waits for a governed change that needs other operators and new blocks, offering heartbeat messages when the
 * chain is idle. Shared by {@code approve} and {@code topics approve}.
 */
final class Await {
    private Await() {
    }

    /**
     * @param path      the chain's API path, for example {@code /api/v1/app-chain/chains/orders}
     * @param done      true once the chain status shows the change in effect
     * @param offer     whether heartbeats can make blocks on this chain at all
     * @param why       shown once while waiting, after "waiting for {@code what}"
     * @param timedOut  shown when the deadline passes, read then; the command is safe to run again
     * @return whether heartbeats are on, so the next wait does not ask again
     */
    static boolean until(Terminal terminal, NodeClient client, String path, String chain, Predicate<JsonNode> done,
                         String what, long deadline, boolean heartbeat, boolean offer, String why,
                         Supplier<String> timedOut) {
        boolean told = false;
        boolean asked = heartbeat || !offer;
        while (true) {
            JsonNode status = client.get(path + "/status").orElse(null);
            if (status != null && done.test(status)) return heartbeat;
            if (System.currentTimeMillis() > deadline) {
                throw CliException.notReady(chain + ": still waiting for " + what + ". " + timedOut.get());
            }
            if (!told) {
                terminal.say(chain + ": waiting for " + what + " (" + why + ")");
                told = true;
            }
            String machine = status == null ? "" : status.path("stateMachine").asText();
            String effect = Heartbeat.effect(machine);
            if (effect != null && !asked) {
                asked = true;
                if (terminal.interactive()) {
                    heartbeat = terminal.confirm(chain + " may be idle. Send heartbeat messages to make blocks? Each "
                            + "one " + effect, false);
                    if (!heartbeat) terminal.say("Then send ordinary traffic to " + chain + " so it makes blocks.");
                } else {
                    terminal.say(chain + " makes blocks only from messages. If it is idle, send traffic, or run "
                            + "again with --heartbeat (each heartbeat " + effect + ").");
                }
            }
            if (heartbeat && offer && effect != null) {
                try {
                    client.post(path + "/messages", Heartbeat.message(machine));
                } catch (CliException busy) {
                    // A refused heartbeat is retried on the next round.
                }
            }
            Devnet.sleep(2000);
        }
    }
}
