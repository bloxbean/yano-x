package org.yanoproject.x.appchain.cli;

import com.fasterxml.jackson.databind.JsonNode;
import org.yanoproject.x.appchain.formats.Canonical;
import org.yanoproject.x.appchain.formats.Draft;
import org.yanoproject.x.appchain.formats.MemberCard;
import org.yanoproject.x.appchain.formats.SealedApp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code status}: where an application is and what to do next. For a draft it counts member cards; for a
 * joined application it reads every member's public API.
 */
final class StatusCommand {
    static final String USAGE = "yano.sh appchain status [<app>|<app>.draft.yaml] [--node-dir yano-node] [--json]"
            + " [--wait live --timeout <seconds>]";
    static final List<String> STAGES = List.of("Draft", "Members", "Sealed", "Starting", "Live");

    private final Terminal terminal;

    StatusCommand(Terminal terminal) {
        this.terminal = terminal;
    }

    /** One member as this node sees it. */
    record MemberView(String name, String keyId, String http, boolean online, Map<String, JsonNode> chains) {
    }

    /** The status of one joined application. */
    record AppView(String name, String fingerprint, String stage, String waitingFor, List<MemberView> members,
                   List<Map<String, Object>> chains, String cardano, String plugins, List<String> next,
                   List<String> problems) {
    }

    int run(String[] arguments) throws IOException {
        Args args = new Args(arguments, Set.of("node-dir", "wait", "timeout"), Set.of("json", "yes"));
        args.maxPositionals(1, USAGE);
        String target = args.positional(0);
        if (target != null && target.endsWith(".draft.yaml")) return draft(Path.of(target), args.flag("json"));
        NodeDir node = new NodeDir(args.path("node-dir", Path.of(NodeDir.DEFAULT)));
        String wait = args.value("wait");
        if (wait != null && !"live".equals(wait)) throw CliException.usage("--wait accepts only 'live'");
        long deadline = System.currentTimeMillis() + 1000L * (args.integer("timeout") == null ? 600
                : args.integer("timeout"));
        while (true) {
            SealedApp app = target == null ? node.onlyApp() : node.app(target);
            AppView view = view(node, app);
            boolean done = wait == null || "Live".equals(view.stage()) || !view.problems().isEmpty();
            if (done || System.currentTimeMillis() > deadline) {
                if (args.flag("json")) {
                    terminal.out().print(Canonical.pretty(view));
                } else {
                    print(view);
                }
                if (!view.problems().isEmpty()) return CliException.FAILURE;
                return wait != null && !"Live".equals(view.stage()) ? CliException.NOT_READY : 0;
            }
            Devnet.sleep(3000);
        }
    }

    AppView view(NodeDir node, SealedApp app) {
        SealedApp.Genesis genesis = app.genesis();
        NodeDir.Identity self = node.requireIdentity();
        NodeDir.Local local = node.local(genesis.name());
        Map<String, String> http = new LinkedHashMap<>();
        Map<String, String> keys = new LinkedHashMap<>();
        genesis.members().forEach(member -> keys.put(member.name(), member.publicKey()));
        app.peers().forEach((name, peer) -> http.put(name, peer.http()));
        if (local != null) {
            for (String json : local.extraMembers()) {
                MemberCard card = MemberCard.parse(json);
                keys.put(card.name(), card.publicKey());
                http.put(card.name(), card.http());
            }
            local.removedMembers().forEach(name -> {
                keys.remove(name);
                http.remove(name);
            });
        }
        keys.putIfAbsent(self.name(), self.publicKey());
        http.put(self.name(), "http://127.0.0.1:" + self.httpPort());

        List<MemberView> members = new ArrayList<>();
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            NodeClient client = new NodeClient(http.get(entry.getKey()), null);
            Map<String, JsonNode> chains = new LinkedHashMap<>();
            boolean online = client.ready();
            if (online) {
                for (SealedApp.ChainGenesis chain : genesis.chains()) {
                    client.get("/api/v1/app-chain/chains/" + chain.id() + "/status")
                            .ifPresent(status -> chains.put(chain.id(), status));
                }
            }
            members.add(new MemberView(entry.getKey(), Show.keyId(entry.getValue()), http.get(entry.getKey()),
                    online, chains));
        }

