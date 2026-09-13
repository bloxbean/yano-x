package org.yanoproject.x.feed.cli;

import org.yanoproject.x.attest.client.AttestTrust;
import org.yanoproject.x.feed.client.FeedClient;
import org.yanoproject.x.feed.client.FeedException;
import org.yanoproject.x.feed.client.FeedVerifier;
import org.yanoproject.x.feed.client.GatewayService;
import org.yanoproject.x.feed.client.PortalService;
import org.yanoproject.x.feed.client.RoundBundle;
import org.yanoproject.x.feed.client.RoundRequest;
import org.yanoproject.x.feed.client.RoundView;
import org.yanoproject.x.feed.profile.Aggregation;
import org.yanoproject.x.feed.profile.FeedDatum;
import org.yanoproject.x.feed.profile.FeedGenesis;
import org.yanoproject.x.feed.profile.FeedStarterProfile;
import org.yanoproject.x.feed.profile.FeedValues;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import org.yanoproject.x.trust.client.StatusAnswer;
import org.yanoproject.x.trust.client.TrustRegistryException;
import org.yanoproject.x.trust.profile.TrustRegistryGenesis;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Dependency-light command line interface for the attestation feed starter, ADR-052 §2.3. Experimental. */
public final class FeedCli {
    public static final int OK = 0;
    public static final int USAGE = 2;
    public static final int UNAVAILABLE = 3;
    public static final int INVALID = 4;
    public static final int PINNED = 5;
    public static final int UNPINNED = 6;
    public static final int DEFAULT_PORTAL_PORT = 8680;
    public static final int DEFAULT_GATEWAY_PORT = 8690;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private static final int MAX_TEXT_FILE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_DATUM_HEX_CHARS = 32 * 1024;

    private final PrintStream out;
    private final PrintStream err;

    FeedCli(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        System.exit(new FeedCli(System.out, System.err).run(args));
    }

