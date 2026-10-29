package org.yanoproject.x.appchain.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.yanoproject.x.appchain.formats.Draft;
import org.yanoproject.x.appchain.formats.FormatException;
import org.yanoproject.x.appchain.formats.MemberCard;
import org.yanoproject.x.appchain.formats.SealedApp;
import org.yanoproject.x.devtools.AppChainSealResolver;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@code seal}: checks the member cards beside the draft, resolves every chain once through the release's
 * catalog, and writes {@code <app>.chain}. Without every expected card it reports who is missing (exit 3).
 */
final class SealCommand {
    static final String USAGE = "yano.sh appchain seal <app>.draft.yaml [<card>.member ...] [--output <dir>]"
            + " [--force]";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Set<String> RESERVED = Set.of("chain-id", "members", "threshold", "signing-key", "peers",
            "state-machine", "state.genesis-id", "state.format-fingerprint", "state.commitment-profile");

    private final Terminal terminal;
    private final Distribution distribution;

    SealCommand(Terminal terminal, Distribution distribution) {
        this.terminal = terminal;
        this.distribution = distribution;
    }

    int run(String[] arguments) throws IOException {
        Args args = new Args(arguments, Set.of("output"), Set.of("force", "yes"));
        if (args.positional(0) == null) throw CliException.usage("Name the draft. Usage: " + USAGE);
        Path draftFile = Path.of(args.positional(0)).toAbsolutePath().normalize();
        Path directory = draftFile.getParent();
        Draft draft = Draft.parse(NodeDir.read(draftFile));
        Map<String, MemberCard> cards = collect(draft, directory,
                args.positionals().subList(1, args.positionals().size()));

        List<String> missing = draft.operators().stream().filter(name -> !cards.containsKey(name)).toList();
        if (!missing.isEmpty()) {
            terminal.say("Members " + cards.size() + "/" + draft.operators().size() + ": waiting for "
                    + String.join(", ", missing));
            terminal.next("ask " + String.join(", ", missing) + " to run  yano.sh appchain request "
                    + draftFile.getFileName() + "  and send the card; put it in " + directory);
            return CliException.NOT_READY;
        }

        List<String> keys = draft.operators().stream().map(name -> cards.get(name).publicKey()).toList();
        int members = keys.size();
        String finality = finality(draft.threshold(), members);
        Map<String, JsonNode> composites = new HashMap<>();
        List<AppChainSealResolver.ChainRequest> requests = new ArrayList<>();
        for (Draft.Chain chain : draft.chains()) {
            JsonNode composite = composite(chain, directory);
            if (composite != null) composites.put(chain.id(), composite);
            requests.add(new AppChainSealResolver.ChainRequest(chain.id(), chain.recipe(), chain.capabilities(),
                    chain.answers(), composite, chain.authenticatedMap()));
        }
        List<String> warnings = new ArrayList<>();
        for (Draft.Chain chain : draft.chains()) {
            AppChainSealResolver.MapFindings findings =
                    AppChainSealResolver.authenticatedMapFindings(chain.authenticatedMap());
            if (!findings.errors().isEmpty()) {
                throw CliException.failure("Chain " + chain.id() + ": " + String.join("; ", findings.errors()));
            }
            findings.warnings().forEach(warning -> warnings.add("Chain " + chain.id() + ": " + warning));
        }
        Map<String, Map<String, String>> overrides = new HashMap<>();
        String devnetTtl = "devnet".equals(draft.network()) ? Long.toString(devnetAnchorValidity(distribution)) : null;
        for (Draft.Chain chain : draft.chains()) overrides.put(chain.id(), overrides(draft, chain, cards, devnetTtl));

        AppChainSealResolver.SealHooks hooks = new AppChainSealResolver.SealHooks() {
            @Override
            public Map<String, String> overrides(String chainId) {
                return overrides.getOrDefault(chainId, Map.of());
            }

            @Override
            public String genesisId(String chainId, Map<String, String> settings, JsonNode composite) {
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("settings", new TreeMap<>(settings));
                if (composite != null) input.put("composite", composite);
                return SealedApp.genesisId(draft.invite(), chainId, input);
            }
        };
        List<AppChainSealResolver.ResolvedChain> resolved;
        try {
            resolved = AppChainSealResolver.resolve(new AppChainSealResolver.Request(draft.name(), draft.network(),
                    distribution.yanoVersion(), finality, draft.membership(), draft.sequencing(), keys, requests,
                    distribution.plugins(), directory, hooks));
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw CliException.failure("The draft cannot be sealed: " + invalid.getMessage());
        }

        Map<String, String> global = new TreeMap<>();
        List<SealedApp.ChainGenesis> chains = new ArrayList<>();
        for (AppChainSealResolver.ResolvedChain chain : resolved) {
            chain.globalSettings().forEach((key, value) -> {
                String prior = global.putIfAbsent(key, value);
                if (prior != null && !prior.equals(value)) {
                    throw CliException.failure("Chains need different values of node-wide setting " + key
                            + "; put them in separate applications");
                }
            });
            chains.add(new SealedApp.ChainGenesis(chain.chainId(), chain.recipe(), chain.settings(),
                    chain.nodeSettings(), chain.bundles()));
        }
        List<SealedApp.Member> roster = draft.operators().stream()
                .map(name -> new SealedApp.Member(name, cards.get(name).publicKey())).toList();
        SealedApp.Genesis genesis = new SealedApp.Genesis(draft.name(), draft.invite(), draft.network(),
                distribution.yanoVersion(), distribution.yanoXVersion(), draft.lead(), draft.membership(),
                resolved.getFirst().threshold(), roster, distribution.bundleDigests(), global, chains);
        Map<String, SealedApp.Peer> peers = new TreeMap<>();
        cards.forEach((name, card) -> peers.put(name, new SealedApp.Peer(card.node(), card.http())));
        SealedApp sealed = new SealedApp(SealedApp.FORMAT, genesis, peers);

        Path output = args.path("output", directory).resolve(draft.name() + ".chain");
        if (Files.isRegularFile(output)) {
            SealedApp existing = SealedApp.parse(NodeDir.read(output));
            if (existing.fingerprint().equals(sealed.fingerprint())) {
                terminal.ok(output + " is already sealed with these contents");
                Show.sealed(terminal, existing);
                return 0;
            }
            if (!args.flag("force")) {
                throw CliException.failure(output + " already holds a different sealed application ("
                        + existing.displayFingerprint() + "); pass --force only if no operator has started it");
            }
        }
        NodeDir.write(output, sealed.toJson());
        terminal.ok("Sealed " + output);
        Show.sealed(terminal, sealed);
        warnings.forEach(terminal::warn);
        for (SealedApp.ChainGenesis chain : chains) {
            String machine = chain.settings().get("state-machine");
            if (!"role-approvals".equals(machine) && !"role-evidence".equals(machine)) continue;
            Path plan = output.resolveSibling(chain.id() + ".role-plan.yaml");
            if (!Files.exists(plan)) {
                NodeDir.write(plan, AppChainSealResolver.roleBootstrapTemplate(chain.id(), machine,
                        genesis.threshold()));
            }
            terminal.say("  " + chain.id() + " starts with no actors: fill in " + plan.getFileName()
                    + " and, once live, run  yano.sh appchain role bootstrap --plan " + plan.getFileName());
        }
        terminal.next("send " + output.getFileName() + " to every operator. On a call, read out the chain "
                + "fingerprint and each operator's key id;",
                "each operator then runs  yano.sh appchain join " + output.getFileName()
                        + "  and  yano.sh appchain start");
        return 0;
    }

