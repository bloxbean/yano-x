package com.bloxbean.cardano.yano.appchain.dpp.cli;

import com.bloxbean.cardano.yano.appchain.attest.client.AttestTrust;
import com.bloxbean.cardano.yano.appchain.dpp.client.CertificationRequest;
import com.bloxbean.cardano.yano.appchain.dpp.client.Disclosure;
import com.bloxbean.cardano.yano.appchain.dpp.client.DocumentStore;
import com.bloxbean.cardano.yano.appchain.dpp.client.DppClient;
import com.bloxbean.cardano.yano.appchain.dpp.client.DppException;
import com.bloxbean.cardano.yano.appchain.dpp.client.GatewayService;
import com.bloxbean.cardano.yano.appchain.dpp.client.PassportBundle;
import com.bloxbean.cardano.yano.appchain.dpp.client.PassportVerifier;
import com.bloxbean.cardano.yano.appchain.dpp.client.PassportView;
import com.bloxbean.cardano.yano.appchain.dpp.client.PortalService;
import com.bloxbean.cardano.yano.appchain.dpp.profile.DppGenesis;
import com.bloxbean.cardano.yano.appchain.dpp.profile.DppStarterProfile;
import com.bloxbean.cardano.yano.appchain.dpp.profile.DppValues;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.trust.client.TrustRegistryException;
import com.bloxbean.cardano.yano.appchain.trust.profile.TrustRegistryGenesis;
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
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Dependency-light command line interface for the DPP starter, ADR-051 §2.3. A prototype. */
public final class DppCli {
    public static final int OK = 0;
    public static final int USAGE = 2;
    public static final int UNAVAILABLE = 3;
    public static final int INVALID = 4;
    public static final int PINNED = 5;
    public static final int UNPINNED = 6;
    public static final int DEFAULT_PORTAL_PORT = 8580;
    public static final int DEFAULT_GATEWAY_PORT = 8590;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private static final int MAX_TEXT_FILE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_DATUM_HEX_CHARS = 32 * 1024;

    private final PrintStream out;
    private final PrintStream err;