    int run(String[] args) {
        try {
            if (args == null || args.length == 0
                    || (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0])))) {
                out.println(usage());
                return args == null || args.length == 0 ? USAGE : OK;
            }
            Arguments input = Arguments.parse(args);
            return switch (input.command()) {
                case "genesis" -> genesis(input);
                case "descriptor" -> descriptor(input);
                case "actor-key" -> actorKey(input);
                case "feed" -> feed(input);
                case "observe" -> observe(input);
                case "round" -> round(input);
                case "verify" -> verify(input);
                case "datum" -> datum(input);
                case "simulate" -> simulate(input);
                case "serve" -> serve(input);
                case "gateway" -> gateway(input);
                default -> throw new UsageException("unknown command " + input.command());
            };
        } catch (UsageException usage) {
            err.println(usage.getMessage());
            err.println(usage());
            return USAGE;
        } catch (FeedException failure) {
            err.println(failure.getMessage());
            return failure.exitCode();
        } catch (TrustRegistryException failure) {
            err.println(failure.getMessage());
            return failure.error() == TrustRegistryException.Error.UNAVAILABLE
                    || failure.error() == TrustRegistryException.Error.NOT_FINALIZED
                    ? UNAVAILABLE : INVALID;
        } catch (IllegalArgumentException invalid) {
            err.println(invalid.getMessage());
            return INVALID;
        } catch (IOException io) {
            err.println(io.getMessage());
            return UNAVAILABLE;
        } catch (Exception failure) {
            err.println("yano-feed command failed: " + failure.getMessage());
            return INVALID;
        }
    }

    // ------------------------------------------------------------------ genesis

    private int genesis(Arguments input) throws IOException {
        TrustRegistryGenesis.Descriptor descriptor = descriptorOf(input);
        List<String> members = List.of(input.required("members").split(",", -1));
        int threshold = (int) input.longValue("threshold", members.size());
        int chainIndex = (int) input.longValue("chain-index", 0);
        AuthenticatedMapContract.Genesis genesis = FeedGenesis.genesis(descriptor, members, threshold);
        StringBuilder lines = new StringBuilder();
        for (String line : FeedGenesis.properties(genesis, chainIndex)) {
            lines.append(line).append(System.lineSeparator());
        }
        return write(input.option("output", null), lines.toString());
    }

    private int descriptor(Arguments input) throws IOException {
        if (!input.flag("demo")) {
            throw new UsageException("descriptor prints the demo descriptor; pass --demo");
        }
        String chainId = input.option("chain", FeedGenesis.DEFAULT_CHAIN_ID);
        return write(input.option("output", null),
                TrustRegistryGenesis.toJson(FeedGenesis.demo(chainId)) + System.lineSeparator());
    }

    private int actorKey(Arguments input) throws IOException {
        String actorId = input.required("actor");
        String chainId = input.option("chain", FeedGenesis.DEFAULT_CHAIN_ID);
        String keyId = input.option("key-id", actorId + "-k1");
        TrustRegistryGenesis.ActorKey key = TrustRegistryGenesis.actorKey(chainId, actorId, keyId, seed(input));
        ObjectNode node = JSON.createObjectNode();
        node.put("chainId", chainId);
        node.put("id", actorId);
        node.put("keyId", key.keyId());
        node.put("publicKeyHex", key.publicKeyHex());
        node.put("keyProofHex", key.keyProofHex());
        return write(input.option("output", null),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node) + System.lineSeparator());
    }

    private TrustRegistryGenesis.Descriptor descriptorOf(Arguments input) throws IOException {
        if (input.flag("demo")) {
            return FeedGenesis.demo(input.option("chain", FeedGenesis.DEFAULT_CHAIN_ID));
        }
        if (!input.has("descriptor")) {
            throw new UsageException("pass --demo or --descriptor <file>");
        }
        return FeedGenesis.requireStarterDescriptor(
                TrustRegistryGenesis.parse(readText(Path.of(input.required("descriptor")))));
    }

    // ------------------------------------------------------------------ feeds and observations

    private int feed(Arguments input) throws IOException {
        FeedClient client = client(input);
        String feedId = input.required("feed");
        switch (input.subcommand()) {
            case "create", "update" -> {
                FeedValues.FeedValue value = specOf(readText(Path.of(input.required("spec"))));
                boolean update = "update".equals(input.subcommand());
                return report(update ? client.updateFeed(signer(input), feedId, value)
                        : client.createFeed(signer(input), feedId, value));
            }
            case "get" -> {
                Long height = input.has("height") ? input.longValue("height", 0) : null;
                StatusAnswer answer = client.feedAnswer(feedId, height);
                if (answer.presence() != StatusAnswer.Presence.ACTIVE) {
                    err.println("Feed " + feedId + " is " + answer.presence() + " at height " + answer.height());
                    return INVALID;
                }
                FeedValues.FeedValue value = FeedValues.FeedValue.decode(answer.entry().value());
                if (input.flag("json")) {
                    ObjectNode node = specNode(value);
                    node.put("feedId", feedId);
                    node.put("height", answer.height());
                    node.put("revision", answer.entry().revision());
                    node.put("currentRoundByClock", client.currentRound(value));
                    out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
                } else {
                    out.println("Feed " + feedId + " on " + client.chainId() + " at height " + answer.height()
                            + " (revision " + answer.entry().revision() + "): " + value.statusName());
                    out.println("  " + FeedStarterProfile.STARTER_NOTICE);
                    out.println("  " + value.description() + " in " + value.unit() + " at scale " + value.scale());
                    out.println("  calendar: epoch " + value.epochStart() + ", " + value.roundSeconds()
                            + " s per round; current round by this clock " + client.currentRound(value));
                    out.println("  sources (" + value.sources().size() + ", quorum " + value.minimumSources() + "): "
                            + String.join(", ", value.sources()));
                    out.println("  outliers beyond " + value.maximumDeviationPpm() + " ppm or "
                            + value.maximumDeviationAbsolute() + " absolute; values within ["
                            + FeedValues.decimal(value.minimumValue(), value.scale()) + ", "
                            + FeedValues.decimal(value.maximumValue(), value.scale()) + "]");
                    List<Long> rounds = client.projection().rounds(feedId);
                    try {
                        client.feeds();
                        rounds = client.projection().rounds(feedId);
                    } catch (FeedException bound) {
                        out.println("  (projection: " + bound.getMessage() + ")");
                    }
                    out.println("  rounds with records: " + (rounds.isEmpty() ? "none" : rounds.toString()));
                }
                return OK;
            }
            default -> throw new UsageException("feed needs create, update, or get");
        }
    }

    private int observe(Arguments input) throws IOException {
        FeedClient client = client(input);
        String feedId = input.required("feed");
        long value = parseValue(input.required("value"));
        long observedAt = input.longValue("observed-at", client.clock().instant().getEpochSecond());
        long round;
        if (input.has("round")) {
            round = input.longValue("round", 0);
        } else {
            FeedValues.FeedValue feed = FeedValues.FeedValue.decode(client.entry(FeedStarterProfile.FEEDS,
                    FeedStarterProfile.feedKey(feedId)).orElseThrow(() ->
                    FeedException.invalid("feed " + feedId + " is not defined")).value());
            round = feed.roundOf(observedAt);
        }
        byte[] evidence = input.has("evidence") ? FeedValues.sha256(Files.readAllBytes(Path.of(input.required("evidence")))) : null;
        FeedValues.ObservationValue observation = new FeedValues.ObservationValue(value, observedAt, evidence,
                input.option("note", ""));
        out.println("Observation of " + input.required("actor") + " for " + feedId + " round " + round
                + ": value " + value + " at " + observedAt);
        return report(client.observe(signer(input), feedId, round, observation));
    }

    // ------------------------------------------------------------------ rounds

    private int round(Arguments input) throws IOException {
        switch (input.subcommand()) {
            case "propose" -> {
                FeedClient client = client(input);
                Long height = input.has("height") ? input.longValue("height", 0) : null;
                RoundRequest request = client.proposeRound(signer(input), input.required("feed"),
                        input.longValue("round", -1), height);
                FeedValues.RoundValue record = request.recordValue();
                Aggregation.Result result = client.compute(request.feedId(), request.round(), record.closedAtHeight()).result();
                out.println("Proposed " + record.statusName() + " for " + request.feedId() + " round " + request.round()
                        + " at height " + record.closedAtHeight() + " as proposal " + request.proposalId()
                        + " (message " + request.proposeMessageIdHex() + ", deadline height " + request.deadlineHeight() + ")");
                if (record.closed()) {
                    out.println("  aggregate " + FeedValues.decimal(record.aggregate(), record.scale())
                            + " from " + record.acceptedSources() + "; datum " + HEX.formatHex(record.datumSha256()));
                }
                for (Aggregation.SourceResult source : result.sources()) {
                    out.println("  " + source.sourceId() + ": " + source.disposition()
                            + (source.value() != null ? " " + FeedValues.decimal(source.value(), record.scale()) : ""));
                }
                return write(input.required("output"), request.toJson() + System.lineSeparator());
            }
            case "approve", "reject" -> {
                FeedClient client = client(input);
                RoundRequest request = request(input);
                boolean approve = "approve".equals(input.subcommand());
                String messageId = approve ? client.approveRound(signer(input), request)
                        : client.rejectRound(signer(input), request);
                out.println((approve ? "Approved" : "Rejected") + " proposal " + request.proposalId()
                        + " as " + input.required("actor") + " (message " + messageId + ")"
                        + (approve ? "; the recomputation at height " + request.recordValue().closedAtHeight() + " agreed" : ""));
                return OK;
            }
            case "apply" -> {
                return report(client(input).applyRound(request(input)));
            }
            case "get" -> {
                FeedClient client = client(input);
                String feedId = input.required("feed");
                String roundText = input.required("round");
                Long height = input.has("height") ? input.longValue("height", 0) : null;
                RoundBundle bundle;
                if ("latest".equals(roundText)) {
                    bundle = client.latestRound(feedId);
                    if (bundle == null) {
                        err.println("No round of " + feedId + " has a record within " + FeedClient.LATEST_PROBE_ROUNDS
                                + " rounds of this clock's round");
                        return INVALID;
                    }
                } else {
                    bundle = client.round(feedId, FeedStarterProfile.parseRound(roundText), height);
                }
                if (input.has("output")) {
                    Files.writeString(Path.of(input.required("output")), bundle.toJson() + System.lineSeparator());
                }
                int code = show(bundle, input);
                if (input.has("output") && !input.flag("json")) {
                    out.println("Round bundle written to " + input.required("output"));
                }
                return code;
            }
            default -> throw new UsageException("round needs propose, approve, reject, apply, or get");
        }
    }

    private static RoundRequest request(Arguments input) throws IOException {
        return RoundRequest.fromJson(readText(Path.of(input.required("request"))));
    }

    private int report(FeedClient.WriteResult result) {
        AuthenticatedMapContract.Receipt receipt = result.receipt();
        if (!result.applied()) {
            err.println("Message " + result.messageIdHex() + " finalized at height " + receipt.height()
                    + " but REJECTED with error code " + receipt.errorCode() + " (" + result.errorName() + ")");
            return INVALID;
        }
        out.println("Message " + result.messageIdHex() + " applied at height " + receipt.height() + ":");
        for (AuthenticatedMapContract.MutationResult mutation : receipt.results()) {
            out.println("  " + mutation.collectionId() + "/"
                    + new String(mutation.applicationKey(), StandardCharsets.US_ASCII)
                    + " revision " + mutation.revision() + " "
                    + (mutation.status() == AuthenticatedMapContract.STATUS_ACTIVE ? "ACTIVE" : "REVOKED"));
        }
        return OK;
    }

    // ------------------------------------------------------------------ offline

    private int verify(Arguments input) throws IOException {
        return show(RoundBundle.fromJson(readText(Path.of(input.required("bundle")))), input);
    }

    private int datum(Arguments input) throws IOException {
        RoundBundle bundle = RoundBundle.fromJson(readText(Path.of(input.required("bundle"))));
        ObjectNode view = RoundView.of(bundle);
        if (view.path("datum").isNull()) {
            err.println("Round " + bundle.round() + " of " + bundle.feedId() + " is " + bundle.status()
                    + " or its record disagrees with the recomputation; a datum exists for agreed CLOSED rounds only");
            return INVALID;
        }
        JsonNode datum = view.path("datum");
        out.println(FeedDatum.DATUM_ID + " for " + bundle.feedId() + " round " + bundle.round()
                + " (a candidate for the deferred Cardano publication executor; nothing is published):");
        out.println("  hex:    " + datum.path("hex").asText());
        out.println("  sha256: " + datum.path("sha256").asText());
        out.println("  binds the record: " + datum.path("bindsRecord").asBoolean());
        return OK;
    }

    private int show(RoundBundle bundle, Arguments input) throws IOException {
        FeedVerifier.Verification verification = FeedVerifier.verify(bundle, trust(input));
        ObjectNode view = RoundView.of(bundle);
        if (input.flag("json")) {
            ObjectNode node = JSON.createObjectNode();
            node.set("round", view);
            node.set("verification", verificationNode(verification));
            out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
        } else {
            printRound(view);
            printVerification(verification);
        }
        return exitCode(verification);
    }

    private void printRound(ObjectNode view) {
        JsonNode feed = view.path("feed");
        out.println("Round " + view.path("round").asLong() + " of " + view.path("feedId").asText() + " on "
                + view.path("chainId").asText() + ": " + view.path("status").asText());
        out.println("  " + FeedStarterProfile.STARTER_NOTICE);
        out.println("  observations at height " + view.path("observationHeight").asLong() + " under root "
                + view.path("stateRoot").asText() + "; record at height " + view.path("recordHeight").asLong());
        if (feed.has("unit")) {
            out.println("  feed: " + feed.path("description").asText() + " in " + feed.path("unit").asText()
                    + ", scale " + feed.path("scale").asInt() + ", quorum " + feed.path("minimumSources").asInt()
                    + " of " + feed.path("sources").size() + ", " + feed.path("status").asText()
                    + ", window [" + feed.path("roundStart").asLong() + ", " + feed.path("roundEnd").asLong() + "]");
        }
        out.println("  sources:");
        for (JsonNode source : view.path("sources")) {
            out.println("    " + source.path("sourceId").asText() + ": " + source.path("disposition").asText()
                    + (source.has("decimal") ? " " + source.path("decimal").asText() + " at " + source.path("observedAt").asLong() : "")
                    + (source.has("revision") ? ", revision " + source.path("revision").asLong() : "")
                    + ", " + who(source.path("provenance")));
        }
        JsonNode recomputed = view.path("recomputed");
        out.println("  recomputed: " + recomputed.path("status").asText()
                + (recomputed.has("decimal") ? " " + recomputed.path("decimal").asText() + " " + recomputed.path("unit").asText()
                + " from " + recomputed.path("acceptedSources") : ""));
        JsonNode record = view.path("record");
        if (record.has("status")) {
            out.println("  record:     " + record.path("status").asText() + " " + record.path("decimal").asText()
                    + " from " + record.path("acceptedSources") + ", closed at height " + record.path("closedAtHeight").asLong()
                    + ", " + (record.path("agreesWithRecomputation").asBoolean() ? "AGREES" : "DISAGREES")
                    + ", approval consumption " + (record.path("approvalConsumption").asBoolean() ? "proven" : "absent")
                    + ", " + who(record.path("provenance")));
        } else {
            out.println("  record:     " + record.path("presence").asText());
        }
        if (!view.path("datum").isNull()) {
            out.println("  datum:      " + FeedDatum.DATUM_ID + " sha256 " + view.path("datum").path("sha256").asText());
        }
    }

    private static String who(JsonNode provenance) {
        StringBuilder text = new StringBuilder(provenance.path("kind").asText("NONE"));
        if (provenance.has("actorId")) {
            text.append(" ").append(provenance.path("actorId").asText()).append(" (")
                    .append(provenance.path("organizationId").asText()).append(", role ")
                    .append(provenance.path("role").asText()).append(")");
        }
        if (provenance.has("messageId")) {
            text.append(" message ").append(provenance.path("messageId").asText(), 0, 16).append("…");
        }
        return text.toString();
    }

    private void printVerification(FeedVerifier.Verification verification) {
        out.println("  verification: " + (verification.consistent() ? "CONSISTENT" : "FAILED")
                + ", trust " + verification.trustLevel() + ", " + verification.certSignatures()
                + " certificate signature(s) at least"
                + (verification.flags().isEmpty() ? "" : ", flags " + String.join(", ", verification.flags())));
        for (String check : verification.checks()) {
            out.println("    + " + check);
        }
        for (String failure : verification.failures()) {
            out.println("    - " + failure);
        }
    }

    private static ObjectNode verificationNode(FeedVerifier.Verification verification) {
        ObjectNode node = JSON.createObjectNode();
        node.put("consistent", verification.consistent());
        node.put("trustLevel", verification.trustLevel().name());
        node.put("certSignatures", verification.certSignatures());
        node.put("status", verification.status());
        ArrayNode flags = node.putArray("flags");
        verification.flags().forEach(flags::add);
        ArrayNode checks = node.putArray("checks");
        verification.checks().forEach(checks::add);
        ArrayNode failures = node.putArray("failures");
        verification.failures().forEach(failures::add);
        ObjectNode answers = node.putObject("answers");
        verification.answers().forEach((label, result) -> answers.put(label,
                result.consistent() ? "CONSISTENT" : "FAILED"));
        return node;
    }

    private static int exitCode(FeedVerifier.Verification verification) {
        if (!verification.consistent()) {
            return INVALID;
        }
        return switch (verification.trustLevel()) {
            case INDEPENDENTLY_VERIFIED_L1_ANCHOR -> OK;
            case CALLER_PINNED_ROOT -> PINNED;
            default -> UNPINNED;
        };
    }

    // ------------------------------------------------------------------ the mock source adapter

    /**
     * ADR-046 §4.6's mock source adapter: one deterministic observation per named source around a
     * base value, one optional outlier, signed from {@code <seeds>/<source>.seed}.
     */
    private int simulate(Arguments input) throws IOException {
        FeedClient client = client(input);
        String feedId = input.required("feed");
        long base = parseValue(input.required("base"));
        List<String> sources = List.of(input.required("sources").split(",", -1));
        Path seedDirectory = Path.of(input.required("seeds"));
        requireOwnerOnly(seedDirectory);
        Map<String, byte[]> seeds = GatewayService.loadSeeds(seedDirectory);
        long observedAt = input.longValue("observed-at", client.clock().instant().getEpochSecond());
        FeedValues.FeedValue feed = FeedValues.FeedValue.decode(client.entry(FeedStarterProfile.FEEDS,
                FeedStarterProfile.feedKey(feedId)).orElseThrow(() ->
                FeedException.invalid("feed " + feedId + " is not defined")).value());
        long round = input.has("round") ? input.longValue("round", 0) : feed.roundOf(observedAt);
        long spreadPpm = input.longValue("spread-ppm", 5_000);
        String outlier = input.option("outlier", null);
        int code = OK;
        for (String source : sources) {
            byte[] seed = seeds.get(source);
            if (seed == null) {
                throw new UsageException("no seed file for source " + source + " in " + seedDirectory);
            }
            long value = simulatedValue(feedId, round, source, base, spreadPpm, source.equals(outlier));
            FeedValues.ObservationValue observation = new FeedValues.ObservationValue(value, observedAt, null,
                    "simulated" + (source.equals(outlier) ? " outlier" : ""));
            out.println("Simulated " + source + " for " + feedId + " round " + round + ": "
                    + FeedValues.decimal(value, feed.scale()) + " " + feed.unit() + " at " + observedAt);
            int result = report(client.observe(FeedClient.Signer.of(source, seed), feedId, round, observation));
            code = Math.max(code, result);
        }
        return code;
    }

    /** {@code base ± spread} from the first bytes of {@code sha256(feed|round|source)}; an outlier sits halfway to zero. */
    static long simulatedValue(String feedId, long round, String source, long base, long spreadPpm, boolean outlier) {
        if (outlier) {
            long away = base == 0 ? 1_000 : base / 2;
            return base - away;
        }
        BigInteger spread = BigInteger.valueOf(base).abs().multiply(BigInteger.valueOf(spreadPpm))
                .divide(BigInteger.valueOf(1_000_000));
        if (spread.signum() == 0) {
            return base;
        }
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(
                    (feedId + "|" + round + "|" + source).getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
        BigInteger draw = new BigInteger(1, java.util.Arrays.copyOf(digest, 8));
        BigInteger width = spread.multiply(BigInteger.TWO).add(BigInteger.ONE);
        return BigInteger.valueOf(base).add(draw.mod(width)).subtract(spread).longValueExact();
    }

    // ------------------------------------------------------------------ services

    private int serve(Arguments input) throws Exception {
        FeedClient client = client(input);
        String bind = input.option("bind", "127.0.0.1");
        int port = (int) input.longValue("port", DEFAULT_PORTAL_PORT);
        try (PortalService service = PortalService.start(client, new InetSocketAddress(bind, port))) {
            out.println("Attestation feed portal for chain " + client.chainId() + " on " + service.baseUrl());
            out.println("  " + FeedStarterProfile.STARTER_NOTICE);
            out.println("  GET /feeds                                   the feeds the projection has seen");
            out.println("  GET /feeds/{feedId}                          the feed record and its rounds");
            out.println("  GET /feeds/{feedId}/rounds/{round}[?height=] the round view");
            out.println("  GET /feeds/{feedId}/rounds/{round}/proof     the feed-round-v1 bundle");
            out.println("  GET /feeds/{feedId}/rounds/{round}/datum     the candidate datum of a closed round");
            out.println("  GET /feeds/{feedId}/latest[/proof]           the newest round with a record");
            out.println("  GET /healthz");
            Thread.currentThread().join();
        }
        return OK;
    }

    private int gateway(Arguments input) throws Exception {
        FeedClient client = client(input);
        String bind = input.option("bind", "127.0.0.1");
        int port = (int) input.longValue("port", DEFAULT_GATEWAY_PORT);
        Path seedDirectory = Path.of(input.required("seeds"));
        requireOwnerOnly(seedDirectory);
        Map<String, byte[]> seeds = GatewayService.loadSeeds(seedDirectory);
        String token = null;
        Path tokenFile = input.has("token-file") ? Path.of(input.required("token-file")) : null;
        if (tokenFile != null && Files.exists(tokenFile)) {
            requireOwnerOnly(tokenFile);
            token = readText(tokenFile).trim();
            if (!token.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("the token file must hold 32 bytes of hex");
            }
        }
        try (GatewayService service = GatewayService.start(client, seeds, token,
                input.option("genesis-id", null), new InetSocketAddress(bind, port), input.flag("allow-remote"))) {
            if (tokenFile != null && !Files.exists(tokenFile)) {
                writeOwnerOnly(tokenFile, service.token() + System.lineSeparator());
            }
            out.println("Attestation feed signing gateway for chain " + client.chainId() + " on " + service.baseUrl());
            out.println("  " + FeedStarterProfile.STARTER_NOTICE);
            out.println("  signs for: " + String.join(", ", service.actorIds()));
            out.println("  token:     " + (tokenFile != null ? "in " + tokenFile : service.token()));
            out.println("  routes:    GET /operator/actors; POST /source/observe; POST /operator/feeds;"
                    + " POST /operator/rounds/{propose,approve,reject,apply}");
            Thread.currentThread().join();
        }
        return OK;
    }

    // ------------------------------------------------------------------ helpers

    private static FeedClient client(Arguments input) throws IOException {
        String url = input.option("url", System.getenv("YANO_FEED_URL"));
        String chain = input.option("chain", System.getenv("YANO_FEED_CHAIN"));
        if (url == null || chain == null) {
            throw new UsageException("--url and --chain are required (or YANO_FEED_URL and YANO_FEED_CHAIN)");
        }
        return FeedClient.connect(url, chain, apiKey(input));
    }

    private static String apiKey(Arguments input) throws IOException {
        if (input.has("api-key-file")) {
            return readText(Path.of(input.required("api-key-file"))).trim();
        }
        return input.option("api-key", System.getenv("YANO_API_KEY"));
    }

    private static FeedClient.Signer signer(Arguments input) throws IOException {
        return new FeedClient.Signer(input.required("actor"), seed(input),
                input.option("genesis-id", null), input.option("key-id", null));
    }

    /** A feed specification as JSON, the fields of ADR-052 §2.1; integers may be given as strings. */
    static FeedValues.FeedValue specOf(String json) throws IOException {
        JsonNode spec = JSON.readTree(json);
        if (spec == null || !spec.isObject()) {
            throw new IllegalArgumentException("the feed specification must be a JSON object");
        }
        List<String> sources = new ArrayList<>();
        for (JsonNode source : spec.path("sources")) {
            sources.add(source.asText());
        }
        return new FeedValues.FeedValue(spec.path("description").asText(""), spec.path("unit").asText(""),
                (int) integer(spec, "scale", 0), integer(spec, "epochStart", -1), integer(spec, "roundSeconds", 60),
                sources, (int) integer(spec, "minimumSources", 1), integer(spec, "maximumDeviationPpm", 0),
                integer(spec, "maximumDeviationAbsolute", 0), integer(spec, "minimumValue", FeedStarterProfile.MIN_VALUE),
                integer(spec, "maximumValue", FeedStarterProfile.MAX_VALUE),
                spec.has("status") ? FeedStarterProfile.feedStatusCode(spec.path("status").asText()) : FeedStarterProfile.FEED_ACTIVE);
    }

    static ObjectNode specNode(FeedValues.FeedValue value) {
        ObjectNode node = JSON.createObjectNode();
        node.put("description", value.description());
        node.put("unit", value.unit());
        node.put("scale", value.scale());
        node.put("epochStart", value.epochStart());
        node.put("roundSeconds", value.roundSeconds());
        ArrayNode sources = node.putArray("sources");
        value.sources().forEach(sources::add);
        node.put("minimumSources", value.minimumSources());
        node.put("maximumDeviationPpm", value.maximumDeviationPpm());
        node.put("maximumDeviationAbsolute", value.maximumDeviationAbsolute());
        node.put("minimumValue", value.minimumValue());
        node.put("maximumValue", value.maximumValue());
        node.put("status", value.statusName());
        return node;
    }

    private static long integer(JsonNode node, String field, long fallback) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return fallback;
        if (value.isIntegralNumber()) return value.longValue();
        if (value.isTextual()) return parseValue(value.textValue());
        throw new IllegalArgumentException("field " + field + " must be an integer");
    }

    static long parseValue(String text) {
        if (text == null || !text.matches("-?[0-9]{1,19}")) {
            throw new IllegalArgumentException("a value is a signed integer at the feed's scale, for example -1825");
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException overflow) {
            throw new IllegalArgumentException("value exceeds 64 bits");
        }
    }

    private static byte[] seed(Arguments input) throws IOException {
        Path file = Path.of(input.required("seed-file"));
        requireOwnerOnly(file);
        String hex = readText(file).trim();
        if (!hex.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("seed file must hold 32 bytes of hex");
        }
        return HEX.parseHex(hex.toLowerCase(Locale.ROOT));
    }

    private static void requireOwnerOnly(Path file) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            if (permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_READ)) {
                throw new IllegalArgumentException(file + " must be readable by its owner only (chmod 600)");
            }
        } catch (UnsupportedOperationException notPosix) {
            // Permission bits are not exposed on this file system.
        }
    }

    private static void writeOwnerOnly(Path file, String content) throws IOException {
        Files.writeString(file, content);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException notPosix) {
            // Permission bits are not exposed on this file system.
        }
    }

    private AttestTrust trust(Arguments input) throws IOException {
        if (input.has("members") && input.has("anchor-datum-hex")) {
            throw new UsageException("pass either --members or --anchor-datum-hex");
        }
        if (input.has("members")) {
            return AttestTrust.CallerPinned.fromJson(readText(Path.of(input.required("members"))));
        }
        if (input.has("anchor-datum-hex")) {
            String datum = input.required("anchor-datum-hex");
            if (datum.length() > MAX_DATUM_HEX_CHARS) {
                throw new IllegalArgumentException("anchor datum is too large");
            }
            return AttestTrust.IndependentAnchor.fromDatumHex(datum, input.option("application-id", null));
        }
        return AttestTrust.bundleDeclared();
    }

    private int write(String output, String content) throws IOException {
        if (output == null) {
            out.print(content);
        } else {
            Files.writeString(Path.of(output), content);
            out.println("Written to " + output);
        }
        return OK;
    }

    private static String readText(Path file) throws IOException {
        if (Files.size(file) > MAX_TEXT_FILE_BYTES) {
            throw new IllegalArgumentException(file + " exceeds " + MAX_TEXT_FILE_BYTES + " bytes");
        }
        return Files.readString(file);
    }

    static String usage() {
        return """
                yano-feed: Data attestation feed starter (ADR-052)
                  EXPERIMENTAL: a configuration-only observation ledger on the stock authenticated map.
                  Not the oracle pipeline of ADR app-layer/012; aggregates are recomputed by every
                  verifier, and nothing is published to Cardano.

                Genesis:
                  genesis       (--demo | --descriptor <file>) --members <key,...> [--threshold <n>]
                                [--chain <id>] [--chain-index <n>] [--output <properties>]
                  descriptor    --demo [--chain <id>] [--output <file>]
                  actor-key     --actor <id> --seed-file <file> [--chain <id>] [--key-id <id>]

                Node: --url <base> --chain <id> [--api-key <key> | --api-key-file <file>]
                      (or YANO_FEED_URL, YANO_FEED_CHAIN, YANO_API_KEY)
                Writes also take: --actor <id> --seed-file <file> [--genesis-id <hex>] [--key-id <id>]
                  feed create|update --feed <id> --spec <feed.json>        as a feed-admin
                  feed get        --feed <id> [--height <h>] [--json]
                  observe         --feed <id> --value <int> [--observed-at <epoch s>] [--round <n>]
                                  [--note <text>] [--evidence <file>]                 as a source
                  round propose   --feed <id> --round <n> [--height <h>] --output <request.json>
                                                                                      as a feed-operator
                  round approve|reject --request <request.json>                        as a publisher
                  round apply     --request <request.json>
                  simulate        --feed <id> --sources <a,b,c> --seeds <dir> --base <int> [--round <n>]
                                  [--observed-at <epoch s>] [--spread-ppm <n>] [--outlier <source>]

                Reads:
                  round get     --feed <id> --round <n|latest> [--height <h>] [--output <bundle.json>] [--json]
                                [--members <keys.json> | --anchor-datum-hex <cbor>]
                  verify        --bundle <bundle.json> [--members <keys.json> | --anchor-datum-hex <cbor>] [--json]
                  datum         --bundle <bundle.json>

                Services:
                  serve         [--bind 127.0.0.1] [--port 8680]
                  gateway       --seeds <dir of <actor>.seed> [--bind 127.0.0.1] [--port 8690] [--token-file <f>]
                                [--genesis-id <hex>] [--allow-remote]

                Values are signed integers at the feed's scale (-1825 at scale 2 is -18.25).
                <base> is the REST base URL including the API prefix, e.g. http://localhost:7070/api/v1.
                Seed files and directories must be owner-only (chmod 600 / 700).

                Exit codes: 0 verified with an independent anchor (or write applied); 2 usage;
                3 unavailable; 4 invalid (a rejected write, a refused approval, or a record that
                disagrees with the recomputation included); 5 verified with caller-pinned members;
                6 consistent only.
                """;
    }

    static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    /** Minimal {@code command [subcommand] --key value} parser with boolean flags. */
    static final class Arguments {
        private static final Set<String> FLAGS = Set.of("json", "demo", "allow-remote");
        private final String command;
        private final String subcommand;
        private final Map<String, String> options;
        private final List<String> flags;

        private Arguments(String command, String subcommand, Map<String, String> options, List<String> flags) {
            this.command = command;
            this.subcommand = subcommand;
            this.options = options;
            this.flags = flags;
        }

        static Arguments parse(String[] args) {
            if (args.length == 0 || args[0].startsWith("--")) {
                throw new UsageException("a command is required");
            }
            int start = 1;
            String subcommand = "";
            if (args.length > 1 && !args[1].startsWith("--")) {
                subcommand = args[1];
                start = 2;
            }
            Map<String, String> options = new LinkedHashMap<>();
            List<String> flags = new ArrayList<>();
            for (int i = start; i < args.length; i++) {
                String token = args[i];
                if (!token.startsWith("--") || token.length() < 3) {
                    throw new UsageException("unexpected argument " + token);
                }
                String key = token.substring(2);
                if (FLAGS.contains(key)) {
                    flags.add(key);
                    continue;
                }
                if (i + 1 >= args.length || (args[i + 1].startsWith("--") && !args[i + 1].matches("--?[0-9]+"))) {
                    throw new UsageException("option --" + key + " requires a value");
                }
                if (options.putIfAbsent(key, args[++i]) != null) {
                    throw new UsageException("option --" + key + " repeated");
                }
            }
            return new Arguments(args[0], subcommand, options, flags);
        }

        String command() {
            return command;
        }

        String subcommand() {
            return subcommand;
        }

        boolean has(String key) {
            return options.containsKey(key);
        }

        boolean flag(String key) {
            return flags.contains(key);
        }

        String required(String key) {
            String value = options.get(key);
            if (value == null || value.isBlank()) {
                throw new UsageException("option --" + key + " is required for " + command);
            }
            return value;
        }

        String option(String key, String fallback) {
            String value = options.get(key);
            return value != null && !value.isBlank() ? value : fallback;
        }

        long longValue(String key, long fallback) {
            String value = options.get(key);
            if (value == null) {
                return fallback;
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException malformed) {
                throw new UsageException("option --" + key + " must be an integer");
            }
        }
    }
}