    /** Cards named on the command line, else every {@code *.member} beside the draft. */
    private Map<String, MemberCard> collect(Draft draft, Path directory, List<String> named) throws IOException {
        List<Path> files = new ArrayList<>();
        if (named.isEmpty()) {
            try (var listing = Files.list(directory)) {
                listing.filter(path -> path.toString().endsWith(".member")).sorted().forEach(files::add);
            }
        } else {
            named.forEach(name -> files.add(Path.of(name)));
        }
        Map<String, MemberCard> cards = new LinkedHashMap<>();
        Map<String, String> keys = new HashMap<>();
        for (Path file : files) {
            MemberCard card;
            try {
                card = MemberCard.parse(NodeDir.read(file)).verifiedFor(draft.invite());
            } catch (FormatException invalid) {
                throw CliException.failure(file.getFileName() + ": " + invalid.getMessage());
            }
            if (!draft.operators().contains(card.name())) {
                throw CliException.failure(file.getFileName() + " is for '" + card.name() + "', who is not in "
                        + "the draft's operators (" + String.join(", ", draft.operators()) + ")");
            }
            if (cards.containsKey(card.name()) && !cards.get(card.name()).equals(card)) {
                throw CliException.failure("Two different cards for " + card.name() + "; keep only the right one");
            }
            String other = keys.putIfAbsent(card.publicKey(), card.name());
            if (other != null && !other.equals(card.name())) {
                throw CliException.failure(card.name() + " and " + other + " use the same key; each operator needs "
                        + "their own node directory");
            }
            cards.put(card.name(), card);
        }
        return cards;
    }

    private static String finality(String threshold, int members) {
        int wanted = Draft.thresholdFor(threshold, members);
        for (String policy : Draft.THRESHOLDS) {
            if (Draft.thresholdFor(policy, members) == wanted) return policy;
        }
        return Integer.toString(wanted);
    }

