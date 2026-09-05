package com.bloxbean.cardano.yano.appchain.trust.cli;

import com.bloxbean.cardano.yano.api.appchain.proof.ProofLabVocabulary;
import com.bloxbean.cardano.yano.appchain.attest.client.AttestTrust;
import com.bloxbean.cardano.yano.appchain.roles.contracts.DirectRolePolicyV1;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.trust.client.AnswerCodec;
import com.bloxbean.cardano.yano.appchain.trust.client.RegistryService;
import com.bloxbean.cardano.yano.appchain.trust.client.StatusAnswer;
import com.bloxbean.cardano.yano.appchain.trust.client.StatusListDocument;
import com.bloxbean.cardano.yano.appchain.trust.client.TrustRegistryClient;
import com.bloxbean.cardano.yano.appchain.trust.client.TrustRegistryException;
import com.bloxbean.cardano.yano.appchain.trust.client.TrustRegistrySigner;
import com.bloxbean.cardano.yano.appchain.trust.client.TrustRegistryVerifier;
import com.bloxbean.cardano.yano.appchain.trust.profile.StatusBitstring;
import com.bloxbean.cardano.yano.appchain.trust.profile.StatusProjection;
import com.bloxbean.cardano.yano.appchain.trust.profile.TrqpEvaluator;
import com.bloxbean.cardano.yano.appchain.trust.profile.TrustRegistryGenesis;
import com.bloxbean.cardano.yano.appchain.trust.profile.TrustRegistryProfile;
import com.bloxbean.cardano.yano.appchain.trust.profile.TrustRegistryValues;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Dependency-light command line interface for the Trust and Status Registry, ADR-049 §2.2. */
public final class TrustCli {
    public static final int OK = 0;
    public static final int USAGE = 2;
    public static final int UNAVAILABLE = 3;
    public static final int INVALID = 4;
    public static final int PINNED = 5;
    public static final int UNPINNED = 6;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private static final int MAX_TEXT_FILE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_DATUM_HEX_CHARS = 32 * 1024;

    private final PrintStream out;
    private final PrintStream err;