        List<String> problems = new ArrayList<>();
        List<Map<String, Object>> chains = new ArrayList<>();
        boolean waitingApproval = false;
        for (SealedApp.ChainGenesis chain : genesis.chains()) {
            Set<String> identities = new TreeSet<>();
            Set<String> roots = new TreeSet<>();
            long tip = 0;
            JsonNode mine = null;
            for (MemberView member : members) {
                JsonNode status = member.chains().get(chain.id());
                if (status == null) continue;
                identities.add(status.path("stateCommitment").path("genesisId").asText());
                roots.add(status.path("tipHeight").asLong() + ":" + status.path("stateRoot").asText());
                tip = Math.max(tip, status.path("tipHeight").asLong());
                if (member.name().equals(self.name())) mine = status;
            }
            if (identities.size() > 1) {
                problems.add("Chain " + chain.id() + ": members report different genesis identities; one of them "
                        + "joined a different sealed file");
            }
            Map<String, Set<String>> rootsByHeight = new java.util.HashMap<>();
            for (String entry : roots) {
                rootsByHeight.computeIfAbsent(entry.substring(0, entry.indexOf(':')), height -> new TreeSet<>())
                        .add(entry.substring(entry.indexOf(':') + 1));
            }
            rootsByHeight.forEach((height, values) -> {
                if (values.size() > 1) {
                    problems.add("Chain " + chain.id() + ": members report different state roots at height " + height
                            + "; stop and compare their logs before accepting more traffic");
                }
            });
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("chain", chain.id());
            summary.put("recipe", chain.recipe());
            String topics = Show.topics(genesis, chain.settings());
            if (topics != null) summary.put("topics", topics);
            summary.put("tipHeight", tip);
            summary.put("agree", identities.size() <= 1);
            if (mine != null) {
                summary.put("members", mine.path("membershipActiveMembers").asInt());
                summary.put("threshold", mine.path("membershipActiveThreshold").asInt());
                if (!mine.path("memberActiveForNextBlock").asBoolean(true)) waitingApproval = true;
                summary.put("anchoring", anchoring(members, chain.id()));
            }
            chains.add(summary);
        }