    private static Map<String, String> overrides(Draft draft, Draft.Chain chain, Map<String, MemberCard> cards,
                                                 String devnetTtl) {
        Map<String, String> values = new TreeMap<>();
        if (!"devnet".equals(draft.network())) values.put("l1.stability-depth", "36");
        if (devnetTtl != null) values.put("anchor.validity-slots", devnetTtl);
        if (chain.anchor() != null) {
            values.put("anchor.mode", chain.anchor().mode());
            values.put("anchor.every-blocks", Integer.toString(chain.anchor().everyBlocks()));
        }
        for (String key : chain.settings().keySet()) {
            if (RESERVED.contains(key) || key.startsWith("sequencer.") || key.startsWith("membership.")
                    || key.startsWith("consensus.")) {
                throw CliException.failure("Chain " + chain.id() + ": setting " + key + " is set by seal (from the "
                        + "draft's operators, threshold, sequencing, membership or the chain's identity) and cannot "
                        + "be set under settings:");
            }
            if (key.startsWith("topics.")) {
                throw CliException.failure("Chain " + chain.id() + ": set topic policies in the chain's topics: "
                        + "block, not under settings:");
            }
        }
        values.putAll(chain.settings());
        if (chain.topics() != null) {
            // The resolver checks the complete seed, host defaults included, with the host's own parser.
            values.putAll(topicSettings(chain.topics(), cards));
        }
        if ("true".equals(values.get("anchor.enabled"))) {
            throw CliException.failure("Chain " + chain.id() + ": anchoring is turned on later with "
                    + "`yano.sh appchain anchor`, not in the draft");
        }
        return values;
    }

    /**
     * A devnet anchor's validity in slots. The public-network default (7200) reaches past the fast devnet's
     * forecast horizon of 3k/f slots, where the ledger cannot evaluate a script anchor; stay inside it, capped at
     * about a minute of the stock 0.2-second devnet. Draft settings can still choose a value.
     */
    static long devnetAnchorValidity(Distribution distribution) {
        Path template = distribution.devnetGenesisTemplate();
        if (!Files.isRegularFile(template)) return 299;
        try {
            JsonNode genesis = new ObjectMapper().readTree(template.toFile());
            long k = genesis.path("securityParam").asLong(0);
            BigDecimal f = genesis.path("activeSlotsCoeff").decimalValue();
            if (k < 1 || f.signum() <= 0 || f.compareTo(BigDecimal.ONE) > 0) {
                throw CliException.failure("The devnet genesis has no usable securityParam/activeSlotsCoeff");
            }
            long horizon = BigDecimal.valueOf(3 * k).divide(f, 0, RoundingMode.FLOOR).longValue();
            return Math.max(1, Math.min(299, horizon - 1));
        } catch (IOException unreadable) {
            throw CliException.failure("Cannot read " + template + ": " + unreadable.getMessage());
        }
    }

    /** The core genesis seed of a chain's topics: block; registrars named by operator become their member keys. */
    static Map<String, String> topicSettings(Draft.Topics topics, Map<String, MemberCard> cards) {
        Map<String, String> values = new TreeMap<>();
        values.put("topics.mode", "enabled");
        values.put("topics.creation.governance", "true");
        values.put("topics.creation.open-claims", Boolean.toString("anyone".equals(topics.create())));
        if (!topics.registrars().isEmpty()) {
            List<String> keys = topics.registrars().stream()
                    .map(registrar -> cards.containsKey(registrar) ? cards.get(registrar).publicKey() : registrar)
                    .sorted().toList();
            if (keys.stream().distinct().count() != keys.size()) {
                throw CliException.failure("A registrar is listed twice, by name and by key");
            }
            values.put("topics.creation.registrars.keys", String.join(",", keys));
            values.put("topics.creation.registrars.threshold", Integer.toString(topics.registrarThreshold()));
        }
        values.put("topics.unregistered-topics", topics.unregistered());
        topics.limits().forEach((name, value) -> values.put("topics." + name, Long.toString(value)));
        return values;
    }

    private static JsonNode composite(Draft.Chain chain, Path directory) throws IOException {
        JsonNode document = chain.composite();
        if (chain.bindings() != null) {
            Path file = directory.resolve(chain.bindings()).normalize();
            if (!file.startsWith(directory)) {
                throw CliException.failure("Chain " + chain.id() + ": the bindings file must be inside the draft's "
                        + "directory, because seal copies it into the shared sealed file");
            }
            if (!Files.isRegularFile(file)) {
                throw CliException.failure("Chain " + chain.id() + ": bindings file " + file + " not found");
            }
            document = YAML.readTree(file.toFile());
        }
        if (document != null && document.has("composite") && document.size() == 1) document = document.get("composite");
        return document;
    }
}
