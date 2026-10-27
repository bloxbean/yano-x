package org.yanoproject.x.devtools;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yanoproject.api.appchain.topics.TopicKeys;
import org.yanoproject.api.appchain.topics.TopicNames;
import org.yanoproject.api.appchain.topics.TopicPolicyProof;
import org.yanoproject.api.appchain.topics.TopicWire;
import org.yanoproject.api.appchain.topics.TopicWire.CommandStatement;
import org.yanoproject.api.appchain.topics.TopicWire.Signature;
import org.yanoproject.api.appchain.topics.TopicWireCodec;
import org.yanoproject.appchain.proof.TopicPolicyProofVerifier;
import org.yanoproject.x.client.Hex;
import org.yanoproject.x.client.TopicAuthoring;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Topic policy authoring, offline owner signing, and caller-pinned authorship verification. */
public final class TopicsCli {
    static final String USAGE = """
            Usage: yano.sh appchain topics init <namespace> [--owner-key-file <seed file>] [--output topics.yaml]
               or: yano.sh appchain topics public-key <seed file>
               or: yano.sh appchain topics plan <topics.yaml> [--output <name>.plan.json]
               or: yano.sh appchain topics sign <plan.json> --key-file <owner seed file> [--command <index>]
               or: yano.sh appchain topics submit <signed plan.json>
               or: yano.sh appchain topics approve <plan.json>     (each operator: a member vote from this node)
               or: yano.sh appchain topics resolve <topic> [--height <n>]
               or: yano.sh appchain topics delegate --scope <name> --session-key <64-hex>
                         --not-before <ms> --not-after <ms> --key-file <issuer seed file>
               or: yano.sh appchain topics verify <messageId> --bundle <json> --trusted-block <json>
               or: yano.sh appchain topics doctor --bindings <yaml>
            In a node directory (--node-dir, default ./yano-node) the commands use this node, its API key and,
            when the application has one chain, that chain. Elsewhere pass --peer <url> and --chain <id>;
            --api-key-env <name> reads an API credential from the named environment variable, and
            --snapshot snapshot.json plans offline. --json prints the machine-readable result of init, plan,
            sign, submit, resolve, delegate, verify and doctor instead. init picks the authority from the chain:
            a parent namespace's owners, or the chain's creation policy (governance, ~registrars or ~claim).
            Signing keys are 32-byte Ed25519 seed files (64 lowercase hex characters), never command arguments.
            Plans are immutable statements: expired/stale plans must be regenerated and signed again.
            Member votes are cast by every operator with topics approve; no tool votes on a member's behalf.
            """;

    /**
     * What a node directory supplies: this node's API URL and key and the application's only chain, any of which
     * may be {@code null}. {@code guided} writes result files under default names and prints what to do next
     * instead of the JSON result.
     */
    public record Defaults(String peer, String apiKey, String chain, boolean guided) {
        public static final Defaults NONE = new Defaults(null, null, null, false);
    }

    /**
     * One member vote of a governance plan: the revision it creates, and the owners it sets ({@code null} for a
     * retirement), so a caller can tell this change from another one that took the same revision.
     */
    public record Vote(String chainId, String generationId, String target, String operation, String commandHex,
                       long revision, long earliestActivation, JsonNode owners) {
    }

    /**
     * The revision each owner-signed command of a plan creates once accepted, by target; a revocation changes no
     * revision and is absent.
     */
    public static Map<String, Long> plannedRevisions(Path planFile) throws IOException {
        JsonNode plan = read(planFile.toString(), false);
        TopicPlan.Snapshot snapshot = TopicPlan.JSON.treeToValue(plan.get("snapshot"), TopicPlan.Snapshot.class);
        Map<String, Long> revisions = new LinkedHashMap<>();
        for (JsonNode command : plan.path("commands")) {
            if (!"registry".equals(command.path("mode").asText()) || "revoke".equals(command.path("operation")
                    .asText())) {
                continue;
            }
            String target = command.path("target").asText();
            var existing = snapshot.entries().get(target);
            revisions.put(target, Math.addExact(existing == null ? snapshot.freedHeads().getOrDefault(target, 0L)
                    : existing.head(), 1));
        }
        return revisions;
    }

    /** How far a member vote has come, from one node's view of the chain. */
    public enum Progress {
        /** The planned revision is active with the planned content. */
        IN_EFFECT,
        /** Another change holds the planned revision, or a later one is active: the plan is out of date. */
        SUPERSEDED,
        /** Enough members voted; the revision is queued and activates after the activation lag. */
        DECIDED,
        /** This member's vote is in the current tally; the others have not all voted yet. */
        VOTED,
        /** This member has not voted in the current tally. */
        OPEN
    }

    /** The state key of the governance tally, read through {@code /state/entry/<hex>}. */
    public static String governanceTallyKeyHex() {
        return Hex.encode(TopicKeys.governance());
    }