    DppCli(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        System.exit(new DppCli(System.out, System.err).run(args));
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
                case "register" -> register(input);
                case "publish-version" -> publishVersion(input);
                case "set-status" -> setStatus(input);
                case "revoke" -> revoke(input);
                case "claim" -> claim(input);
                case "event" -> event(input);
                case "certify" -> certify(input);
                case "passport" -> passport(input);
                case "verify" -> verify(input);
                case "disclose" -> disclose(input);
                case "document" -> document(input);
                case "serve" -> serve(input);
                case "gateway" -> gateway(input);
                default -> throw new UsageException("unknown command " + input.command());
            };
        } catch (UsageException usage) {
            err.println(usage.getMessage());
            err.println(usage());
            return USAGE;
        } catch (DppException failure) {
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
            err.println("yano-dpp command failed: " + failure.getMessage());
            return INVALID;
        }
    }

    // ------------------------------------------------------------------ genesis

    private int genesis(Arguments input) throws IOException {
        TrustRegistryGenesis.Descriptor descriptor = descriptorOf(input);
        List<String> members = List.of(input.required("members").split(",", -1));
        int threshold = (int) input.longValue("threshold", members.size());
        int chainIndex = (int) input.longValue("chain-index", 0);
        AuthenticatedMapContract.Genesis genesis = DppGenesis.genesis(descriptor, members, threshold);
        StringBuilder lines = new StringBuilder();
        for (String line : DppGenesis.properties(genesis, chainIndex)) {
            lines.append(line).append(System.lineSeparator());
        }
        return write(input.option("output", null), lines.toString());
    }

    private int descriptor(Arguments input) throws IOException {
        if (!input.flag("demo")) {
            throw new UsageException("descriptor prints the demo descriptor; pass --demo");
        }
        String chainId = input.option("chain", DppGenesis.DEFAULT_CHAIN_ID);
        return write(input.option("output", null),
                TrustRegistryGenesis.toJson(DppGenesis.demo(chainId)) + System.lineSeparator());
    }

    private int actorKey(Arguments input) throws IOException {
        String actorId = input.required("actor");
        String chainId = input.option("chain", DppGenesis.DEFAULT_CHAIN_ID);
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
            return DppGenesis.demo(input.option("chain", DppGenesis.DEFAULT_CHAIN_ID));
        }
        if (!input.has("descriptor")) {
            throw new UsageException("pass --demo or --descriptor <file>");
        }
        return DppGenesis.requireStarterDescriptor(
                TrustRegistryGenesis.parse(readText(Path.of(input.required("descriptor")))));
    }

    // ------------------------------------------------------------------ writes

    private int register(Arguments input) throws IOException {
        DppClient client = client(input);
        String productId = input.required("product");
        int status = input.has("status") ? DppStarterProfile.statusCode(input.required("status"))
                : DppStarterProfile.STATUS_DRAFT;
        DppValues.ProductValue value = new DppValues.ProductValue(
                organizationOf(client, input, "manufacturer-org"), status, 0, "",
                input.required("profile"));
        return report(client.registerProduct(signer(client, input), productId, value));
    }

    private int publishVersion(Arguments input) throws IOException {
        DppClient client = client(input);
        String productId = input.required("product");
        long version = input.longValue("version", 0);
        Path file = Path.of(input.required("document"));
        byte[] document = readBytes(file);
        String sha256 = documents(input).put(document);
        DppValues.VersionValue value = new DppValues.VersionValue(HEX.parseHex(sha256),
                input.option("media-type", mediaTypeOf(file)), input.option("reference", ""),
                document.length);
        out.println("Document " + file + " staged as " + sha256 + " (" + document.length + " bytes)");
        return report(client.publishVersion(signer(client, input), productId, version, value));
    }

    private int setStatus(Arguments input) throws IOException {
        DppClient client = client(input);
        int status = DppStarterProfile.statusCode(input.required("status"));
        if (status == DppStarterProfile.STATUS_DRAFT) {
            throw new UsageException("a registered product cannot return to DRAFT");
        }
        return report(client.setStatus(signer(client, input), input.required("product"), status,
                input.option("successor", "")));
    }

    private int revoke(Arguments input) throws IOException {
        DppClient client = client(input);
        return report(client.revokeProduct(signer(client, input), input.required("product")));
    }

    private int claim(Arguments input) throws IOException {
        DppClient client = client(input);
        String productId = input.required("product");
        String claimType = input.required("type");
        String claimId = input.required("id");
        String text = input.required("text");
        byte[] evidence = input.has("evidence") ? DppValues.sha256(readBytes(Path.of(input.required("evidence")))) : null;
        String organization = organizationOf(client, input, "org");
        Disclosure disclosure = null;
        byte[] value;
        if (input.flag("committed")) {
            if (!input.has("disclosure-output")) {
                throw new UsageException("--committed needs --disclosure-output <file> for the salt and text");
            }
            disclosure = Disclosure.create(client.chainId(), productId, claimType, claimId, text);
            value = disclosure.commitment();
        } else {
            value = text.getBytes(StandardCharsets.UTF_8);
        }
        DppValues.ClaimValue claim = new DppValues.ClaimValue(
                disclosure != null ? DppStarterProfile.VISIBILITY_COMMITTED : DppStarterProfile.VISIBILITY_PUBLIC,
                value, organization, input.longValue("valid-from", 0), input.longValue("valid-until", 0),
                evidence);
        if (disclosure != null) {
            writeOwnerOnly(Path.of(input.required("disclosure-output")), disclosure.toJson() + System.lineSeparator());
            out.println("Disclosure (salt and text) written to " + input.required("disclosure-output")
                    + "; commitment " + HEX.formatHex(disclosure.commitment()));
        }
        return report(client.putClaim(signer(client, input), productId, claimType, claimId, claim));
    }

    private int event(Arguments input) throws IOException {
        DppClient client = client(input);
        String productId = input.required("product");
        long observedAt = input.longValue("observed-at", System.currentTimeMillis() / 1000);
        String eventId = input.option("id", "e-" + observedAt + "-" + HEX.formatHex(randomBytes(4)));
        byte[] evidence = input.has("evidence") ? DppValues.sha256(readBytes(Path.of(input.required("evidence")))) : null;
        DppValues.EventValue value = new DppValues.EventValue(input.required("type"),
                organizationOf(client, input, "org"), observedAt, input.option("location", ""),
                evidence, input.option("note", ""));
        out.println("Event id " + eventId);
        return report(client.appendEvent(signer(client, input), productId, eventId, value));
    }

    private int certify(Arguments input) throws IOException {
        DppClient client = client(input);
        switch (input.subcommand()) {
            case "propose" -> {
                CertificationRequest request;
                if (input.flag("revoke")) {
                    request = client.proposeRevocation(signer(client, input), input.required("certificate"));
                } else {
                    DppValues.CertificateValue value = new DppValues.CertificateValue(
                            input.required("product"), input.required("type"),
                            organizationOf(client, input, "org"),
                            DppValues.sha256(readBytes(Path.of(input.required("evidence")))),
                            input.longValue("valid-from", 0), input.longValue("valid-until", 0));
                    request = client.proposeCertificate(signer(client, input),
                            input.required("certificate"), value);
                }
                out.println("Proposed " + request.operation() + " of certificate " + request.certificateId()
                        + " as proposal " + request.proposalId() + " (message " + request.proposeMessageIdHex()
                        + ", deadline height " + request.deadlineHeight() + ")");
                return write(input.required("output"), request.toJson() + System.lineSeparator());
            }
            case "approve", "reject" -> {
                CertificationRequest request = request(input);
                boolean approve = "approve".equals(input.subcommand());
                String messageId = client.decideCertification(signer(client, input), request, approve);
                out.println((approve ? "Approved" : "Rejected") + " proposal " + request.proposalId()
                        + " as " + input.required("actor") + " (message " + messageId + ")");
                return OK;
            }
            case "apply" -> {
                return report(client.applyCertification(request(input)));
            }
            default -> throw new UsageException("certify needs propose, approve, reject, or apply");
        }
    }

    private static CertificationRequest request(Arguments input) throws IOException {
        return CertificationRequest.fromJson(readText(Path.of(input.required("request"))));
    }

    private int report(DppClient.WriteResult result) {
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

    // ------------------------------------------------------------------ reads

    private int passport(Arguments input) throws IOException {
        DppClient client = client(input);
        Long height = input.has("height") ? input.longValue("height", 0) : null;
        PassportBundle bundle = client.passport(input.required("product"), height);
        if (input.has("output")) {
            Files.writeString(Path.of(input.required("output")), bundle.toJson() + System.lineSeparator());
        }
        int code = show(bundle, input);
        if (input.has("output") && !input.flag("json")) {
            out.println("Passport bundle written to " + input.required("output"));
        }
        return code;
    }

    private int verify(Arguments input) throws IOException {
        return show(PassportBundle.fromJson(readText(Path.of(input.required("passport")))), input);
    }

    private int disclose(Arguments input) throws IOException {
        PassportBundle bundle = PassportBundle.fromJson(readText(Path.of(input.required("passport"))));
        Disclosure disclosure = Disclosure.fromJson(readText(Path.of(input.required("disclosure"))));
        PassportVerifier.Verification verification = PassportVerifier.verify(bundle, trust(input));
        Disclosure.Check check = disclosure.check(bundle);
        out.println("Disclosure of " + disclosure.claimType() + "/" + disclosure.claimId() + " on "
                + disclosure.productId() + ": " + check.outcome() + " (" + check.message() + ")");
        if (check.matches()) {
            out.println("  disclosed text: " + disclosure.text());
        }
        printVerification(verification);
        int code = exitCode(verification);
        return code == INVALID || !check.matches() ? INVALID : code;
    }

    private int document(Arguments input) throws IOException {
        String sha256 = input.required("sha256");
        byte[] bytes = documents(input).get(sha256).orElseThrow(() ->
                DppException.invalid("no document " + sha256 + " in the content directory"));
        Files.write(Path.of(input.required("output")), bytes);
        out.println("Document " + sha256 + " (" + bytes.length + " bytes) written to " + input.required("output"));
        return OK;
    }

    private int show(PassportBundle bundle, Arguments input) throws IOException {
        DocumentStore store = input.has("content-dir") || input.command().equals("passport")
                ? documents(input) : null;
        PassportVerifier.Verification verification = PassportVerifier.verify(bundle, trust(input));
        ObjectNode view = PassportView.of(bundle, sha -> store != null && store.has(sha));
        if (input.flag("json")) {
            ObjectNode node = JSON.createObjectNode();
            node.set("passport", view);
            node.set("verification", verificationNode(verification));
            out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
        } else {
            printPassport(view);
            printVerification(verification);
        }
        return exitCode(verification);
    }

    private void printPassport(ObjectNode view) {
        out.println("Passport " + view.path("productId").asText() + " on " + view.path("chainId").asText()
                + " at height " + view.path("height").asLong() + ": " + view.path("status").asText());
        out.println("  " + DppStarterProfile.PROTOTYPE_NOTICE);
        out.println("  state root:   " + view.path("stateRoot").asText());
        out.println("  block hash:   " + view.path("blockHash").asText());
        JsonNode product = view.path("product");
        if (product.has("manufacturerOrganizationId")) {
            out.println("  manufacturer: " + product.path("manufacturerOrganizationId").asText()
                    + ", passport profile " + product.path("passportProfileId").asText()
                    + ", current version " + product.path("currentVersion").asLong()
                    + (product.path("successorProductId").asText("").isEmpty() ? ""
                    : ", successor " + product.path("successorProductId").asText()));
        }
        out.println("  written by:   " + who(product.path("provenance")));
        flags("  flags:        ", view.path("flags"));
        out.println("  versions (" + view.path("versions").size() + "):");
        for (JsonNode version : view.path("versions")) {
            out.println("    v" + version.path("version").asLong() + (version.path("current").asBoolean() ? " [current]" : "")
                    + " " + version.path("presence").asText() + " sha256 " + version.path("documentSha256").asText("-")
                    + " " + version.path("mediaType").asText("") + " " + version.path("byteLength").asLong()
                    + " bytes, " + version.path("availability").asText("") + ", revision "
                    + version.path("revision").asLong() + ", by " + who(version.path("provenance")));
            flags("      flags: ", version.path("flags"));
        }
        out.println("  claims (" + view.path("claims").size() + "):");
        for (JsonNode claim : view.path("claims")) {
            String value = claim.has("text") ? "\"" + claim.path("text").asText() + "\""
                    : "commitment " + claim.path("commitment").asText("-");
            out.println("    " + claim.path("claimType").asText() + "/" + claim.path("claimId").asText()
                    + " " + claim.path("presence").asText() + " " + claim.path("visibility").asText("")
                    + " " + value + " by " + claim.path("issuerOrganizationId").asText("")
                    + ", validity " + claim.path("validity").asText("") + ", " + who(claim.path("provenance")));
            flags("      flags: ", claim.path("flags"));
        }
        out.println("  events (" + view.path("events").size() + ", ledger order):");
        for (JsonNode event : view.path("events")) {
            out.println("    " + event.path("eventType").asText("?") + " by " + event.path("actorOrganizationId").asText("")
                    + " at " + event.path("observedAt").asLong() + " " + event.path("location").asText("")
                    + (event.path("note").asText("").isEmpty() ? "" : " (" + event.path("note").asText() + ")")
                    + ", height " + event.path("lastMutationHeight").asLong() + ", " + who(event.path("provenance")));
            flags("      flags: ", event.path("flags"));
        }
        out.println("  certificates (" + view.path("certificates").size() + "):");
        for (JsonNode certificate : view.path("certificates")) {
            out.println("    " + certificate.path("certificateId").asText() + " " + certificate.path("presence").asText()
                    + " " + certificate.path("certificateType").asText("") + " by "
                    + certificate.path("issuerOrganizationId").asText("") + ", validity "
                    + certificate.path("validity").asText("") + ", approval consumption "
                    + (certificate.path("approvalConsumption").asBoolean() ? "proven" : "absent")
                    + ", " + who(certificate.path("provenance")));
            flags("      flags: ", certificate.path("flags"));
        }
        out.println("  timeline: " + view.path("timeline").size() + " applied mutation(s)");
    }

    private void flags(String prefix, JsonNode flags) {
        if (flags.isArray() && !flags.isEmpty()) {
            List<String> names = new ArrayList<>();
            flags.forEach(flag -> names.add(flag.asText()));
            out.println(prefix + String.join(", ", names));
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

    private void printVerification(PassportVerifier.Verification verification) {
        out.println("  verification: " + (verification.consistent() ? "CONSISTENT" : "FAILED")
                + ", trust " + verification.trustLevel() + ", " + verification.certSignatures()
                + " certificate signature(s) at least");
        for (String check : verification.checks()) {
            out.println("    + " + check);
        }
        for (String failure : verification.failures()) {
            out.println("    - " + failure);
        }
    }

    private static ObjectNode verificationNode(PassportVerifier.Verification verification) {
        ObjectNode node = JSON.createObjectNode();
        node.put("consistent", verification.consistent());
        node.put("trustLevel", verification.trustLevel().name());
        node.put("certSignatures", verification.certSignatures());
        ArrayNode checks = node.putArray("checks");
        verification.checks().forEach(checks::add);
        ArrayNode failures = node.putArray("failures");
        verification.failures().forEach(failures::add);
        ObjectNode answers = node.putObject("answers");
        verification.answers().forEach((label, result) -> answers.put(label,
                result.consistent() ? "CONSISTENT" : "FAILED"));
        return node;
    }

    private static int exitCode(PassportVerifier.Verification verification) {
        if (!verification.consistent()) {
            return INVALID;
        }
        return switch (verification.trustLevel()) {
            case INDEPENDENTLY_VERIFIED_L1_ANCHOR -> OK;
            case CALLER_PINNED_ROOT -> PINNED;
            default -> UNPINNED;
        };
    }

    // ------------------------------------------------------------------ services

    private int serve(Arguments input) throws Exception {
        DppClient client = client(input);
        String bind = input.option("bind", "127.0.0.1");
        int port = (int) input.longValue("port", DEFAULT_PORTAL_PORT);
        try (PortalService service = PortalService.start(client, documents(input),
                new InetSocketAddress(bind, port))) {
            out.println("DPP starter portal for chain " + client.chainId() + " on " + service.baseUrl());
            out.println("  " + DppStarterProfile.PROTOTYPE_NOTICE);
            out.println("  GET /passports/{productId}[?height=]      the passport view");
            out.println("  GET /passports/{productId}/proof          the dpp-passport-v1 bundle");
            out.println("  GET /01/{gtin}[/21/{serial}]              GS1 Digital Link resolver");
            out.println("  GET /documents/{sha256}                   an archived document");
            out.println("  GET /healthz");
            Thread.currentThread().join();
        }
        return OK;
    }

    private int gateway(Arguments input) throws Exception {
        DppClient client = client(input);
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
        try (GatewayService service = GatewayService.start(client, documents(input), seeds, token,
                input.option("genesis-id", null), new InetSocketAddress(bind, port), input.flag("allow-remote"))) {
            if (tokenFile != null && !Files.exists(tokenFile)) {
                writeOwnerOnly(tokenFile, service.token() + System.lineSeparator());
            }
            out.println("DPP starter operator gateway for chain " + client.chainId() + " on " + service.baseUrl());
            out.println("  " + DppStarterProfile.PROTOTYPE_NOTICE);
            out.println("  signs for: " + String.join(", ", service.actorIds()));
            out.println("  token:     " + (tokenFile != null ? "in " + tokenFile : service.token()));
            out.println("  routes:    GET /operator/actors; POST /operator/{products,versions,status,revoke,"
                    + "claims,events}; POST /operator/certifications/{propose,approve,reject,apply}");
            Thread.currentThread().join();
        }
        return OK;
    }

    // ------------------------------------------------------------------ helpers

    private static DppClient client(Arguments input) throws IOException {
        String url = input.option("url", System.getenv("YANO_DPP_URL"));
        String chain = input.option("chain", System.getenv("YANO_DPP_CHAIN"));
        if (url == null || chain == null) {
            throw new UsageException("--url and --chain are required (or YANO_DPP_URL and YANO_DPP_CHAIN)");
        }
        return DppClient.connect(url, chain, apiKey(input));
    }

    private static String apiKey(Arguments input) throws IOException {
        if (input.has("api-key-file")) {
            return readText(Path.of(input.required("api-key-file"))).trim();
        }
        return input.option("api-key", System.getenv("YANO_API_KEY"));
    }

    private static DppClient.Signer signer(DppClient client, Arguments input) throws IOException {
        return new DppClient.Signer(input.required("actor"), seed(input),
                input.option("genesis-id", null), input.option("key-id", null));
    }

    /** {@code --<option>} when given, else the actor's organization as the chain records it. */
    private static String organizationOf(DppClient client, Arguments input, String option) {
        if (input.has(option)) {
            return input.required(option);
        }
        if (client.tipHeight() < 1) {
            throw new UsageException("--" + option + " is required before the chain has a block");
        }
        return client.chain().actor(input.required("actor")).organizationId();
    }

    private static DocumentStore documents(Arguments input) {
        String directory = input.option("content-dir", System.getenv("YANO_DPP_CONTENT_DIR"));
        if (directory == null) {
            directory = System.getProperty("user.home") + "/.yano-x/dpp/documents";
        }
        return new DocumentStore(Path.of(directory));
    }

    private static String mediaTypeOf(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".txt")) return "text/plain";
        if (name.endsWith(".xml")) return "application/xml";
        return "application/octet-stream";
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

    private static byte[] readBytes(Path file) throws IOException {
        if (Files.size(file) > DocumentStore.MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException(file + " exceeds " + DocumentStore.MAX_DOCUMENT_BYTES + " bytes");
        }
        return Files.readAllBytes(file);
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
    }

    static String usage() {
        return """
                yano-dpp: Digital Product Passport starter (ADR-051)
                  PROTOTYPE: a configuration-only starter on the stock authenticated map. Not the DPP
                  product of ADR-026; it enforces no DPP lifecycle rule and claims no conformance.

                Genesis:
                  genesis       (--demo | --descriptor <file>) --members <key,...> [--threshold <n>]
                                [--chain <id>] [--chain-index <n>] [--output <properties>]
                  descriptor    --demo [--chain <id>] [--output <file>]
                  actor-key     --actor <id> --seed-file <file> [--chain <id>] [--key-id <id>]

                Node: --url <base> --chain <id> [--api-key <key> | --api-key-file <file>]
                      (or YANO_DPP_URL, YANO_DPP_CHAIN, YANO_API_KEY)
                Writes also take: --actor <id> --seed-file <file> [--genesis-id <hex>] [--key-id <id>]
                  register        --product <id> --profile <passport profile> [--manufacturer-org <org>] [--status DRAFT]
                  publish-version --product <id> --version <n> --document <file> [--media-type <t>] [--reference <text>]
                  set-status      --product <id> --status ACTIVE|INACTIVE|REPLACED|RETIRED [--successor <id>]
                  revoke          --product <id>
                  claim           --product <id> --type <t> --id <id> --text <text> [--committed --disclosure-output <f>]
                                  [--org <org>] [--valid-from <h>] [--valid-until <h>] [--evidence <file>]
                  event           --product <id> --type <t> [--id <id>] [--org <org>] [--observed-at <epoch s>]
                                  [--location <text>] [--note <text>] [--evidence <file>]
                  certify propose --product <id> --certificate <id> --type <t> --evidence <file> --output <request.json>
                                  [--org <org>] [--valid-from <h>] [--valid-until <h>]
                  certify propose --revoke --certificate <id> --output <request.json>
                  certify approve|reject --request <request.json>
                  certify apply   --request <request.json>

                Reads:
                  passport      --product <id> [--height <h>] [--members <keys.json> | --anchor-datum-hex <cbor>]
                                [--output <passport.json>] [--json]
                  verify        --passport <passport.json> [--members <keys.json> | --anchor-datum-hex <cbor>] [--json]
                  disclose      --passport <passport.json> --disclosure <disclosure.json> [--members <keys.json>]
                  document      --sha256 <hex> --output <file>

                Services:
                  serve         [--bind 127.0.0.1] [--port 8580]
                  gateway       --seeds <dir of <actor>.seed> [--bind 127.0.0.1] [--port 8590] [--token-file <f>]
                                [--genesis-id <hex>] [--allow-remote]

                Documents are kept under --content-dir (YANO_DPP_CONTENT_DIR, default ~/.yano-x/dpp/documents).
                <base> is the REST base URL including the API prefix, e.g. http://localhost:7070/api/v1.
                Seed files and directories must be owner-only (chmod 600 / 700).

                Exit codes: 0 verified with an independent anchor (or write applied); 2 usage;
                3 unavailable; 4 invalid (a rejected write included); 5 verified with caller-pinned
                members; 6 consistent only.
                """;
    }

    static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    /** Minimal {@code command [subcommand] --key value} parser with boolean flags. */
    static final class Arguments {
        private static final Set<String> FLAGS = Set.of("json", "demo", "committed", "revoke", "allow-remote");
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
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
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
