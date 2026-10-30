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
import org.yanoproject.api.appchain.topics.TopicPolicyResolver;
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
import java.nio.ByteBuffer;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
            when the application has one chain, that chain. Elsewhere pass --peer <url>, and --chain <id> to
            init, resolve, delegate and doctor (plan, sign and submit read the chain from the file);
            --api-key-env <name> reads an API credential from the named environment variable, and
            --snapshot snapshot.json plans offline. --json prints the machine-readable result of init, plan,
            sign, submit, resolve, delegate, verify and doctor instead. init picks the authority from the chain:
            a parent namespace's owners, or the chain's creation policy (governance, ~registrars or ~claim).
            Signing keys are 32-byte Ed25519 seed files (64 lowercase hex characters), never command arguments.
            Plans are immutable statements: expired/stale plans must be regenerated and signed again.
            Member votes are cast by every operator with topics approve; no tool votes on a member's behalf.
            """;

    /** What each command does, for {@code topics <command> --help}. */
    private static final Map<String, String> HELP = Map.of(
            "init", """
                    Usage: yano.sh appchain topics init <namespace> [--owner-key-file <seed file>]
                             [--output topics.yaml]
                    Writes topics.yaml for one namespace change. In a node directory it reads the chain: a new
                    top-level name follows its creation policy (member vote, ~registrars or ~claim), a name under an
                    owned namespace is signed by that namespace's owners, and an existing namespace gets an update by
                    its owners that starts from its active policy, so planning it unchanged changes nothing.
                    --owner-key-file fills in the owners' public key from a seed file.""",
            "plan", """
                    Usage: yano.sh appchain topics plan <topics.yaml> [--output <name>.plan.json]
                    Checks the change against the chain and writes <name>.plan.json with the exact statements to
                    sign or vote on, who must act, and how each change alters the effective policy. The chain is
                    the file's chainId. Elsewhere: --peer <url>, or --snapshot <file> to plan offline.""",
            "sign", """
                    Usage: yano.sh appchain topics sign <plan.json> --key-file <seed file> [--command <index>]
                    Adds this key's signature to every command it may sign and writes <name>.signed.json (a signed
                    file is updated in place). The seed never leaves the file; member votes use topics approve.""",
            "submit", """
                    Usage: yano.sh appchain topics submit <signed plan.json> [--no-wait] [--heartbeat] [--timeout <s>]
                             [--json]
                    Submits the signed commands through this node and waits until each is accepted and in effect;
                    --heartbeat makes blocks on an idle chain, --no-wait returns at once. Submitting the same plan
                    again waits for the earlier submission instead. --json prints the result as JSON once done, and
                    the progress on standard error.""",
            "resolve", """
                    Usage: yano.sh appchain topics resolve <topic> [--height <n>] [--json]
                    The policy that governs writes to <topic> (at the block --height, 2 or more), in words, with
                    the namespaces it comes from; --json prints the effective-policy proof.""",
            "delegate", """
                    Usage: yano.sh appchain topics delegate --scope <namespace or topic> --session-key <64-hex>
                             --not-before <unix ms> --not-after <unix ms> --key-file <issuer seed file>
                    Issues a session-key credential: the issuer (an allowed delegator of the scope) lets a short-lived
                    browser key write under <scope> between the two times. In a node directory the chain and its
                    generation come from this node, and the credential is checked against the scope's active policy
                    first (the issuer a writer of a 1-of-n set and listed under delegate, the lifetime within
                    maxDelegationMs); with --generation and no node it is not checked. Prints the credential
                    (delegationHex) for the application; in production a delegation service does this, outside
                    the node.""",
            "verify", """
                    Usage: yano.sh appchain topics verify <messageId> --bundle <json> --trusted-block <json>
                    Checks offline who wrote a message under which policy, against a block you trust.""",
            "doctor", """
                    Usage: yano.sh appchain topics doctor --bindings <composite.yaml>
                    Checks a composite's topics against active and pending policies: coverage, payload schemas,
                    retired targets and writes that cross a protected topic.""",
            "public-key", """
                    Usage: yano.sh appchain topics public-key <seed file>
                    Prints the public key of an Ed25519 seed file (64 hex characters); never the seed.""");

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

    /** One committed state value at a pinned height: empty when absent; failing when the node cannot answer. */
    @FunctionalInterface
    public interface StateReader {
        Optional<byte[]> read(byte[] key) throws IOException;
    }

    /**
     * Where a vote stands, from state read at one height. Voting again after quorum would start a second tally
     * that re-applies the change, so a caller votes only on {@link Progress#OPEN}. The planned content comes from
     * the vote's own command bytes, so another change that took the same revision is never mistaken for it.
     *
     * @param memberKey this member's public key (64 hex)
     * @param height    the height the state was read at
     * @param window    the configuration's governance window in blocks
     */
    public static Progress progress(Vote vote, String memberKey, StateReader state, long height, long window)
            throws IOException {
        TopicWire.GovernanceOperation planned = TopicWireCodec.decodeGovernanceCommand(Hex.decode(vote.commandHex()))
                .operation();
        long planHead = vote.revision() - 1; // the target's head, or freed head, when the plan was made
        Optional<byte[]> stored = state.read(TopicKeys.entry(vote.target()));
        long head;
        if (stored.isPresent()) {
            TopicWire.EntryRecord entry = TopicWireCodec.decodeEntryRecord(stored.get());
            TopicWire.ActiveRevision active = entry.active();
            long activeRevision = active == null ? 0 : active.revision();
            // A member-governed change restarts the owners' authority epoch; an owner-signed update with the same
            // owners does not, so it is never taken for this change.
            if (active != null && activeRevision > planHead && active.ownersSince() == activeRevision
                    && planned(planned, active.content())) {
                return Progress.IN_EFFECT;
            }
            long last = Math.min(entry.head(), Math.max(planHead, activeRevision) + MAX_QUEUED_SCAN);
            for (long revision = Math.max(planHead, activeRevision) + 1; revision <= last; revision++) {
                Optional<byte[]> queued = state.read(TopicKeys.queued(vote.target(), revision));
                if (queued.isEmpty()) continue;
                TopicWire.QueuedRevision decided = TopicWireCodec.decodeQueuedRevision(queued.get());
                if (GOVERNANCE_AUTHORITY.equals(decided.authority()) && planned(planned, decided.content())) {
                    return Progress.DECIDED;
                }
            }
            head = entry.head();
        } else {
            // A cancelled creation leaves only its freed head; voting the old plan again would recreate the name.
            Optional<byte[]> freed = state.read(TopicKeys.freed(vote.target()));
            head = freed.isPresent() && freed.get().length == Long.BYTES ? ByteBuffer.wrap(freed.get()).getLong() : 0;
        }
        // The target changed since planning (or the plan claims a future head): this plan is out of date.
        if (head != planHead) return Progress.SUPERSEDED;
        Optional<byte[]> tally = state.read(TopicKeys.governance());
        if (tally.isPresent()) {
            String hash = TopicWireCodec.blake2b256Hex(Hex.decode(vote.commandHex()));
            for (var approval : TopicWireCodec.decodeGovernancePending(tally.get())) {
                // The next block is the earliest a further vote lands; the window is counted from there.
                if (approval.commandHash().equals(hash) && height + 1 - approval.firstSeen() <= window
                        && approval.approvers().contains(memberKey.toLowerCase(Locale.ROOT))) {
                    return Progress.VOTED;
                }
            }
        }
        return Progress.OPEN;
    }

    /** A target name for a message, or "(not shown)" when it could be key material. */
    public static String safeTarget(String target) {
        return TopicPlan.safe(target);
    }

    /** The hash the host tallies a member vote under. */
    public static String commandHash(Vote vote) {
        return TopicWireCodec.blake2b256Hex(Hex.decode(vote.commandHex()));
    }

    /** The statement hash of each owner-signed command of a plan, by target. */
    public static Map<String, String> statementHashes(Path planFile) throws IOException {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (JsonNode command : read(planFile.toString(), false).path("commands")) {
            if ("registry".equals(command.path("mode").asText())) {
                hashes.put(command.path("target").asText(), command.path("statementHash").asText());
            }
        }
        return hashes;
    }

    /** Whether the chain has already used a signed statement: its command was submitted and included. */
    public static boolean used(StateReader state, String statementHash) throws IOException {
        return state.read(TopicKeys.used(statementHash)).isPresent();
    }

    /** The active revision of a target, or 0 when it has none, read at the reader's height. */
    public static long activeRevision(StateReader state, String target) throws IOException {
        Optional<byte[]> entry = state.read(TopicKeys.entry(target));
        if (entry.isEmpty()) return 0;
        TopicWire.ActiveRevision active = TopicWireCodec.decodeEntryRecord(entry.get()).active();
        return active == null ? 0 : active.revision();
    }

    /** Where an owner-signed command stands: still pending, in effect, or refused (superseded included). */
    public record Submission(boolean done, String refusal) {
    }

    /**
     * Reads a submitted command's receipt and its target together at one height, so a change cancelled after it
     * was accepted (its receipt turns SUPERSEDED) is never reported as in effect.
     *
     * @param revision the revision the command creates, or {@code null} for a revocation
     */
    public static Submission submitted(StateReader state, String messageId, String target, Long revision)
            throws IOException {
        Optional<byte[]> stored = state.read(TopicKeys.receipt(messageId));
        if (stored.isEmpty()) return new Submission(false, null);
        TopicWire.Receipt receipt = TopicWireCodec.decodeReceipt(stored.get());
        if (!TopicWire.Receipt.ACCEPTED.equals(receipt.code())) return new Submission(true, receipt.code());
        if (revision == null) return new Submission(true, null);
        Optional<byte[]> entry = state.read(TopicKeys.entry(target));
        boolean active = entry.isPresent() && TopicWireCodec.decodeEntryRecord(entry.get()).active() != null
                && TopicWireCodec.decodeEntryRecord(entry.get()).active().revision() >= revision;
        return new Submission(active, null);
    }

    /** What a member vote does, in words: owners, writers, delegators, schema and size for a creation. */
    public static List<String> describe(Vote vote) {
        TopicWire.GovernanceOperation operation = TopicWireCodec.decodeGovernanceCommand(Hex.decode(vote.commandHex()))
                .operation();
        String target = TopicPlan.safe(vote.target());
        return switch (operation) {
            case TopicWire.GovCreate create -> List.of("create " + target,
                    "  owners: " + keySet(create.owners()),
                    "  writers: " + (create.policy().writers() == null ? "inherited"
                            : create.policy().writers().open() ? "anyone" : keySet(create.policy().writers().keys())),
                    "  session credentials from: " + (create.policy().delegate() == null ? "inherited"
                            : create.policy().delegate().isEmpty() ? "nobody"
                            : create.policy().delegate().stream().map(key -> key.substring(0, 16)).toList()),
                    "  payload schema: " + (create.policy().schema() == null ? "inherited"
                            : create.policy().schema().isEmpty() ? "none" : create.policy().schema().substring(0, 16)
                            + "…") + ", up to " + (create.policy().maxPayloadBytes() == null ? "the inherited"
                            : create.policy().maxPayloadBytes()) + " bytes");
            case TopicWire.GovRecover recover -> List.of("recover " + target,
                    "  new owners: " + keySet(recover.owners()));
            case TopicWire.GovRetire ignored -> List.of("retire " + target + " (no more writes)");
            default -> List.of(vote.operation() + " " + target);
        };
    }

    /** Whether stored content is what the governance operation makes. */
    private static boolean planned(TopicWire.GovernanceOperation operation, TopicWire.EntryContent content) {
        return switch (operation) {
            case TopicWire.GovCreate create -> !content.retired() && content.owners().equals(create.owners())
                    && content.policy().equals(create.policy());
            case TopicWire.GovRecover recover -> content.owners().equals(recover.owners());
            case TopicWire.GovRetire ignored -> content.retired();
            default -> false;
        };
    }

    private static String keySet(TopicWire.KeySet keys) {
        return keys.threshold() + " of " + keys.keys().stream().map(key -> key.substring(0, 16)).toList();
    }

    /** The host's authority name for member-governed registry changes. */
    private static final String GOVERNANCE_AUTHORITY = "~governance";
    /** Pending revisions per entry are capped by the chain (4 by default); scanning a few more is cheap. */
    private static final int MAX_QUEUED_SCAN = 16;

    private Defaults defaults = Defaults.NONE;
    /** Where notes go that are not part of a command's result. */
    private PrintWriter err = new PrintWriter(PrintWriter.nullWriter());
    /** The exact plan bytes {@code sign} read, so an in-place update replaces only them. */
    private byte[] signedInput;

    /** Runs a topics command with node-directory defaults. */
    public int run(String[] args, PrintWriter out, PrintWriter err, Defaults defaults) {
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        return run(args, out, err);
    }

    /** The member votes of a plan file, after checking the plan against its canonical compilation. */
    public static List<Vote> votes(Path planFile) throws IOException {
        return votes(planBytes(planFile));
    }

    /** The bytes of a plan file, read once, so a digest and the votes parsed from them describe the same plan. */
    public static byte[] planBytes(Path planFile) throws IOException {
        if (!Files.isRegularFile(planFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(planFile.toString(), null, "not a regular file");
        }
        try (InputStream input = Files.newInputStream(planFile, LinkOption.NOFOLLOW_LINKS)) {
            return input.readNBytes(TopicPlan.MAX_FILE_BYTES + 1);
        }
    }

    /** The member votes of a plan's bytes, checked against its own compilation. */
    public static List<Vote> votes(byte[] planBytes) throws IOException {
        ObjectNode plan;
        try {
            plan = TopicPlan.validate(TopicPlan.read(planBytes, false));
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
                out.println(args.length > 0 && HELP.containsKey(args[0]) ? HELP.get(args[0]) : USAGE);
                return AppChainDevtoolsCli.EXIT_OK;
            }
            Arguments arguments = parse(args);
            this.err = err;
            if (Set.of("plan", "sign", "submit").contains(args[0]) && arguments.options().containsKey("chain")) {
                throw TopicPlan.guidance(args[0] + " reads the chain from the file (its chainId); remove --chain, "
                        + "and pass --peer <url> to use another node");
            }
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
        String operation = "create";
        TopicWire.Policy current = null;
        long currentRevision = 0;
        long expiryWindow = 3_600_000;
        boolean online = chain != null && (args.options().containsKey("peer") || defaults.peer() != null);
        if (online) {
            TopicsPeer peer = peer(args, chain);
            JsonNode config = peer.get("/topics/config");
            if (!config.path("enabled").asBoolean(false)) {
                throw TopicPlan.guidance(TopicPlan.NO_REGISTRY);
            }
            List<String> names = TopicNames.applicableNames(namespace);
            for (String name : names.subList(0, names.size() - 1)) {
                JsonNode entry = peer.optional("/topics/entries/" + TopicsPeer.segment(name));
                // A retired parent cannot authorize; the nearest active one does.
                if (entry != null && entry.path("active").isObject()
                        && !entry.path("active").path("content").path("retired").asBoolean(false)) {
                    authority = name;
                }
            }
            // An existing namespace is changed by its own owners rather than created again, starting from its
            // active policy: an update replaces the entry's whole policy, so an unedited one changes nothing.
            JsonNode existing = peer.optional("/topics/entries/" + TopicsPeer.segment(namespace));
            if (existing != null && existing.path("active").isObject()) {
                TopicWire.ActiveRevision active = TopicPlan.JSON.treeToValue(existing, TopicWire.EntryRecord.class)
                        .active();
                if (active.content().retired()) {
                    throw TopicPlan.guidance(TopicPlan.safe(namespace) + " is retired; it takes no more changes");
                }
                operation = "update";
                authority = namespace;
                current = active.content().policy();
                currentRevision = active.revision();
            }
            JsonNode settings = config.path("config").path("settings");
            // Inside the chain's command window with room to sign and submit: an hour, or 90% of a shorter window.
            long window = settings.path("maxCommandMs").asLong(3_600_000);
            long margin = Math.max(window / 10, Math.min(60_000, window / 2));
            expiryWindow = Math.max(1, Math.min(3_600_000, window - margin));
            if ("create".equals(operation) && "governance".equals(authority) && names.size() == 1) {
                if (settings.path("registrars").isObject()) authority = TopicWire.AUTHORITY_REGISTRARS;
                else if (settings.path("openClaims").asBoolean(false)) authority = TopicWire.AUTHORITY_CLAIM;
            }
        }
        ObjectNode document = TopicPlan.JSON.createObjectNode();
        document.put("schemaVersion", 1).put("kind", "Topics")
                .put("chainId", chain != null ? chain : "REPLACE_CHAIN_ID");
        ObjectNode command = document.putArray("commands").addObject();
        command.put("target", namespace).put("operation", operation).put("authority", authority);
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        command.put("nonce", Hex.encode(nonce));
        if (!"governance".equals(authority)) command.put("notAfter", System.currentTimeMillis() + expiryWindow);
        if ("create".equals(operation)) command.putObject("owners").put("threshold", 1).putArray("keys").add(owner);
        if (current != null) command.set("policy", policyDocument(current));
        else command.putObject("policy").put("writers", "open").put("schema", "none");
        Path path = Path.of(args.options().getOrDefault("output", "topics.yaml"));
        Files.writeString(path, TopicPlan.YAML.writeValueAsString(document), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        ObjectNode result = TopicPlan.JSON.createObjectNode().put("written", path.toString()).put("kind", "Topics")
                .put("authority", authority).put("operation", operation)
                .put("ownerKnown", "update".equals(operation) || !owner.startsWith("REPLACE"))
                .put("chainKnown", chain != null).put("checked", online);
        if (current != null) result.put("policyFromRevision", currentRevision);
        return result;
    }

    /**
     * An entry's own stored policy as a topics document writes it: a field the entry leaves to its ancestors stays
     * out, and explicit overrides ({@code writers: open}, an empty {@code delegate}, {@code schema: none}) stay in.
     * A stored schema is named by its hash, which the chain already holds.
     */
    static ObjectNode policyDocument(TopicWire.Policy policy) {
        ObjectNode node = TopicPlan.JSON.createObjectNode();
        if (policy.writers() != null) {
            if (policy.writers().open()) node.put("writers", "open");
            else node.set("writers", TopicPlan.JSON.valueToTree(policy.writers().keys()));
        }
        if (policy.delegate() != null) policy.delegate().forEach(node.putArray("delegate")::add);
        if (policy.maxDelegationMs() != null) node.put("maxDelegationMs", policy.maxDelegationMs());
        if (policy.maxPayloadBytes() != null) node.put("maxPayloadBytes", policy.maxPayloadBytes());
        if (policy.schema() != null) node.put("schema", policy.schema().isEmpty() ? "none" : policy.schema());
        return node;
    }

    /** The public key of a seed file; the seed is never printed. */
    private JsonNode publicKey(Arguments args) throws IOException {
        if (args.positionals().isEmpty() && !args.options().containsKey("key-file")) {
            throw TopicPlan.guidance("name the seed file:  yano.sh appchain topics public-key <seed file>");
        }
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
                    + "; fix its chainId, or pass --peer <url> for a node of that chain");
        }
        ObjectNode result = TopicPlan.compile(document, snapshot);
        checkExpiry(result, System.currentTimeMillis());
        return result;
    }

    private JsonNode sign(Arguments args) throws IOException {
        args.only(Set.of("plan", "key-file", "command", "output"), args.fileArgument("plan"));
        Path input = Path.of(args.file("plan"));
        if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(input.toString(), null, "not a regular file");
        }
        signedInput = Files.readAllBytes(input);
        ObjectNode plan = TopicPlan.validate(TopicPlan.read(signedInput, false));
        boolean signable = false;
        for (JsonNode command : plan.path("commands")) signable |= "registry".equals(command.path("mode").asText());
        if (!signable) {
            throw TopicPlan.guidance("this plan is a member vote, not signed by keys: each operator runs  yano.sh "
                    + "appchain topics approve <plan>  from their node directory");
        }
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
            commands.forEach(command -> {
                if ("registry".equals(command.path("mode").asText())) {
                    TopicPlan.keyList(command.path("requiredSigners").path("keys"))
                            .forEach(key -> expected.add(key.substring(0, 16)));
                }
            });
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
                    throw TopicPlan.invalid(TopicPlan.safe(command.path("target").asText()) + " needs " + missing
                            + " more "
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
        if (args.options().containsKey("height")) path += "&height=" + height(args);
        JsonNode proof = peer.get(path);
        TopicPolicyProof decoded = TopicAuthorshipVerifier.policy(proof);
        if (!topic.equals(decoded.topic())) throw TopicPlan.invalid("topic proof belongs to another topic");
        if (args.options().containsKey("height") && decoded.height() != height(args)) {
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
        // With a node to read, the credential is checked against the scope's policy before it is signed.
        boolean online = args.options().containsKey("peer") || defaults.peer() != null;
        TopicsPeer peer = online ? peer(args, chain) : null;
        // The generation binds the credential to this chain's identity; a node supplies it from its status.
        String generation = args.options().containsKey("generation") ? args.required("generation")
                : TopicPlan.text(peer(args, chain).get("/status").path("stateCommitment"), "generationId");
        String scope = args.required("scope");
        String sessionKey = args.required("session-key");
        long notBefore = positive(args.required("not-before"), 0);
        long notAfter = positive(args.required("not-after"), 0);
        try (FileSigner signer = new FileSigner(Path.of(args.required("key-file")))) {
            if (online) checkDelegation(peer, scope, signer.publicKey(), notBefore, notAfter);
            else {
                err.println("Note: no node was read, so this credential was not checked against the policy of "
                        + TopicPlan.safe(scope) + "; members check it on every write");
            }
            TopicWire.Delegation delegation = TopicAuthoring.issueDelegation(chain, generation, sessionKey, scope,
                    notBefore, notAfter, signer);
            return TopicPlan.JSON.createObjectNode().put("delegationHex", Hex.encode(TopicWireCodec.encodeDelegation(delegation)))
                    .put("delegationHash", TopicWireCodec.delegationHash(chain, generation, delegation))
                    .put("policyChecked", online);
        }
    }

    /**
     * Refuses a credential the chain would refuse on every write, by the rules members apply to the scope at the
     * tip: its policy must have a 1-of-n writer set, the issuer must be one of those writers and listed under
     * {@code delegate}, and the lifetime must fit {@code maxDelegationMs} (capped by the chain). A topic below the
     * scope can still narrow its own policy, and pending changes are not seen; members check each write.
     */
    private static void checkDelegation(TopicsPeer peer, String scope, String issuer, long notBefore, long notAfter)
            throws IOException {
        String where = TopicPlan.safe(scope);
        if (!TopicNames.isValidEntryName(scope)) {
            throw TopicPlan.guidance("--scope must be a namespace (ending in /) or a topic name");
        }
        TopicPlan.Snapshot snapshot = peer.snapshot(new LinkedHashSet<>(TopicNames.applicableNames(scope)));
        if (snapshot.config() == null) throw TopicPlan.guidance(TopicPlan.NO_REGISTRY);
        TopicWire.EffectiveOutcome outcome = TopicPolicyResolver.resolve(scope, snapshot.config(),
                snapshot.maxMessageBytes(), snapshot.entries());
        String issuerId = "this key (" + issuer.substring(0, 16) + ")";
        if (outcome.policy() == null) {
            throw TopicPlan.guidance("no namespace covers " + where + ", so a credential for it grants nothing; "
                    + "create the namespace first");
        }
        TopicWire.ResolvedPolicy policy = outcome.policy();
        if (policy.retired()) throw TopicPlan.guidance(where + " is retired; it takes no more writes");
        if (policy.writers().open()) {
            throw TopicPlan.guidance("anyone may write " + where + " (writers: open), so it needs no credential");
        }
        TopicWire.KeySet writers = policy.writers().keys();
        if (writers.threshold() != 1) {
            throw TopicPlan.guidance(where + " needs " + writers.threshold() + " writer signatures on each write; a "
                    + "credential stands in for one writer, so it works only with a 1-of-n writer set");
        }
        if (!writers.keys().contains(issuer)) {
            throw TopicPlan.guidance(issuerId + " is not a writer of " + where + "; issue with a writer key");
        }
        if (!policy.delegate().contains(issuer)) {
            throw TopicPlan.guidance(issuerId + " is not listed under delegate for " + where + "; its owners add it "
                    + "with  yano.sh appchain topics init " + where + "  (an update), then plan, sign and submit");
        }
        if (notAfter - notBefore > policy.maxDelegationMs()) {
            throw TopicPlan.guidance("the credential would last " + (notAfter - notBefore) + " ms, longer than "
                    + where + " allows (" + policy.maxDelegationMs() + " ms: its maxDelegationMs, capped by the "
                    + "chain's max-delegation-ms); move --not-after closer to --not-before");
        }
        if (notAfter < System.currentTimeMillis()) {
            throw TopicPlan.guidance("--not-after is in the past, so the credential would never be valid");
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
                out.println("✓ Wrote " + result.path("written").asText() + ": " + result.path("operation").asText()
                        + " " + TopicPlan.safe(args.positionals().getFirst()) + " by " + authorityWords(authority));
                if (!result.path("checked").asBoolean()) {
                    out.println("  The chain was not read (no node directory), so the authority is a guess; run "
                            + "this in your node directory, or pass --peer and --chain");
                }
                List<String> edits = new ArrayList<>();
                if (!result.path("ownerKnown").asBoolean()) edits.add("the owners' public keys");
                if (!result.path("chainKnown").asBoolean()) edits.add("the chain id");
                if (result.has("policyFromRevision")) {
                    out.println("  The policy is the active one (revision " + result.path("policyFromRevision")
                            .asLong() + "); change only the fields you mean to change");
                    edits.add("the policy fields to change");
                } else edits.add("the writer policy");
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
                boolean exists = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
                if (exists && !unsignedPlan(file)) {
                    throw TopicPlan.guidance(shown(file.toString()) + " exists and is not an unsigned plan; remove "
                            + "it or pass --output <file>");
                }
                if (exists) replaceLocked(file, Files.readAllBytes(file), serialized);
                else Files.writeString(file, serialized, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                out.println("✓ Wrote " + file + " for chain " + result.path("snapshot").path("chainId").asText()
                        + " at block " + result.path("snapshot").path("height").asLong());
                boolean votes = false;
                boolean signatures = false;
                for (JsonNode command : result.path("commands")) {
                    String what = "  " + command.path("operation").asText() + " "
                            + TopicPlan.safe(command.path("target").asText());
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
                    changes(command, revoked(result, command)).forEach(line -> out.println("    " + line));
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
                if (file.toString().equals(input)) {
                    // Signing in place: another signer may be writing the same file, so the update holds a lock
                    // and only replaces the exact bytes this signature was made over.
                    replaceLocked(file, signedInput, serialized);
                } else {
                    try {
                        Files.writeString(file, serialized, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                    } catch (FileAlreadyExistsException exists) {
                        throw TopicPlan.guidance(shown(file.toString()) + " already exists; sign that file instead, "
                                + "or remove it");
                    }
                }
                out.println("✓ Signed " + file);
                boolean complete = true;
                for (JsonNode command : result.path("commands")) {
                    if (!"registry".equals(command.path("mode").asText())) continue;
                    int have = command.path("signatures").size();
                    int need = command.path("requiredSigners").path("threshold").asInt();
                    out.println("  " + command.path("operation").asText() + " "
                            + TopicPlan.safe(command.path("target").asText()) + ": " + have + " of " + need
                            + " signatures");
                    changes(command, revoked(result, command)).forEach(line -> out.println("    " + line));
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

    /**
     * How a planned command changes its target's effective policy, field by field, from the plan's {@code before}
     * and {@code afterActivation}; a change that drops writer keys, delegators or a schema is called out, so
     * nobody signs a removal by accident. A revocation names the credential instead.
     */
    static List<String> changes(JsonNode command, String revoked) {
        if (revoked != null) return List.of("revokes " + revoked);
        JsonNode before = command.path("before");
        JsonNode after = command.path("afterActivation");
        if (!after.isObject()) return List.of();
        if (!"POLICY".equals(before.path("kind").asText())) {
            return List.of("after activation: " + outcomeWords(after));
        }
        JsonNode old = before.path("policy");
        JsonNode next = after.path("policy");
        List<String> lines = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        String[][] fields = {{"writers", writersWords(old), writersWords(next)},
                {"delegate", delegateWords(old), delegateWords(next)},
                {"maxDelegationMs", old.path("maxDelegationMs").asText(), next.path("maxDelegationMs").asText()},
                {"maxPayloadBytes", old.path("maxPayloadBytes").asText(), next.path("maxPayloadBytes").asText()},
                {"schema", schemaWords(old), schemaWords(next)},
                {"retired", old.path("retired").asText("false"), next.path("retired").asText("false")}};
        for (String[] field : fields) {
            if (!field[1].equals(field[2])) lines.add(field[0] + ": " + field[1] + " → " + field[2]);
        }
        if (old.path("writers").path("keys").isObject() && !next.path("writers").path("keys").isObject()) {
            removed.add("the writer keys (anyone may write)");
        }
        if (!old.path("delegate").isEmpty() && next.path("delegate").isEmpty()) removed.add("every delegator");
        if (!old.path("schema").asText("").isEmpty() && next.path("schema").asText("").isEmpty()) {
            removed.add("the payload schema");
        }
        if (lines.isEmpty()) return List.of("the effective policy does not change");
        if (!removed.isEmpty()) lines.add("! this removes " + String.join(", ", removed));
        return lines;
    }

    private static String writersWords(JsonNode policy) {
        JsonNode keys = policy.path("writers").path("keys");
        return keys.isObject() ? keys.path("threshold").asInt() + " of " + keyIds(keys.path("keys")) : "anyone";
    }

    private static String delegateWords(JsonNode policy) {
        return policy.path("delegate").isEmpty() ? "nobody" : keyIds(policy.path("delegate"));
    }

    private static String schemaWords(JsonNode policy) {
        String schema = policy.path("schema").asText("");
        return schema.isEmpty() ? "none" : schema.substring(0, 16) + "…";
    }

    /**
     * The credential a plan's revoke command revokes, in words: its hash, session key and scope; null for any
     * other command.
     */
    static String revoked(JsonNode plan, JsonNode command) {
        if (!"revoke".equals(command.path("operation").asText())) return null;
        String target = command.path("target").asText();
        for (JsonNode written : plan.path("document").path("commands")) {
            if (!target.equals(written.path("target").asText()) || !written.has("delegationHex")) continue;
            TopicWire.Delegation delegation = TopicWireCodec.decodeDelegation(Hex.decode(
                    written.path("delegationHex").asText()));
            JsonNode snapshot = plan.path("snapshot");
            String hash = TopicWireCodec.delegationHash(snapshot.path("chainId").asText(),
                    snapshot.path("generationId").asText(), delegation);
            return "credential " + hash.substring(0, 16) + "… (session key " + delegation.delegate().substring(0, 16)
                    + "…, scope " + TopicPlan.safe(delegation.scope()) + ")";
        }
        return null;
    }

    /** The credential each revoke command of a plan revokes, in words, by target. */
    public static Map<String, String> revocations(Path planFile) throws IOException {
        JsonNode plan = read(planFile.toString(), false);
        Map<String, String> revoked = new LinkedHashMap<>();
        for (JsonNode command : plan.path("commands")) {
            String words = revoked(plan, command);
            if (words != null) revoked.put(command.path("target").asText(), words);
        }
        return revoked;
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

    /** A plan that checks against its canonical compilation and carries no signature. */
    private static boolean unsignedPlan(Path file) {
        try {
            JsonNode plan = read(file.toString(), false);
            TopicPlan.validate(plan);
            for (JsonNode command : plan.path("commands")) if (!command.path("signatures").isEmpty()) return false;
            return true;
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /** Writes owner-only next to the target and moves it into place, never through a link. */
    private static void replace(Path file, String content) throws IOException {
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), ".topics-", ".json");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Replaces {@code file} under a lock file, only if it still holds {@code expected}; a concurrent writer's
     * result is never overwritten.
     */
    private static void replaceLocked(Path file, byte[] expected, String content) throws IOException {
        Path lock = file.resolveSibling("." + file.getFileName() + ".lock");
        try {
            Files.createFile(lock);
        } catch (FileAlreadyExistsException busy) {
            throw TopicPlan.guidance("another signer is updating " + shown(file.toString()) + "; try again (remove "
                    + shown(lock.toString()) + " if no one is)");
        }
        try {
            if (expected == null || !Arrays.equals(expected, Files.readAllBytes(file))) {
                throw TopicPlan.guidance(shown(file.toString()) + " changed while signing; sign it again");
            }
            replace(file, content);
        } finally {
            Files.deleteIfExists(lock);
        }
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

    /** A policy is resolved for the block a write lands in: the first block after genesis or later. */
    private static long height(Arguments args) {
        String value = args.required("height");
        if (!value.matches("[0-9]{1,18}") || Long.parseLong(value) < 2) {
            throw TopicPlan.guidance("--height is the block a write lands in: 2 or more (block 1 follows genesis)");
        }
        return Long.parseLong(value);
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
            return shown(exists.getFile()) + " already exists; edit it, or pass --output <file>";
        }
        if (failure instanceof NoSuchFileException missing) return "No file " + shown(missing.getFile());
        if (failure instanceof JsonProcessingException parse) {
            var where = parse.getLocation();
            return "The file is not valid JSON or YAML" + (where == null ? ""
                    : " (line " + where.getLineNr() + ", column " + where.getColumnNr() + ")");
        }
        String message = failure.getMessage();
        return "Topic file or node request failed: " + (message == null ? failure.getClass().getSimpleName()
                : message) + ". Check the path, the node (--peer) and the API credential.";
    }

    /** A file named in a message: its name when it looks like one, so a mistyped key never appears. */
    public static String shown(String path) {
        if (path == null) return "(path not shown)";
        String name = Path.of(path).getFileName() == null ? path : Path.of(path).getFileName().toString();
        return name.matches("[A-Za-z0-9._-]{1,80}") && !name.matches(".*[0-9a-fA-F]{24}.*") ? name
                : "(path not shown)";
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
            // Only the option's name is repeated, never a value.
            if (value == null || value.isBlank()) {
                throw TopicPlan.guidance("missing --" + key + "; the command's --help lists what it needs");
            }
            return value;
        }

        void only(Set<String> names, int positionalCount) {
            // Option names are repeated, never a value or a positional argument, which may be a path.
            List<String> unknown = options.keySet().stream().filter(name -> !names.contains(name))
                    .map(name -> "--" + TopicPlan.safe(name)).sorted().toList();
            if (!unknown.isEmpty()) {
                throw TopicPlan.invalid("unknown option " + String.join(", ", unknown)
                        + "; the command's --help lists its options");
            }
            if (positionals.size() != positionalCount) {
                throw TopicPlan.invalid("expected " + positionalCount + " file "
                        + (positionalCount == 1 ? "argument" : "arguments") + ", got " + positionals.size()
                        + "; the command's --help lists what it needs");
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