    /**
     * Where a vote stands. Voting again after quorum would start a second tally that re-applies the change, so a
     * caller votes only on {@link Progress#OPEN}.
     *
     * @param memberKey this member's public key (64 hex)
     * @param entry     the target's {@code /topics/entries} record, or {@code null} when it has none
     * @param tallyHex  the governance tally state value, or {@code null} when there is none
     * @param height    the chain height the entry and tally were read at
     * @param window    the configuration's governance window in blocks
     */
    public static Progress progress(Vote vote, String memberKey, JsonNode entry, String tallyHex, long height,
                                    long window) {
        if (entry != null) {
            JsonNode active = entry.path("active");
            boolean hasActive = active.isObject();
            long revision = hasActive ? active.path("revision").asLong() : 0;
            if (revision == vote.revision()) return planned(vote, active.path("content")) ? Progress.IN_EFFECT
                    : Progress.SUPERSEDED;
            if (revision > vote.revision()) return Progress.SUPERSEDED;
            if (entry.path("head").asLong() >= vote.revision()) return Progress.DECIDED;
        }
        if (tallyHex != null && !tallyHex.isEmpty()) {
            String hash = TopicWireCodec.blake2b256Hex(Hex.decode(vote.commandHex()));
            for (var approval : TopicWireCodec.decodeGovernancePending(Hex.decode(tallyHex))) {
                if (approval.commandHash().equals(hash) && height - approval.firstSeen() <= window
                        && approval.approvers().contains(memberKey.toLowerCase(Locale.ROOT))) {
                    return Progress.VOTED;
                }
            }
        }
        return Progress.OPEN;
    }

    private static boolean planned(Vote vote, JsonNode content) {
        if (vote.owners() == null) return content.path("retired").asBoolean(false);
        try {
            return TopicPlan.JSON.treeToValue(vote.owners(), TopicWire.KeySet.class)
                    .equals(TopicPlan.JSON.treeToValue(content.path("owners"), TopicWire.KeySet.class));
        } catch (IOException | IllegalArgumentException unreadable) {
            return false;
        }
    }

    private Defaults defaults = Defaults.NONE;

    /** Runs a topics command with node-directory defaults. */
    public int run(String[] args, PrintWriter out, PrintWriter err, Defaults defaults) {
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        return run(args, out, err);
    }

    /** The member votes of a plan file, after checking the plan against its canonical compilation. */
    public static List<Vote> votes(Path planFile) throws IOException {
        ObjectNode plan;
        try {
            plan = TopicPlan.validate(read(planFile.toString(), false));
        } catch (TopicPlan.Guidance guidance) {
            throw guidance;
        } catch (RuntimeException invalid) {
            // Parser messages may quote the file; only a TOPIC_ code is repeated.
            throw TopicPlan.guidance("it is malformed or was edited after planning"
                    + (invalid.getMessage() != null && invalid.getMessage().matches("TOPIC_[A-Z_]+")
                    ? " (" + invalid.getMessage() + ")" : ""));
        }
        TopicPlan.Snapshot snapshot = TopicPlan.JSON.treeToValue(plan.get("snapshot"), TopicPlan.Snapshot.class);
        List<Vote> votes = new ArrayList<>();
        for (JsonNode command : plan.path("commands")) {
            if (!"governance".equals(command.path("mode").asText())) continue;
            String target = command.path("target").asText();
            var existing = snapshot.entries().get(target);
            long revision = Math.addExact(existing == null ? snapshot.freedHeads().getOrDefault(target, 0L)
                    : existing.head(), 1);
            JsonNode source = null;
            for (JsonNode written : plan.path("document").path("commands")) {
                if (target.equals(written.path("target").asText())) source = written;
            }
            JsonNode owners = source == null || !source.has("owners") ? null : source.get("owners");
            votes.add(new Vote(snapshot.chainId(), snapshot.generationId(), target,
                    command.path("operation").asText(), command.path("commandHex").asText(), revision,
                    command.path("earliestActivation").asLong(0), owners));
        }
        if (votes.isEmpty()) throw TopicPlan.guidance("the plan has no member votes; sign and submit it instead");
        return List.copyOf(votes);
    }