    TrustCli(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        System.exit(new TrustCli(System.out, System.err).run(args));
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
                case "status" -> status(input);
                case "verify" -> verify(input);
                case "list" -> list(input);
                case "trqp" -> trqp(input);
                case "put" -> put(input);
                case "revoke" -> revoke(input);
                case "publish-list" -> publishList(input);
                case "serve" -> serve(input);
                default -> throw new UsageException("unknown command " + input.command());
            };
        } catch (UsageException failure) {
            err.println(failure.getMessage());
            err.println(usage());
            return USAGE;
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
            err.println("yano-trust command failed: " + failure.getMessage());
            return INVALID;
        }
    }

    // ------------------------------------------------------------------ genesis

    private int genesis(Arguments input) throws IOException {
        TrustRegistryGenesis.Descriptor descriptor = descriptorOf(input);
        List<String> members = List.of(input.required("members").split(",", -1));
        int threshold = (int) input.longValue("threshold", members.size());
        int chainIndex = (int) input.longValue("chain-index", 0);
        AuthenticatedMapContract.Genesis genesis = TrustRegistryGenesis.genesis(
                descriptor, members, threshold);
        StringBuilder lines = new StringBuilder();
        for (String line : TrustRegistryGenesis.properties(genesis, chainIndex)) {
            lines.append(line).append(System.lineSeparator());
        }
        return write(input.option("output", null), lines.toString());
    }

    private int descriptor(Arguments input) throws IOException {
        if (!input.flag("demo")) {
            throw new UsageException("descriptor prints the demo descriptor; pass --demo");
        }
        String chainId = input.option("chain", TrustRegistryGenesis.DEFAULT_CHAIN_ID);
        return write(input.option("output", null),
                TrustRegistryGenesis.toJson(TrustRegistryGenesis.demo(chainId))
                        + System.lineSeparator());
    }

    private int actorKey(Arguments input) throws IOException {
        String actorId = input.required("actor");
        String chainId = input.option("chain", TrustRegistryGenesis.DEFAULT_CHAIN_ID);
        String keyId = input.option("key-id", actorId + "-k1");
        TrustRegistryGenesis.ActorKey key = TrustRegistryGenesis.actorKey(
                chainId, actorId, keyId, seed(input));
        ObjectNode node = JSON.createObjectNode();
        node.put("chainId", chainId);
        node.put("id", actorId);
        node.put("keyId", key.keyId());
        node.put("publicKeyHex", key.publicKeyHex());
        node.put("keyProofHex", key.keyProofHex());
        return write(input.option("output", null),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node)
                        + System.lineSeparator());
    }

    private TrustRegistryGenesis.Descriptor descriptorOf(Arguments input) throws IOException {
        if (input.flag("demo")) {
            return TrustRegistryGenesis.demo(
                    input.option("chain", TrustRegistryGenesis.DEFAULT_CHAIN_ID));
        }
        if (!input.has("descriptor")) {
            throw new UsageException("pass --demo or --descriptor <file>");
        }
        return TrustRegistryGenesis.parse(readText(Path.of(input.required("descriptor"))));
    }

    // ------------------------------------------------------------------ reads

    private int status(Arguments input) throws IOException {
        TrustRegistryClient client = client(input);
        Target target = target(input);
        Long height = input.has("height") ? input.longValue("height", 0) : null;
        StatusAnswer answer = client.answer(target.collection(), target.key(), height);
        AttestTrust trust = trust(input);
        TrustRegistryVerifier.Verification verification = TrustRegistryVerifier.verify(answer, trust);
        if (input.has("output")) {
            Files.writeString(Path.of(input.required("output")),
                    AnswerCodec.toJson(answer) + System.lineSeparator());
        }
        if (input.flag("json")) {
            out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                    report(answer, verification)));
        } else {
            printAnswer(answer, verification);
            if (input.has("output")) {
                out.println("Answer written to " + input.required("output"));
            }
        }
        return exitCode(verification);
    }

    private int verify(Arguments input) throws IOException {
        StatusAnswer answer = AnswerCodec.fromJson(readText(Path.of(input.required("answer"))));
        TrustRegistryVerifier.Verification verification =
                TrustRegistryVerifier.verify(answer, trust(input));
        if (input.flag("json")) {
            out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                    report(answer, verification)));
        } else {
            printAnswer(answer, verification);
        }
        return exitCode(verification);
    }

    private int list(Arguments input) throws IOException {
        TrustRegistryClient client = client(input);
        String listId = TrustRegistryProfile.requireListId(input.required("list"));
        Long height = input.has("height") ? input.longValue("height", 0) : null;
        StatusAnswer listEntry = client.answer(TrustRegistryProfile.STATUS_LISTS,
                TrustRegistryProfile.listKey(listId), height);
        if (listEntry.presence() != StatusAnswer.Presence.ACTIVE) {
            err.println("status list " + listId + " is not published at height "
                    + listEntry.height() + " (" + listEntry.presence() + ")");
            return INVALID;
        }
        TrustRegistryValues.StatusListValue list =
                TrustRegistryValues.StatusListValue.decode(listEntry.entry().value());
        long replayTo = height != null ? height : list.publishedHeight();
        StatusProjection projection = new StatusProjection();
        client.replay(projection, replayTo);
        StatusListDocument.Rendered rendered = StatusListDocument.render(
                listId, projection, replayTo, list, listEntry, client.chainId());
        out.println("List " + listId + " purpose " + list.purpose() + ", " + list.bitLength()
                + " bits, published at height " + list.publishedHeight()
                + ", replayed to height " + replayTo);
        out.println("  set bits:       " + rendered.bitstring().setCount());
        out.println("  bitstring hash: " + rendered.bitstring().sha256Hex());
        out.println("  chain hash:     " + HEX.formatHex(list.listSha256()));
        out.println("  matches chain:  " + rendered.matchesChain());
        if (input.has("output")) {
            Files.writeString(Path.of(input.required("output")),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(rendered.document())
                            + System.lineSeparator());
            out.println("Bitstring Status List document written to " + input.required("output"));
        }
        return rendered.matchesChain() ? OK : INVALID;
    }

    private int trqp(Arguments input) throws IOException {
        TrustRegistryClient client = client(input);
        String entity = input.required("entity");
        String authorization = input.required("authorization");
        String framework = input.required("framework");
        Long height = input.has("height") ? input.longValue("height", 0) : null;
        StatusAnswer answer = client.answer(TrustRegistryProfile.ISSUERS,
                TrustRegistryProfile.issuerKey(entity), height);
        TrustRegistryValues.IssuerValue issuer = answer.presence() == StatusAnswer.Presence.ACTIVE
                ? TrustRegistryValues.IssuerValue.decode(answer.entry().value()) : null;
        TrqpEvaluator.Answer evaluated = TrqpEvaluator.evaluate(answer.presence().code(), issuer,
                framework, authorization, answer.height());
        TrustRegistryVerifier.Verification verification =
                TrustRegistryVerifier.verify(answer, trust(input));
        if (input.flag("json")) {
            ObjectNode node = JSON.createObjectNode();
            node.put("entityId", entity);
            node.put("authorizationId", authorization);
            node.put("framework", framework);
            node.put("height", answer.height());
            node.put("authorized", evaluated.authorized());
            node.put("reason", evaluated.reason());
            node.set("verification", report(answer, verification));
            out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
        } else {
            out.println((evaluated.authorized() ? "AUTHORIZED" : "NOT AUTHORIZED") + ": " + entity
                    + " for " + authorization + " under " + framework + " at height "
                    + answer.height() + " (" + evaluated.reason() + ")");
            printAnswer(answer, verification);
        }
        if (input.has("output")) {
            Files.writeString(Path.of(input.required("output")),
                    AnswerCodec.toJson(answer) + System.lineSeparator());
        }
        return exitCode(verification);
    }

    // ------------------------------------------------------------------ writes

    private int put(Arguments input) throws IOException {
        TrustRegistryClient client = client(input);
        Target target = target(input);
        byte[] value = value(input, target.collection());
        return submit(client, input, target.collection(), AuthenticatedMapContract.Command.single(
                AuthenticatedMapContract.Mutation.put(target.collection(), target.key(), value)));
    }

    private int revoke(Arguments input) throws IOException {
        TrustRegistryClient client = client(input);
        Target target = target(input);
        StatusAnswer current = client.answer(target.collection(), target.key(), null);
        if (current.presence() != StatusAnswer.Presence.ACTIVE) {
            err.println("entry is " + current.presence() + " at height " + current.height()
                    + "; only an active entry can be revoked");
            return INVALID;
        }
        return submit(client, input, target.collection(), AuthenticatedMapContract.Command.single(
                AuthenticatedMapContract.Mutation.revoke(target.collection(), target.key(),
                        current.entry().revision(), null)));
    }

    private int publishList(Arguments input) throws IOException {
        TrustRegistryClient client = client(input);
        String listId = TrustRegistryProfile.requireListId(input.required("list"));
        String purpose = input.option("purpose", "revocation");
        long bitLength = input.longValue("bit-length", TrustRegistryProfile.MIN_BIT_LENGTH);
        long tip = client.tipHeight();
        StatusProjection projection = new StatusProjection();
        long replayed = client.replay(projection, tip);
        StatusBitstring bits = projection.bitstring(listId, bitLength);
        out.println("Replayed " + projection.mutationCount() + " status write(s) to height "
                + replayed + "; list " + listId + " has " + bits.setCount() + " set bit(s), hash "
                + bits.sha256Hex());
        TrustRegistryValues.StatusListValue value = new TrustRegistryValues.StatusListValue(
                purpose, bitLength, bits.sha256(), replayed);
        return submit(client, input, TrustRegistryProfile.STATUS_LISTS,
                AuthenticatedMapContract.Command.single(AuthenticatedMapContract.Mutation.put(
                        TrustRegistryProfile.STATUS_LISTS, TrustRegistryProfile.listKey(listId),
                        value.encode())));
    }

    private int submit(TrustRegistryClient client, Arguments input, String collection,
                       AuthenticatedMapContract.Command command) throws IOException {
        String policyId = TrustRegistryProfile.policyOf(collection);
        if (policyId.equals(TrustRegistryProfile.ONBOARDING_POLICY)) {
            throw new UsageException(collection + " is written through the approval route; "
                    + "use the stock role-workflow CLIs (see the guide)");
        }
        String actorId = input.required("actor");
        byte[] seed = seed(input);
        long tip = client.tipHeight();
        TrustRegistrySigner.ActorContext actor;
        long policyRevision;
        long lifetime = TrustRegistryProfile.DIRECT_AUTHORIZATION_LIFETIME_BLOCKS;
        byte[] genesisId;
        if (tip < 1) {
            // First write on a fresh chain: no block, no readable records yet.
            if (!input.has("genesis-id")) {
                throw new UsageException("the chain has no block yet; pass --genesis-id "
                        + "(the generated state.genesis-id) and, if not <actor>-k1, --key-id");
            }
            genesisId = hex32(input.required("genesis-id"), "genesis-id");
            byte[] publicKey = com.bloxbean.cardano.client.crypto.KeyGenUtil
                    .getPublicKeyFromPrivateKey(seed);
            actor = new TrustRegistrySigner.ActorContext(actorId, 1,
                    input.option("key-id", actorId + "-k1"), publicKey, seed);
            policyRevision = 1;
        } else {
            genesisId = input.has("genesis-id")
                    ? hex32(input.required("genesis-id"), "genesis-id") : client.mapGenesisId();
            actor = TrustRegistrySigner.actorContext(client.actor(actorId), seed, tip);
            DirectRolePolicyV1 policy = client.directPolicy(policyId);
            policyRevision = policy.revision();
            lifetime = Math.min(lifetime, policy.maximumAuthorizationLifetimeBlocks());
        }
        long issued = Math.max(tip, 1);
        byte[] bytes = TrustRegistrySigner.governedCommand(command, policyId, policyRevision,
                actor, client.chainId(), genesisId, issued, issued + lifetime,
                TrustRegistrySigner.randomAuthorizationId());
        String messageId = client.submit(bytes);
        out.println("Submitted message " + messageId + " as " + actorId + " under policy "
                + policyId + " revision " + policyRevision);
        Duration timeout = Duration.ofSeconds(input.longValue("timeout-seconds", 60));
        AuthenticatedMapContract.Receipt receipt = client.awaitReceipt(messageId, timeout);
        if (receipt.status() != AuthenticatedMapContract.RECEIPT_APPLIED) {
            err.println("Finalized at height " + receipt.height() + " but REJECTED with error code "
                    + receipt.errorCode() + " (" + errorName(receipt.errorCode()) + ")");
            return INVALID;
        }
        out.println("Applied at height " + receipt.height() + ":");
        for (AuthenticatedMapContract.MutationResult result : receipt.results()) {
            out.println("  " + result.collectionId() + "/"
                    + new String(result.applicationKey(), StandardCharsets.US_ASCII)
                    + " revision " + result.revision() + " "
                    + (result.status() == AuthenticatedMapContract.STATUS_ACTIVE ? "ACTIVE" : "REVOKED"));
        }
        return OK;
    }

    private int serve(Arguments input) throws Exception {
        TrustRegistryClient client = client(input);
        String bind = input.option("bind", "127.0.0.1");
        int port = (int) input.longValue("port", 8480);
        try (RegistryService service = RegistryService.start(client,
                new InetSocketAddress(bind, port))) {
            out.println("Trust registry service for chain " + client.chainId() + " on "
                    + service.baseUrl());
            out.println("  GET /status-lists/{listId}[?height=]");
            out.println("  GET /trqp/entities/{entityId}/authorizations/{authorizationId}?framework=");
            out.println("  GET /entries/{collection}/{keyHex}[?height=]");
            out.println("  GET /healthz");
            Thread.currentThread().join();
        }
        return OK;
    }

    // ------------------------------------------------------------------ helpers

    private record Target(String collection, byte[] key) {
    }

    private static Target target(Arguments input) {
        if (input.has("subject")) {
            return new Target(TrustRegistryProfile.SUBJECTS,
                    TrustRegistryProfile.subjectKey(input.required("subject")));
        }
        if (input.has("issuer")) {
            return new Target(TrustRegistryProfile.ISSUERS,
                    TrustRegistryProfile.issuerKey(input.required("issuer")));
        }
        if (input.has("list") && !input.has("index")) {
            return new Target(TrustRegistryProfile.STATUS_LISTS,
                    TrustRegistryProfile.listKey(input.required("list")));
        }
        if (input.has("list")) {
            return new Target(TrustRegistryProfile.STATUS, TrustRegistryProfile.statusKey(
                    input.required("list"), input.longValue("index", -1)));
        }
        if (input.has("schema")) {
            return new Target(TrustRegistryProfile.SCHEMAS,
                    TrustRegistryProfile.schemaKey(input.required("schema")));
        }
        if (input.has("collection")) {
            String collection = input.required("collection");
            TrustRegistryProfile.policyOf(collection);
            byte[] key = input.has("key-hex") ? HEX.parseHex(input.required("key-hex"))
                    : input.required("key").getBytes(StandardCharsets.UTF_8);
            return new Target(collection, key);
        }
        throw new UsageException("name the entry: --subject, --issuer, --list [--index], "
                + "--schema, or --collection with --key/--key-hex");
    }

    private static byte[] value(Arguments input, String collection) throws IOException {
        if (input.has("value-hex")) {
            return HEX.parseHex(input.required("value-hex"));
        }
        switch (collection) {
            case TrustRegistryProfile.STATUS -> {
                return new TrustRegistryValues.StatusValue((int) input.longValue("bit", -1),
                        (int) input.longValue("reason", 0)).encode();
            }
            case TrustRegistryProfile.SUBJECTS -> {
                return new TrustRegistryValues.SubjectValue(input.required("controller"),
                        input.required("kind"),
                        hex32(input.required("metadata-hash"), "metadata-hash")).encode();
            }
            case TrustRegistryProfile.SCHEMAS -> {
                return Files.readAllBytes(Path.of(input.required("value-file")));
            }
            default -> throw new UsageException("pass --value-hex, or the collection's fields "
                    + "(--bit/--reason for status, --controller/--kind/--metadata-hash for "
                    + "subjects, --value-file for schemas)");
        }
    }

    private static TrustRegistryClient client(Arguments input) throws IOException {
        return TrustRegistryClient.builder(input.required("url"), input.required("chain"))
                .apiKey(apiKey(input)).build();
    }

    private static String apiKey(Arguments input) throws IOException {
        if (input.has("api-key-file")) {
            return readText(Path.of(input.required("api-key-file"))).trim();
        }
        String environment = System.getenv("YANO_API_KEY");
        return input.option("api-key", environment);
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
                throw new IllegalArgumentException("seed file " + file
                        + " must be readable by its owner only (chmod 600)");
            }
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
            return AttestTrust.IndependentAnchor.fromDatumHex(datum,
                    input.option("application-id", null));
        }
        return AttestTrust.bundleDeclared();
    }

    private static int exitCode(TrustRegistryVerifier.Verification verification) {
        if (!verification.consistent()) {
            return INVALID;
        }
        return switch (verification.trustLevel()) {
            case INDEPENDENTLY_VERIFIED_L1_ANCHOR -> OK;
            case CALLER_PINNED_ROOT -> PINNED;
            default -> UNPINNED;
        };
    }

    private void printAnswer(StatusAnswer answer, TrustRegistryVerifier.Verification verification) {
        String key = answer.keyText() != null ? answer.keyText() : answer.keyHex();
        out.println(answer.collection() + "/" + key + " on " + answer.chainId() + " at height "
                + answer.height() + ": " + answer.presence());
        out.println("  state root:   " + answer.stateRootHex());
        out.println("  block hash:   " + answer.blockHashHex());
        if (answer.entry() != null) {
            out.println("  revision:     " + answer.entry().revision()
                    + " (created " + answer.entry().createdHeight()
                    + ", last mutation " + answer.entry().lastMutationHeight() + ")");
            JsonNode decoded = AnswerCodec.decodedValue(answer.collection(), answer.entry());
            if (decoded != null) {
                out.println("  value:        " + decoded);
            } else if (answer.entry().value().length > 0) {
                out.println("  value hex:    " + HEX.formatHex(answer.entry().value()));
            }
        }
        StatusAnswer.Provenance provenance = answer.provenance();
        StringBuilder who = new StringBuilder("  provenance:   ").append(provenance.kind());
        if (provenance.messageIdHex() != null) {
            who.append(", message ").append(provenance.messageIdHex())
                    .append(" applied at ").append(provenance.appliedHeight());
        }
        if (provenance.actorId() != null) {
            who.append(", by ").append(provenance.actorId()).append(" (")
                    .append(provenance.organizationId()).append(", role ")
                    .append(provenance.role()).append(", key ").append(provenance.keyId())
                    .append(") under ").append(provenance.policyId()).append(" revision ")
                    .append(provenance.policyRevision());
        }
        out.println(who);
        out.println("  verification: " + (verification.consistent() ? "CONSISTENT" : "FAILED")
                + ", trust " + verification.trustLevel()
                + ", " + verification.certSignatures() + " certificate signature(s)");
        for (String check : verification.checks()) {
            out.println("    + " + check);
        }
        for (String failure : verification.failures()) {
            out.println("    - " + failure);
        }
    }

    private static ObjectNode report(StatusAnswer answer,
                                     TrustRegistryVerifier.Verification verification) {
        ObjectNode node = JSON.createObjectNode();
        node.put("chainId", answer.chainId());
        node.put("collection", answer.collection());
        node.put("keyHex", answer.keyHex());
        if (answer.keyText() != null) node.put("key", answer.keyText());
        node.put("height", answer.height());
        node.put("stateRoot", answer.stateRootHex());
        node.put("presence", answer.presence().name());
        node.put("provenance", answer.provenance().kind().name());
        if (answer.entry() != null) {
            node.put("revision", answer.entry().revision());
            JsonNode decoded = AnswerCodec.decodedValue(answer.collection(), answer.entry());
            if (decoded != null) node.set("decoded", decoded);
        }
        node.put("consistent", verification.consistent());
        node.put("trustLevel", verification.trustLevel().name());
        node.put("certSignatures", verification.certSignatures());
        ArrayNode checks = node.putArray("checks");
        verification.checks().forEach(checks::add);
        ArrayNode failures = node.putArray("failures");
        verification.failures().forEach(failures::add);
        return node;
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

    private static byte[] hex32(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("--" + name + " must be 32 bytes of lowercase hex");
        }
        return HEX.parseHex(value);
    }

    static String errorName(int code) {
        Map<Integer, String> names = new LinkedHashMap<>();
        names.put(AuthenticatedMapContract.ERROR_UNKNOWN_COLLECTION, "UNKNOWN_COLLECTION");
        names.put(AuthenticatedMapContract.ERROR_COLLECTION_BOUNDS, "COLLECTION_BOUNDS");
        names.put(AuthenticatedMapContract.ERROR_UNAUTHORIZED, "UNAUTHORIZED");
        names.put(AuthenticatedMapContract.ERROR_ALREADY_EXISTS, "ALREADY_EXISTS");
        names.put(AuthenticatedMapContract.ERROR_ABSENT, "ABSENT");
        names.put(AuthenticatedMapContract.ERROR_REVOKED, "REVOKED");
        names.put(AuthenticatedMapContract.ERROR_ACTIVE, "ACTIVE");
        names.put(AuthenticatedMapContract.ERROR_PRECONDITION, "PRECONDITION");
        names.put(AuthenticatedMapContract.ERROR_RESTORE_FORBIDDEN, "RESTORE_FORBIDDEN");
        names.put(AuthenticatedMapContract.ERROR_VALUE_ENCODING, "VALUE_ENCODING");
        names.put(AuthenticatedMapContract.ERROR_VALUE_SCHEMA, "VALUE_SCHEMA");
        names.put(AuthenticatedMapContract.ERROR_VALUE_VALIDATOR, "VALUE_VALIDATOR");
        names.put(AuthenticatedMapContract.ERROR_AUTHORIZATION_ASSIGNMENT, "AUTHORIZATION_ASSIGNMENT");
        names.put(AuthenticatedMapContract.ERROR_UNKNOWN_POLICY, "UNKNOWN_POLICY");
        names.put(AuthenticatedMapContract.ERROR_POLICY_INACTIVE, "POLICY_INACTIVE");
        names.put(AuthenticatedMapContract.ERROR_ACTOR_INELIGIBLE, "ACTOR_INELIGIBLE");
        names.put(AuthenticatedMapContract.ERROR_ACTOR_SIGNATURE, "ACTOR_SIGNATURE");
        names.put(AuthenticatedMapContract.ERROR_AUTHORIZATION_DEADLINE, "AUTHORIZATION_DEADLINE");
        names.put(AuthenticatedMapContract.ERROR_DIRECT_AUTHORIZATION_REPLAY, "DIRECT_AUTHORIZATION_REPLAY");
        names.put(AuthenticatedMapContract.ERROR_APPROVAL_NOT_APPROVED, "APPROVAL_NOT_APPROVED");
        names.put(AuthenticatedMapContract.ERROR_APPROVAL_MISMATCH, "APPROVAL_MISMATCH");
        names.put(AuthenticatedMapContract.ERROR_APPROVAL_REPLAY, "APPROVAL_REPLAY");
        names.put(AuthenticatedMapContract.ERROR_CAPACITY_EXCEEDED, "CAPACITY_EXCEEDED");
        names.put(AuthenticatedMapContract.ERROR_CRYPTO_WORK_EXCEEDED, "CRYPTO_WORK_EXCEEDED");
        names.put(AuthenticatedMapContract.ERROR_GOVERNED_ROUTE_UNSUPPORTED, "GOVERNED_ROUTE_UNSUPPORTED");
        names.put(AuthenticatedMapContract.ERROR_WRONG_GENESIS, "WRONG_GENESIS");
        names.put(AuthenticatedMapContract.ERROR_WRONG_REVISION, "WRONG_REVISION");
        return names.getOrDefault(code, "UNKNOWN");
    }

    static String usage() {
        return """
                yano-trust: Trust and Status Registry client (ADR-049)

                Genesis:
                  genesis      (--demo | --descriptor <file>) --members <key,...> [--threshold <n>]
                               [--chain <id>] [--chain-index <n>] [--output <properties>]
                  descriptor   --demo [--chain <id>] [--output <file>]
                  actor-key    --actor <id> --seed-file <file> [--chain <id>] [--key-id <id>]

                Reads (node: --url <base> --chain <id> [--api-key <key> | --api-key-file <file>]):
                  status       <entry> [--height <h>] [--members <keys.json> | --anchor-datum-hex <cbor>]
                               [--output <answer.json>] [--json]
                  list         --list <id> [--height <h>] [--output <status-list.json>]
                  trqp         --entity <id> --authorization <id> --framework <id> [--height <h>]
                               [--members <keys.json>] [--output <answer.json>] [--json]
                  verify       --answer <answer.json> [--members <keys.json> | --anchor-datum-hex <cbor>]
                               [--application-id <id>] [--json]

                Writes (--actor <id> --seed-file <file> [--genesis-id <hex>] [--key-id <id>]):
                  put          <entry> (--value-hex <hex> | --bit 0|1 [--reason <n>]
                               | --controller <org> --kind <text> --metadata-hash <hex> | --value-file <f>)
                  revoke       <entry>
                  publish-list --list <id> [--purpose revocation] [--bit-length 131072]

                Service:
                  serve        [--bind 127.0.0.1] [--port 8480]

                <entry> is --subject <id> | --issuer <id> | --list <id> | --list <id> --index <n>
                | --schema <id> | --collection <c> (--key <text> | --key-hex <hex>).
                <base> is the REST base URL including the API prefix, e.g. http://localhost:7070/api/v1.
                YANO_API_KEY is read when no --api-key is given. Seed files must be chmod 600.

                Exit codes: 0 verified with an independent anchor (or write applied); 2 usage;
                3 unavailable; 4 invalid; 5 verified with caller-pinned members; 6 consistent only.
                """;
    }

    static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    /** Minimal {@code command --key value} parser with boolean flags. */
    static final class Arguments {
        private static final Set<String> FLAGS = Set.of("json", "demo");
        private final String command;
        private final Map<String, String> options;
        private final List<String> flags;

        private Arguments(String command, Map<String, String> options, List<String> flags) {
            this.command = command;
            this.options = options;
            this.flags = flags;
        }

        static Arguments parse(String[] args) {
            if (args.length == 0 || args[0].startsWith("--")) {
                throw new UsageException("a command is required");
            }
            Map<String, String> options = new LinkedHashMap<>();
            List<String> flags = new ArrayList<>();
            for (int i = 1; i < args.length; i++) {
                String token = args[i];
                if (!token.startsWith("--") || token.length() < 3) {
                    throw new UsageException("unexpected argument " + token);
                }
                String key = token.substring(2);
                if (FLAGS.contains(key)) {
                    flags.add(key);
                    continue;
                }
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new UsageException("option --" + key + " requires a value");
                }
                if (options.putIfAbsent(key, args[++i]) != null) {
                    throw new UsageException("option --" + key + " repeated");
                }
            }
            return new Arguments(args[0], options, flags);
        }

        String command() {
            return command;
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