        MemberView me = members.stream().filter(member -> member.name().equals(self.name())).findFirst().orElseThrow();
        List<String> offline = members.stream().filter(member -> !member.online()).map(MemberView::name).toList();
        // A node that is up but has not loaded a chain (joined without a restart) is not running that chain yet.
        List<String> notLoaded = members.stream().filter(MemberView::online)
                .filter(member -> genesis.chains().stream().anyMatch(chain -> !member.chains().containsKey(chain.id())))
                .map(MemberView::name).toList();
        String stage;
        String waitingFor = null;
        List<String> next = new ArrayList<>();
        if (!me.online()) {
            stage = Processes.running(node) ? "Starting" : "Sealed";
            next.add(Processes.running(node) ? "wait for this node to finish starting, then run status again"
                    : "yano.sh appchain start");
        } else if (waitingApproval) {
            stage = "Starting";
            waitingFor = "approval";
            next.add("this node waits for approval: each existing operator runs  yano.sh appchain approve "
                    + self.name() + ".member");
        } else if (!notLoaded.isEmpty()) {
            stage = "Starting";
            waitingFor = String.join(", ", notLoaded) + " to load every chain";
            next.add(String.join(", ", notLoaded) + " restarts their node so it loads the new chains:  yano.sh appchain "
                    + "stop  then  yano.sh appchain start");
        } else if (!offline.isEmpty()) {
            stage = "Starting";
            waitingFor = String.join(", ", offline);
            boolean leadMissing = "devnet".equals(genesis.network()) && offline.contains(genesis.lead());
            next.add(leadMissing ? "ask the lead (" + genesis.lead() + ") to run  yano.sh appchain start  first"
                    : "ask " + String.join(", ", offline) + " to run  yano.sh appchain start");
        } else {
            stage = "Live";
            boolean anchored = chains.stream().anyMatch(chain -> !"not set up".equals(chain.get("anchoring")));
            next.add(anchored ? "submit messages to a chain, e.g. POST " + me.http() + "/api/v1/app-chain/chains/"
                    + genesis.chains().getFirst().id() + "/messages"
                    : "optional: anchor a chain to Cardano with  yano.sh appchain anchor " + genesis.name());
        }
        return new AppView(genesis.name(), app.displayFingerprint(), stage, waitingFor, members, chains,
                cardano(node, genesis.network(), me.online()), plugins(node, me.online(), genesis.chains()), next,
                problems);
    }

    /**
     * This node's Cardano view: on a public network how far behind the network it is; on a devnet the hash of the
     * stored genesis, which every member compares (the runtime does not report it).
     */
    private static String cardano(NodeDir node, String network, boolean online) {
        String block = "";
        long behindSeconds = -1;
        if (online) {
            JsonNode latest = NodeClient.local(node).get("/api/v1/blocks/latest").orElse(null);
            if (latest != null) {
                block = "block " + latest.path("height").asLong();
                behindSeconds = System.currentTimeMillis() / 1000 - latest.path("time").asLong();
            }
        }
        if ("devnet".equals(network)) {
            String genesis = java.nio.file.Files.isRegularFile(Devnet.genesis(node))
                    ? "genesis " + Distribution.sha256(Devnet.genesis(node)).substring(0, 16)
                    : "genesis not downloaded yet";
            return "devnet " + (block.isEmpty() ? "" : block + ", ") + genesis + " (compare with the other members)";
        }
        if (block.isEmpty()) return network + ": unknown until this node runs";
        return network + " " + block + (behindSeconds > 600 ? ", syncing: about " + duration(behindSeconds)
                + " behind" : ", up to date");
    }

    /** This node's plugin catalog fingerprint, which every member compares (the identity route is private). */
    private static String plugins(NodeDir node, boolean online, List<SealedApp.ChainGenesis> chains) {
        if (!online) return "unknown until this node runs";
        return NodeClient.local(node).get("/api/v1/app-chain/chains/" + chains.getFirst().id() + "/identity")
                .map(identity -> identity.path("pluginCatalogFingerprint").asText("unknown"))
                .map(value -> value.length() > 23 ? value.substring(0, 23) + "…" : value)
                .map(value -> "catalog " + value + " (compare with the other members)").orElse("unknown");
    }

    private static String duration(long seconds) {
        if (seconds >= 2 * 86400) return seconds / 86400 + " days";
        if (seconds >= 7200) return seconds / 3600 + " hours";
        return Math.max(1, seconds / 60) + " minutes";
    }

    /** Whether a member's anchor status says it leads; in script mode co-signers report enabled too. */
    static boolean leads(JsonNode anchor) {
        if (anchor.has("leader")) return anchor.path("leader").asBoolean(false);
        return anchor.path("enabled").asBoolean(false);
    }

    private static String anchoring(List<MemberView> members, String chain) {
        for (MemberView member : members) {
            JsonNode status = member.chains().get(chain);
            if (status == null) continue;
            JsonNode anchor = status.path("anchor");
            if (leads(anchor)) {
                long count = anchor.path("anchoredCount").asLong(0);
                return "led by " + member.name() + (count > 0 ? ", " + count + " anchored (last height "
                        + anchor.path("lastAnchoredHeight").asLong() + ")" : " (waiting for the first anchor)");
            }
        }
        return "not set up";
    }

    private void print(AppView view) {
        StringBuilder ladder = new StringBuilder("  ");
        for (String stage : STAGES) {
            if (ladder.length() > 2) ladder.append(" ─ ");
            ladder.append(stage.equals(view.stage()) ? "[" + stage + "]" : stage);
        }
        terminal.say(view.name() + "  chain fingerprint " + view.fingerprint());
        terminal.say(ladder.toString());
        long online = view.members().stream().filter(MemberView::online).count();
        terminal.say("  members online " + online + "/" + view.members().size()
                + (view.waitingFor() == null ? "" : "; waiting for " + view.waitingFor()));
        for (MemberView member : view.members()) {
            terminal.say(String.format(java.util.Locale.ROOT, "    %-12s key %s  %s  %s", member.name(),
                    member.keyId(), member.online() ? "online " : "offline", member.http()));
        }
        for (Map<String, Object> chain : view.chains()) {
            terminal.say(String.format(java.util.Locale.ROOT, "  chain %-14s height %-6s %s  members %s, %s "
                            + "signatures  anchoring: %s", chain.get("chain"), chain.get("tipHeight"),
                    Boolean.TRUE.equals(chain.get("agree")) ? "members agree" : "MISMATCH",
                    chain.getOrDefault("members", "?"), chain.getOrDefault("threshold", "?"),
                    chain.getOrDefault("anchoring", "?")));
            if (chain.containsKey("topics")) terminal.say("        topics: " + chain.get("topics"));
        }
        terminal.say("  Cardano: " + view.cardano());
        terminal.say("  Plugins: " + view.plugins());
        view.problems().forEach(problem -> terminal.warn(problem));
        terminal.next(view.next().toArray(String[]::new));
    }

    private int draft(Path file, boolean json) throws IOException {
        Draft draft = Draft.parse(NodeDir.read(file));
        Path directory = file.toAbsolutePath().getParent();
        Map<String, String> cards = new LinkedHashMap<>();
        try (var listing = Files.list(directory)) {
            for (Path path : listing.filter(item -> item.toString().endsWith(".member")).sorted().toList()) {
                try {
                    MemberCard card = MemberCard.parse(NodeDir.read(path)).verifiedFor(draft.invite());
                    cards.put(card.name(), Show.keyId(card.publicKey()));
                } catch (RuntimeException invalid) {
                    terminal.warn(path.getFileName() + ": " + invalid.getMessage());
                }
            }
        }
        List<String> missing = draft.operators().stream().filter(name -> !cards.containsKey(name)).toList();
        Path sealed = directory.resolve(draft.name() + ".chain");
        String stage = Files.isRegularFile(sealed) ? "Sealed" : missing.isEmpty() ? "Members" : "Draft";
        if (json) {
            terminal.out().print(Canonical.pretty(Map.of("name", draft.name(), "stage", stage, "cards", cards,
                    "missing", missing)));
            return 0;
        }
        terminal.say(draft.name() + "  invite " + draft.invite());
        terminal.say("  members " + cards.size() + "/" + draft.operators().size()
                + (missing.isEmpty() ? "" : "; waiting for " + String.join(", ", missing)));
        cards.forEach((name, keyId) -> terminal.say("    " + name + "  key " + keyId));
        if ("Sealed".equals(stage)) {
            terminal.next("send " + sealed.getFileName() + " to every operator; each runs  yano.sh appchain join "
                    + sealed.getFileName());
        } else if (missing.isEmpty()) {
            terminal.next("yano.sh appchain seal " + file.getFileName());
        } else {
            terminal.next("ask " + String.join(", ", missing) + " to run  yano.sh appchain request "
                    + file.getFileName() + "  and send the card");
        }
        return 0;
    }
}