    int run(String[] args, PrintWriter out, PrintWriter err) {
        try {
            if (args.length == 0 || Set.of("help", "--help", "-h").contains(args[0])
                    || Arrays.asList(args).contains("--help")) {
                out.println(USAGE);
                return AppChainDevtoolsCli.EXIT_OK;
            }
            Arguments arguments = parse(args);
            JsonNode result = switch (args[0]) {
                case "init" -> initialize(arguments);
                case "public-key" -> publicKey(arguments);
                case "plan" -> plan(arguments);
                case "sign" -> sign(arguments);
                case "submit" -> submit(arguments);
                case "resolve" -> resolve(arguments);
                case "delegate" -> delegate(arguments);
                case "verify" -> verify(arguments);
                case "doctor" -> doctor(arguments);
                default -> throw TopicPlan.invalid("unknown topics command");
            };
            String serialized = TopicPlan.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n";
            String output = arguments.options().get("output");
            if (defaults.guided()) {
                guide(args[0], arguments, result, serialized, out);
                return AppChainDevtoolsCli.EXIT_OK;
            }
            if (output != null && !args[0].equals("init")) {
                // Never replace an existing signed plan or secret by an accidentally reused path.
                Files.writeString(Path.of(output), serialized, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                out.println("Written " + Path.of(output).getFileName());
            } else out.print(serialized);
            return AppChainDevtoolsCli.EXIT_OK;
        } catch (IOException failure) {
            err.println(ioReason(failure));
            return AppChainDevtoolsCli.EXIT_IO;
        } catch (RuntimeException failure) {
            // In particular, a parser exception must never echo a seed file or arbitrary secret argument.
            err.println(safeReason(failure));
            return AppChainDevtoolsCli.EXIT_INVALID_CONFIG;
        } finally {
            out.flush();
            err.flush();
        }
    }

    private JsonNode initialize(Arguments args) throws IOException {
        args.only(Set.of("output", "owner-key-file", "peer", "chain", "api-key-env"), 1);
        String namespace = args.positionals().getFirst();
        if (!TopicNames.isNamespace(namespace)) throw TopicPlan.invalid("namespace must end in '/' and use valid segments");
        String chain = args.options().containsKey("chain") ? args.required("chain") : defaults.chain();
        String owner = "REPLACE_OWNER_PUBLIC_KEY";
        if (args.options().containsKey("owner-key-file")) {
            try (FileSigner signer = new FileSigner(Path.of(args.required("owner-key-file")))) {
                owner = signer.publicKey();
            }
        }
        // The authority follows how this chain creates the name: under an owned parent its owners sign; a
        // top-level name follows the chain's creation policy. Without a node, the member vote is the default.
        String authority = "governance";
        boolean online = chain != null && (args.options().containsKey("peer") || defaults.peer() != null);
        if (online) {
            TopicsPeer peer = peer(args, chain);
            JsonNode config = peer.get("/topics/config");
            if (!config.path("enabled").asBoolean(false)) {
                throw TopicPlan.guidance("chain " + chain + " has no topic registry; seal it with a topics: block");
            }
            List<String> names = TopicNames.applicableNames(namespace);
            for (String name : names.subList(0, names.size() - 1)) {
                JsonNode entry = peer.optional("/topics/entries/" + TopicsPeer.segment(name));
                if (entry != null && entry.path("active").isObject()) authority = name;
            }
            JsonNode settings = config.path("config").path("settings");
            if ("governance".equals(authority) && names.size() == 1) {
                if (settings.path("registrars").isObject()) authority = TopicWire.AUTHORITY_REGISTRARS;
                else if (settings.path("openClaims").asBoolean(false)) authority = TopicWire.AUTHORITY_CLAIM;
            }
        }
        ObjectNode document = TopicPlan.JSON.createObjectNode();
        document.put("schemaVersion", 1).put("kind", "Topics")
                .put("chainId", chain != null ? chain : "REPLACE_CHAIN_ID");
        ObjectNode command = document.putArray("commands").addObject();
        command.put("target", namespace).put("operation", "create").put("authority", authority);
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        command.put("nonce", Hex.encode(nonce));
        if (!"governance".equals(authority)) command.put("notAfter", System.currentTimeMillis() + 3_600_000);
        command.putObject("owners").put("threshold", 1).putArray("keys").add(owner);
        command.putObject("policy").put("writers", "open").put("schema", "none");
        Path path = Path.of(args.options().getOrDefault("output", "topics.yaml"));
        Files.writeString(path, TopicPlan.YAML.writeValueAsString(document), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        return TopicPlan.JSON.createObjectNode().put("written", path.toString()).put("kind", "Topics")
                .put("authority", authority).put("ownerKnown", !owner.startsWith("REPLACE"))
                .put("chainKnown", chain != null);
    }

    /** The public key of a seed file; the seed is never printed. */
    private JsonNode publicKey(Arguments args) throws IOException {
        args.only(Set.of("key-file"), args.fileArgument("key-file"));
        try (FileSigner signer = new FileSigner(Path.of(args.file("key-file")))) {
            return TopicPlan.JSON.createObjectNode().put("publicKey", signer.publicKey());
        }
    }

    private JsonNode plan(Arguments args) throws IOException {
        args.only(Set.of("file", "peer", "snapshot", "output", "api-key-env"), args.fileArgument("file"));
        JsonNode document = read(args.file("file"), true);
        TopicPlan.Snapshot snapshot;
        if (args.options().containsKey("snapshot")) {
            if (args.options().containsKey("peer")) throw TopicPlan.invalid("choose peer or snapshot");
            snapshot = TopicPlan.JSON.treeToValue(read(args.required("snapshot"), false), TopicPlan.Snapshot.class);
        } else snapshot = peer(args, TopicPlan.text(document, "chainId")).snapshot(document);
        if (defaults.chain() != null && !defaults.chain().equals(snapshot.chainId())
                && !args.options().containsKey("peer")) {
            throw TopicPlan.guidance("the topics file names another chain than this node's " + defaults.chain()
                    + "; fix its chainId, or pass --peer and --chain");
        }
        ObjectNode result = TopicPlan.compile(document, snapshot);
        checkExpiry(result, System.currentTimeMillis());
        return result;
    }

    private JsonNode sign(Arguments args) throws IOException {
        args.only(Set.of("plan", "key-file", "command", "output"), args.fileArgument("plan"));
        ObjectNode plan = TopicPlan.validate(read(args.file("plan"), false));
        checkExpiry(plan, System.currentTimeMillis());
        Integer selected = args.options().containsKey("command") ? integer(args.required("command"), 0) : null;
        ArrayNode commands = (ArrayNode) plan.get("commands");
        if (selected != null && selected >= commands.size()) throw TopicPlan.invalid("command index outside plan");
        int signed = 0;
        String signerId;
        try (FileSigner signer = new FileSigner(Path.of(args.required("key-file")))) {
            signerId = signer.publicKey().substring(0, 16);
            for (int index = 0; index < commands.size(); index++) {
                if (selected != null && selected != index) continue;
                ObjectNode command = (ObjectNode) commands.get(index);
                if (!"registry".equals(command.path("mode").asText())) continue;
                List<String> authorized = TopicPlan.keyList(command.path("requiredSigners").path("keys"));
                if (!authorized.contains(signer.publicKey())) continue;
                List<Signature> existing = TopicPlan.signatures(command.path("signatures"));
                if (existing.stream().anyMatch(signature -> signature.key().equals(signer.publicKey()))) {
                    throw TopicPlan.invalid("signer already signed this statement");
                }
                Signature added = TopicAuthoring.signRegistry(TopicPlan.statement(plan, index), signer)
                        .signatures().getFirst();
                TreeMap<String, Signature> sorted = new TreeMap<>();
                existing.forEach(signature -> sorted.put(signature.key(), signature));
                sorted.put(added.key(), added);
                ArrayNode output = command.putArray("signatures");
                sorted.values().forEach(signature -> output.addObject().put("key", signature.key())
                        .put("sigHex", Hex.encode(signature.sig())));
                signed++;
            }
        }
        if (signed == 0) {
            List<String> expected = new ArrayList<>();
            commands.forEach(command -> TopicPlan.keyList(command.path("requiredSigners").path("keys"))
                    .forEach(key -> expected.add(key.substring(0, 16))));
            throw TopicPlan.guidance("this key (" + signerId + ") is not a signer of any command in the plan"
                    + (expected.isEmpty() ? "" : "; it needs " + String.join(", ", expected.stream().distinct()
                    .toList())));
        }
        return TopicPlan.validate(plan);
    }

    private JsonNode submit(Arguments args) throws IOException {
        args.only(Set.of("plan", "peer", "api-key-env"), args.fileArgument("plan"));
        ObjectNode plan = TopicPlan.validate(read(args.file("plan"), false));
        checkExpiry(plan, System.currentTimeMillis());
        TopicPlan.Snapshot before = TopicPlan.JSON.treeToValue(plan.get("snapshot"), TopicPlan.Snapshot.class);
        TopicsPeer peer = peer(args, before.chainId());
        TopicPlan.Snapshot current = peer.snapshot(plan.path("document"));
        if (!before.generationId().equals(current.generationId())
                || !Objects.equals(before.config(), current.config())) {
            throw TopicPlan.invalid("TOPIC_COMMAND_STALE");
        }
        // Recompile against current heads and owners, then compare the signed statement and authority epoch inputs.
        ObjectNode replanned = TopicPlan.compile(plan.get("document"), current);
        checkExpiry(replanned, System.currentTimeMillis());
        ArrayNode commands = (ArrayNode) plan.get("commands");
        for (int index = 0; index < commands.size(); index++) {
            JsonNode command = commands.get(index);
            JsonNode fresh = replanned.path("commands").get(index);
            if ("registry".equals(command.path("mode").asText())) {
                if (!command.path("statementHex").equals(fresh.path("statementHex"))
                        || !command.path("requiredSigners").equals(fresh.path("requiredSigners"))
                        || authorityEpoch(TopicPlan.statement(plan, index), before)
                        != authorityEpoch(TopicPlan.statement(replanned, index), current)
                        || !TopicPlan.equivalent(command.path("before"), fresh.path("before"))) {
                    throw TopicPlan.invalid("TOPIC_COMMAND_STALE");
                }
                int missing = command.path("requiredSigners").path("threshold").intValue()
                        - command.path("signatures").size();
                if (missing > 0) {
                    throw TopicPlan.invalid(command.path("target").asText() + " needs " + missing + " more "
                            + (missing == 1 ? "signature" : "signatures") + "; each remaining signer runs  "
                            + "yano.sh appchain topics sign <plan> --key-file <seed file>");
                }
            }
        }
        ArrayNode results = TopicPlan.JSON.createArrayNode();
        // Validation of every step precedes any submit. The host remains authoritative if state races this preflight.
        for (int index = 0; index < commands.size(); index++) {
            JsonNode command = commands.get(index);
            ObjectNode result = results.addObject().put("target", command.path("target").asText());
            if ("governance".equals(command.path("mode").asText())) {
                result.put("status", "MEMBER_GOVERNANCE_REQUIRED");
                result.put("endpoint", "admin/topics/governance").put("commandHex", command.path("commandHex").asText());
            } else {
                CommandStatement statement = TopicPlan.statement(plan, index);
                byte[] bytes = TopicWireCodec.encodeRegistryCommand(TopicAuthoring.completeRegistry(statement,
                        TopicPlan.signatures(command.path("signatures"))));
                result.set("submission", peer.submit(bytes));
                result.put("status", "SUBMITTED_CHECK_RECEIPT");
            }
        }
        ObjectNode result = TopicPlan.JSON.createObjectNode();
        result.put("chainId", before.chainId());
        result.set("commands", results);
        return result;
    }

    private JsonNode resolve(Arguments args) throws IOException {
        args.only(Set.of("chain", "peer", "height", "api-key-env", "output"), 1);
        String topic = args.positionals().getFirst();
        if (!TopicNames.isValidTopic(topic)) throw TopicPlan.invalid("invalid topic");
        TopicsPeer peer = peer(args, chain(args));
        String path = "/topics/proof?topic=" + TopicsPeer.segment(topic);
        if (args.options().containsKey("height")) path += "&height=" + positive(args.required("height"), 2);
        JsonNode proof = peer.get(path);
        TopicPolicyProof decoded = TopicAuthorshipVerifier.policy(proof);
        if (!topic.equals(decoded.topic())) throw TopicPlan.invalid("topic proof belongs to another topic");
        if (args.options().containsKey("height") && decoded.height() != positive(args.required("height"), 2)) {
            throw TopicPlan.invalid("topic proof belongs to another height");
        }
        TopicWire.EffectiveOutcome outcome = TopicPolicyProofVerifier.verify(decoded, decoded.parentRoot())
                .orElseThrow(() -> TopicPlan.invalid("peer topic proof does not verify"));
        String digest = TopicWireCodec.outcomeDigest(outcome);
        if (!digest.equals(TopicPlan.text(proof, "effectivePolicyDigest"))) {
            throw TopicPlan.invalid("peer topic outcome digest does not match its proof");
        }
        ObjectNode result = TopicPlan.JSON.createObjectNode();
        result.put("subjectType", "topic-policy-v1").put("trust", "UNPINNED_PEER_ROOT");
        result.set("proof", proof);
        result.set("outcome", TopicPlan.JSON.valueToTree(outcome));
        result.put("effectivePolicyDigest", digest);
        return result;
    }

    private JsonNode delegate(Arguments args) throws IOException {
        args.only(Set.of("chain", "generation", "scope", "session-key", "not-before", "not-after",
                "key-file", "output", "peer", "api-key-env"), 0);
        String chain = chain(args);
        // The generation binds the credential to this chain's identity; a node supplies it from its status.
        String generation = args.options().containsKey("generation") ? args.required("generation")
                : TopicPlan.text(peer(args, chain).get("/status").path("stateCommitment"), "generationId");
        try (FileSigner signer = new FileSigner(Path.of(args.required("key-file")))) {
            TopicWire.Delegation delegation = TopicAuthoring.issueDelegation(chain,
                    generation, args.required("session-key"), args.required("scope"),
                    positive(args.required("not-before"), 0), positive(args.required("not-after"), 0), signer);
            return TopicPlan.JSON.createObjectNode().put("delegationHex", Hex.encode(TopicWireCodec.encodeDelegation(delegation)))
                    .put("delegationHash", TopicWireCodec.delegationHash(chain, generation, delegation));
        }
    }

    private JsonNode verify(Arguments args) throws IOException {
        args.only(Set.of("bundle", "trusted-block"), 1);
        return TopicAuthorshipVerifier.verify(args.positionals().getFirst(), read(args.required("bundle"), false),
                read(args.required("trusted-block"), false));
    }

    private JsonNode doctor(Arguments args) throws IOException {
        args.only(Set.of("bindings", "chain", "peer", "api-key-env"), 0);
        JsonNode bindings = read(args.required("bindings"), true);
        return TopicDoctor.check(bindings, peer(args, chain(args)));
    }

    /** Writes results under default file names and says, in words, what happens next. */
    private void guide(String action, Arguments args, JsonNode result, String serialized, PrintWriter out)
            throws IOException {
        String output = args.options().get("output");
        switch (action) {
            case "init" -> {
                String authority = result.path("authority").asText();
                out.println("✓ Wrote " + result.path("written").asText() + ": create " + args.positionals().getFirst()
                        + " by " + authorityWords(authority));
                List<String> edits = new ArrayList<>();
                if (!result.path("ownerKnown").asBoolean()) edits.add("the owners' public keys");
                if (!result.path("chainKnown").asBoolean()) edits.add("the chain id");
                edits.add("the writer policy");
                next(out, "check " + String.join(", ", edits) + " in it" + (result.path("ownerKnown").asBoolean()
                                ? "" : " (topics public-key <seed file> prints a key)") + ", then run  "
                                + "yano.sh appchain topics plan " + result.path("written").asText());
            }
            case "public-key" -> out.println(result.path("publicKey").asText());
            case "plan" -> {
                Path file = Path.of(output != null ? output
                        : stem(args.file("file"), ".yaml", ".yml", ".json") + ".plan.json");
                // An unsigned plan is recomputed from its document, so replacing one loses nothing; any other file
                // (a signed plan, a key, a link) is never replaced.
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !unsignedPlan(file)) {
                    throw TopicPlan.guidance(file.getFileName() + " exists and is not an unsigned plan; remove it "
                            + "or pass --output <file>");
                }
                replace(file, serialized);
                out.println("✓ Wrote " + file + " for chain " + result.path("snapshot").path("chainId").asText()
                        + " at block " + result.path("snapshot").path("height").asLong());
                boolean votes = false;
                boolean signatures = false;
                for (JsonNode command : result.path("commands")) {
                    String what = "  " + command.path("operation").asText() + " " + command.path("target").asText();
                    if ("governance".equals(command.path("mode").asText())) {
                        out.println(what + ": a member vote");
                        votes = true;
                    } else {
                        JsonNode signers = command.path("requiredSigners");
                        int needed = signers.path("threshold").asInt();
                        out.println(what + ": " + needed + (needed == 1 ? " signature" : " signatures")
                                + " needed from " + keyIds(signers.path("keys")) + " (authority "
                                + command.path("authority").asText() + ")");
                        signatures = true;
                    }
                    if (command.has("warning")) out.println("    " + command.path("warning").asText());
                }
                List<String> lines = new ArrayList<>();
                if (signatures) {
                    lines.add("each signer runs  yano.sh appchain topics sign " + file + " --key-file <seed file>");
                    lines.add("then  yano.sh appchain topics submit " + stem(file.toString(), ".plan.json", ".json")
                            + ".signed.json");
                }
                if (votes) {
                    lines.add((signatures ? "and " : "") + "send " + file.getFileName()
                            + " to every operator; each runs  yano.sh appchain topics approve " + file.getFileName());
                }
                next(out, lines.toArray(String[]::new));
            }
            case "sign" -> {
                String input = args.file("plan");
                Path file = Path.of(output != null ? output : input.endsWith(".signed.json") ? input
                        : stem(input, ".plan.json", ".json") + ".signed.json");
                if (!file.toString().equals(input) && Files.exists(file)) {
                    throw TopicPlan.guidance(file.getFileName() + " already exists; sign that file instead, or "
                            + "remove it");
                }
                replace(file, serialized);
                out.println("✓ Signed " + file);
                boolean complete = true;
                for (JsonNode command : result.path("commands")) {
                    if (!"registry".equals(command.path("mode").asText())) continue;
                    int have = command.path("signatures").size();
                    int need = command.path("requiredSigners").path("threshold").asInt();
                    out.println("  " + command.path("operation").asText() + " " + command.path("target").asText()
                            + ": " + have + " of " + need + " signatures");
                    complete &= have >= need;
                }
                next(out, complete ? "yano.sh appchain topics submit " + file
                        : "send " + file.getFileName() + " to the next signer, who runs  yano.sh appchain topics sign "
                        + file.getFileName() + " --key-file <seed file>");
            }
            case "submit" -> {
                String votes = null;
                for (JsonNode command : result.path("commands")) {
                    String target = command.path("target").asText();
                    if ("MEMBER_GOVERNANCE_REQUIRED".equals(command.path("status").asText())) {
                        out.println("  " + target + ": needs a member vote, not a submission");
                        votes = target;
                    } else {
                        out.println("✓ Submitted " + target + " (message "
                                + command.path("submission").path("messageId").asText() + ")");
                    }
                }
                next(out, votes != null ? "every operator runs  yano.sh appchain topics approve <the unsigned plan>"
                        : "it takes effect after the chain's activation lag; check with  yano.sh appchain topics "
                        + "resolve " + firstTopic(result));
            }
            case "resolve" -> {
                JsonNode outcome = result.path("outcome");
                out.println(args.positionals().getFirst() + " → " + outcomeWords(outcome));
                out.println("  proof: topic-policy-v1 at the node's parent root (trust: "
                        + result.path("trust").asText() + "); pass --json for the proof");
            }
            default -> {
                if (output != null) {
                    Files.writeString(Path.of(output), serialized, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW);
                    out.println("✓ Wrote " + output);
                } else out.print(serialized);
            }
        }
    }

    /** An effective outcome in words: who may write, the schema and the size limit, and where it comes from. */
    static String outcomeWords(JsonNode outcome) {
        String kind = outcome.path("kind").asText();
        return switch (kind) {
            case "DISABLED" -> "topic policies are off on this chain; anyone the members accept may write";
            case "UNREGISTERED_OPEN" -> "no namespace covers it; anyone the members accept may write";
            case "UNREGISTERED_REJECT" -> "no namespace covers it, and this chain refuses such topics";
            default -> {
                JsonNode policy = outcome.path("policy");
                JsonNode keys = policy.path("writers").path("keys");
                String writers = keys.isObject() ? keys.path("threshold").asInt() + " of "
                        + keyIds(keys.path("keys")) + " sign each write" : "anyone may write";
                String schema = policy.path("schema").asText("").isEmpty() ? "no schema"
                        : "schema " + policy.path("schema").asText().substring(0, 16) + "…";
                List<String> from = new ArrayList<>();
                outcome.path("sources").forEach(source -> from.add(source.path("name").asText() + " r"
                        + source.path("revision").asLong()));
                yield (policy.path("retired").asBoolean(false) ? "RETIRED; " : "") + writers + ", " + schema
                        + ", up to " + policy.path("maxPayloadBytes").asLong() + " bytes"
                        + (from.isEmpty() ? "" : " (from " + String.join(", ", from) + ")");
            }
        };
    }

    private static boolean unsignedPlan(Path file) {
        try {
            JsonNode plan = read(file.toString(), false);
            if (!TopicPlan.KIND.equals(plan.path("kind").asText())) return false;
            for (JsonNode command : plan.path("commands")) if (!command.path("signatures").isEmpty()) return false;
            return true;
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /** Writes owner-only next to the target and moves it into place, never through a link. */
    private static void replace(Path file, String content) throws IOException {
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), ".topics-", ".json");
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String authorityWords(String authority) {
        return switch (authority) {
            case "governance" -> "member vote";
            case TopicWire.AUTHORITY_REGISTRARS -> "registrar signatures (~registrars)";
            case TopicWire.AUTHORITY_CLAIM -> "a first-come claim (~claim), signed by its new owners";
            default -> "the owners of " + authority;
        };
    }

    private static String firstTopic(JsonNode result) {
        String target = result.path("commands").path(0).path("target").asText();
        return target.endsWith("/") ? target + "<topic>" : target;
    }

    private static String keyIds(JsonNode keys) {
        List<String> ids = new ArrayList<>();
        keys.forEach(key -> ids.add(key.asText().substring(0, 16)));
        return ids.size() == 1 ? "key " + ids.getFirst() : "keys " + String.join(", ", ids);
    }

    private static String stem(String file, String... suffixes) {
        for (String suffix : suffixes) {
            if (file.endsWith(suffix)) return file.substring(0, file.length() - suffix.length());
        }
        return file;
    }

    private static void next(PrintWriter out, String... lines) {
        if (lines.length == 0) return;
        out.println();
        out.println("Next: " + lines[0]);
        for (int index = 1; index < lines.length; index++) out.println("      " + lines[index]);
    }

    static void checkExpiry(JsonNode plan, long now) {
        TopicPlan.Snapshot snapshot = TopicPlan.JSON.convertValue(plan.get("snapshot"), TopicPlan.Snapshot.class);
        for (JsonNode command : plan.path("document").path("commands")) {
            if ("governance".equals(command.path("authority").asText())) continue;
            long notAfter = TopicPlan.number(command, "notAfter", 0);
            if (notAfter < now || notAfter - now > snapshot.config().settings().maxCommandMs()) {
                throw TopicPlan.invalid("TOPIC_COMMAND_EXPIRED");
            }
        }
    }

    private static long authorityEpoch(CommandStatement statement, TopicPlan.Snapshot state) {
        if (statement.operation() instanceof TopicWire.Revoke) return 0;
        return switch (statement.authority()) {
            case TopicWire.AUTHORITY_CLAIM -> 0;
            case TopicWire.AUTHORITY_REGISTRARS -> state.config().registrarsSince();
            default -> state.entries().get(statement.authority()).active().ownersSince();
        };
    }

    private TopicsPeer peer(Arguments args, String chain) {
        String env = args.options().get("api-key-env");
        String peer = args.options().get("peer");
        // A node directory's key belongs to its own node only; never send it to a peer named on the command line.
        String key = peer == null ? defaults.apiKey() : null;
        if (env != null) {
            if (!env.matches("[A-Za-z_][A-Za-z0-9_]*")) throw TopicPlan.invalid("invalid API credential variable name");
            key = System.getenv(env);
            if (key == null || key.isBlank()) throw TopicPlan.invalid("API credential environment variable is empty");
        }
        if (peer == null) peer = defaults.peer();
        if (peer == null) {
            throw TopicPlan.guidance("missing --peer; run in a node directory (--node-dir) or name a peer URL");
        }
        return new TopicsPeer(peer, chain, key);
    }

    private String chain(Arguments args) {
        if (args.options().containsKey("chain")) return args.required("chain");
        if (defaults.chain() == null) {
            throw TopicPlan.guidance("missing --chain; name the chain (the application has several, or this is not "
                    + "a node directory)");
        }
        return defaults.chain();
    }

    static JsonNode read(String file, boolean yaml) throws IOException {
        Path path = Path.of(file);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(file, null, "not a regular file");
        }
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            return TopicPlan.read(input.readNBytes(TopicPlan.MAX_FILE_BYTES + 1), yaml);
        }
    }

    private static Arguments parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        List<String> positionals = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) positionals.add(args[i]);
            else {
                String name = args[i].substring(2);
                if (++i == args.length || args[i].startsWith("--") || options.putIfAbsent(name, args[i]) != null) {
                    throw TopicPlan.invalid("options require one value and cannot be repeated");
                }
            }
        }
        return new Arguments(Map.copyOf(options), List.copyOf(positionals));
    }

    private static int integer(String value, int minimum) {
        return Math.toIntExact(positive(value, minimum));
    }

    private static long positive(String value, long minimum) {
        if (!value.matches("0|[1-9][0-9]{0,18}")) throw TopicPlan.invalid("expected a nonnegative integer");
        long result = Long.parseLong(value);
        if (result < minimum) throw TopicPlan.invalid("integer below minimum");
        return result;
    }

    /** What went wrong with a file or the node; a parser's message, which may quote the file, is not repeated. */
    private static String ioReason(IOException failure) {
        if (failure instanceof TopicsPeer.Refusal refusal) return "The node refused it: " + explain(refusal.code());
        if (failure instanceof FileAlreadyExistsException exists) {
            return exists.getFile() + " already exists; edit it, or pass --output <file>";
        }
        if (failure instanceof NoSuchFileException missing) return "No file " + missing.getFile();
        if (failure instanceof JsonProcessingException parse) {
            var where = parse.getLocation();
            return "The file is not valid JSON or YAML" + (where == null ? ""
                    : " (line " + where.getLineNr() + ", column " + where.getColumnNr() + ")");
        }
        String message = failure.getMessage();
        return "Topic file or node request failed: " + (message == null ? failure.getClass().getSimpleName()
                : message) + ". Check the path, the node (--peer) and the API credential.";
    }

    /** The host's refusal codes in words, with the code kept for searching. */
    public static String explain(String reason) {
        if (reason == null) return "no reason given";
        String code = reason.replaceAll(":.*", "");
        String words = switch (code) {
            case "TOPIC_COMMAND_STALE" -> "the chain changed since this plan was made; plan it again and collect "
                    + "new signatures";
            case "TOPIC_COMMAND_EXPIRED" -> "the plan's notAfter has passed or lies beyond the chain's command "
                    + "window; plan it again with a fresh notAfter";
            case "TOPIC_COMMAND_PENDING" -> "a change to this target is still waiting to activate; plan again "
                    + "once it is in effect";
            default -> null;
        };
        return words == null ? reason : words + " (" + reason + ")";
    }

    private static String safeReason(RuntimeException failure) {
        String message = failure.getMessage();
        if (failure instanceof TopicPlan.Guidance) return explain(message);
        if (message != null && message.matches("TOPIC_[A-Z_]+")) return explain(message);
        return "Use 'appchain topics --help' for the format; no input values were logged.";
    }

    private record Arguments(Map<String, String> options, List<String> positionals) {
        /** A file named by option or as the one positional argument; returns the positional count allowed. */
        int fileArgument(String option) {
            return options.containsKey(option) ? 0 : 1;
        }

        String file(String option) {
            return options.containsKey(option) ? required(option) : positionals.getFirst();
        }

        String required(String key) {
            String value = options.get(key);
            if (value == null || value.isBlank()) throw TopicPlan.invalid("missing option");
            return value;
        }

        void only(Set<String> names, int positionalCount) {
            if (!names.containsAll(options.keySet()) || positionals.size() != positionalCount) {
                throw TopicPlan.invalid("unexpected option or positional argument");
            }
        }
    }

    private static final String SEED_FORMAT = "the key file is not an Ed25519 seed (64 lowercase hex characters)";

    private static final class FileSigner implements TopicAuthoring.Signer, AutoCloseable {
        private final byte[] seed;
        private final String publicKey;

        FileSigner(Path file) throws IOException {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new NoSuchFileException(file.toString(), null, "not a regular file");
            }
            byte[] encoded;
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                encoded = input.readNBytes(131);
            }
            try {
                if (encoded.length > 130) throw TopicPlan.invalid(SEED_FORMAT);
                String hex = new String(encoded, StandardCharsets.US_ASCII).strip();
                if (!hex.matches("[0-9a-f]{64}")) throw TopicPlan.invalid(SEED_FORMAT);
                seed = Hex.decode(hex);
                publicKey = Hex.encode(KeyGenUtil.getPublicKeyFromPrivateKey(seed));
            } finally {
                Arrays.fill(encoded, (byte) 0);
            }
        }

        @Override public String publicKey() { return publicKey; }

        @Override public byte[] sign(byte[] statement) {
            return CryptoConfiguration.INSTANCE.getSigningProvider().sign(statement, seed);
        }

        @Override public void close() { Arrays.fill(seed, (byte) 0); }
    }
}
