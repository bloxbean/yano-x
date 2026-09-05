package com.bloxbean.cardano.yano.appchain.attest.cli;

import com.bloxbean.cardano.yano.api.appchain.proof.ProofLabVocabulary;
import com.bloxbean.cardano.yano.appchain.attest.client.AttestCertificate;
import com.bloxbean.cardano.yano.appchain.attest.client.AttestCertificateCodec;
import com.bloxbean.cardano.yano.appchain.attest.client.AttestClient;
import com.bloxbean.cardano.yano.appchain.attest.client.AttestTrust;
import com.bloxbean.cardano.yano.appchain.attest.client.AttestVerification;
import com.bloxbean.cardano.yano.appchain.attest.client.AttestVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dependency-light command line interface for Yano Attest, ADR-047 §7. */
public final class AttestCli {
    public static final int OK = 0;
    public static final int USAGE = 2;
    public static final int UNAVAILABLE = 3;
    public static final int INVALID = 4;
    public static final int PINNED = 5;
    public static final int UNPINNED = 6;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_FILE_BYTES = 256 * 1024 * 1024;
    private static final int MAX_MEMBERS_BYTES = 256 * 1024;
    private static final int MAX_DATUM_HEX_CHARS = 32 * 1024;

    private final PrintStream out;
    private final PrintStream err;

    AttestCli(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        System.exit(new AttestCli(System.out, System.err).run(args));
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
                case "attest" -> attest(input);
                case "certificate" -> certificate(input);
                case "verify" -> verify(input);
                case "trail" -> trail(input);
                case "status" -> status(input);
                default -> throw new UsageException("unknown command " + input.command());
            };
        } catch (UsageException failure) {
            err.println(failure.getMessage());
            err.println(usage());
            return USAGE;
        } catch (AttestClient.AttestClientException unavailable) {
            err.println(unavailable.getMessage());
            return unavailable.error() == AttestClient.Error.MALFORMED_RESPONSE ? INVALID : UNAVAILABLE;
        } catch (IllegalArgumentException invalid) {
            err.println(invalid.getMessage());
            return INVALID;
        } catch (IOException io) {
            err.println(io.getMessage());
            return UNAVAILABLE;
        } catch (Exception failure) {
            err.println("Attest command failed: " + failure.getMessage());
            return INVALID;
        }
    }

    private int attest(Arguments input) throws IOException {
        AttestClient client = client(input);
        Path file = Path.of(input.required("file"));
        byte[] document = readBounded(file, MAX_FILE_BYTES);
        AttestClient.Submission submission = client.attest(document, new AttestClient.AttestRequest(
                input.option("entity", null), input.option("reference", null)));
        out.println("Submitted message " + submission.messageIdHex()
                + " for entity " + submission.entityId()
                + " (sha-256 " + submission.entryHashHex() + ")");
        Duration timeout = Duration.ofSeconds(input.longValue("timeout-seconds", 60));
        AttestClient.FinalizedMessage finalized = client.awaitFinalized(submission.messageIdHex(), timeout);
        out.println("Finalized at height " + finalized.height() + " index " + finalized.index());
        AttestCertificate certificate = client.certificate(submission.messageIdHex(),
                metadata(input, file, document));
        return writeCertificate(certificate, input.option("output", defaultOutput(file)));
    }

    private int certificate(Arguments input) throws IOException {
        AttestClient client = client(input);
        String messageId = input.required("message-id").toLowerCase(java.util.Locale.ROOT);
        AttestClient.SubjectMetadata metadata = AttestClient.SubjectMetadata.none();
        if (input.has("file")) {
            Path file = Path.of(input.required("file"));
            metadata = metadata(input, file, readBounded(file, MAX_FILE_BYTES));
        } else if (input.has("label")) {
            metadata = new AttestClient.SubjectMetadata(null, null, null, input.option("label", null));
        }
        AttestCertificate certificate = client.certificate(messageId, metadata);
        return writeCertificate(certificate, input.required("output"));
    }

    private int verify(Arguments input) throws IOException {
        Path certificatePath = Path.of(input.required("certificate"));
        AttestCertificate certificate = AttestCertificateCodec.fromJson(
                readBounded(certificatePath, AttestCertificateCodec.MAX_JSON_BYTES));
        byte[] document = input.has("file") ? readBounded(Path.of(input.required("file")), MAX_FILE_BYTES) : null;
        AttestTrust trust = trust(input);
        AttestVerification result = AttestVerifier.verify(certificate, document, trust);
        if (input.flag("json")) {
            out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report(certificate, result)));
        } else {
            printReport(certificate, result);
        }
        if (!result.consistent()) {
            return INVALID;
        }
        return switch (result.trustLevel()) {
            case INDEPENDENTLY_VERIFIED_L1_ANCHOR -> OK;
            case CALLER_PINNED_ROOT -> PINNED;
            default -> UNPINNED;
        };
    }

    private int trail(Arguments input) throws IOException {
        AttestClient.Trail trail = client(input).trail(input.required("entity"));
        ObjectNode node = JSON.createObjectNode();
        node.put("entityId", trail.entityId());
        node.put("present", trail.present());
        node.put("revision", trail.revision());
        node.put("headDigestHex", trail.headDigestHex());
        node.put("committedHeight", trail.committedHeight());
        node.put("stateRootHex", trail.stateRootHex());
        out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
        return trail.present() ? OK : UNAVAILABLE;
    }

    private int status(Arguments input) throws IOException {
        AttestClient.ChainStatus status = client(input).status();
        ObjectNode node = JSON.createObjectNode();
        node.put("chainId", status.chainId());
        node.put("stateMachine", status.stateMachine());
        node.put("applicationId", status.applicationId());
        node.put("docTrail", status.docTrail());
        node.put("tipHeight", status.tipHeight());
        node.put("stateRootHex", status.stateRootHex());
        node.put("members", status.members());
        node.put("threshold", status.threshold());
        node.put("anchoringConfigured", status.anchoringConfigured());
        node.put("anchorMode", status.anchorMode());
        out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
        return status.docTrail() ? OK : UNAVAILABLE;
    }

    // ------------------------------------------------------------------ helpers

    private static AttestClient client(Arguments input) {
        return AttestClient.builder(input.required("url"), input.required("chain"))
                .apiKey(input.option("api-key", null)).build();
    }

    private static AttestTrust trust(Arguments input) throws IOException {
        if (input.has("anchor-datum-hex")) {
            String datumHex = input.required("anchor-datum-hex");
            if (datumHex.length() > MAX_DATUM_HEX_CHARS) {
                throw new IllegalArgumentException("anchor datum hex exceeds " + MAX_DATUM_HEX_CHARS + " characters");
            }
            return AttestTrust.IndependentAnchor.fromDatumHex(datumHex, input.option("application-id", null));
        }
        if (input.has("members")) {
            return AttestTrust.CallerPinned.fromJson(new String(
                    readBounded(Path.of(input.required("members")), MAX_MEMBERS_BYTES),
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        return AttestTrust.bundleDeclared();
    }

    private static AttestClient.SubjectMetadata metadata(Arguments input, Path file, byte[] document)
            throws IOException {
        String mediaType = null;
        try {
            mediaType = Files.probeContentType(file);
        } catch (IOException ignored) {
            // media type stays unknown
        }
        return new AttestClient.SubjectMetadata(file.getFileName().toString(), (long) document.length,
                mediaType, input.option("label", null));
    }

    private int writeCertificate(AttestCertificate certificate, String output) throws IOException {
        Path path = Path.of(output);
        Files.writeString(path, AttestCertificateCodec.toJson(certificate) + System.lineSeparator());
        out.println("Wrote " + certificate.status().name().toLowerCase(java.util.Locale.ROOT)
                + " certificate to " + path);
        return OK;
    }

    private static String defaultOutput(Path file) {
        return file.getFileName() + ".attest.json";
    }

    static ObjectNode report(AttestCertificate certificate, AttestVerification result) {
        ObjectNode node = JSON.createObjectNode();
        node.put("accepted", result.accepted());
        node.put("consistent", result.consistent());
        node.put("trustLevel", result.trustLevel().name());
        ObjectNode checks = node.putObject("checks");
        checks.put("digest", result.digest().name());
        checks.put("commandBinding", result.commandBinding().name());
        checks.put("messageInclusion", result.inclusion().name());
        checks.put("finality", result.finality().name());
        checks.put("certSignatures", result.certSignatures());
        checks.put("anchorLinkage", result.anchor().name());
        checks.put("trailHead", result.trailHead().name());
        ObjectNode summary = node.putObject("certificate");
        summary.put("chainId", certificate.chainId());
        summary.put("status", certificate.status().name());
        summary.put("entityId", certificate.subject().entityId());
        summary.put("entryHashHex", certificate.subject().entryHashHex());
        summary.put("fileName", certificate.subject().fileName());
        summary.put("reference", certificate.subject().reference());
        summary.put("label", certificate.subject().label());
        summary.put("messageIdHex", certificate.message().messageIdHex());
        summary.put("height", certificate.message().height());
        summary.put("senderHex", certificate.message().senderHex());
        if (certificate.trailHead() != null) {
            summary.put("revision", certificate.trailHead().revision());
            summary.put("headDigestHex", certificate.trailHead().headDigestHex());
        }
        if (certificate.anchorReference() != null) {
            summary.put("anchoredHeight", certificate.anchorReference().anchoredHeight());
            summary.put("anchorTransactionHash", certificate.anchorReference().transactionHash());
            summary.put("anchorL1Slot", certificate.anchorReference().l1Slot());
        }
        ArrayNode failures = node.putArray("failures");
        result.failures().forEach(failures::add);
        return node;
    }

    private void printReport(AttestCertificate certificate, AttestVerification result) {
        Map<String, String> lines = new LinkedHashMap<>();
        lines.put("Chain", certificate.chainId());
        lines.put("Entity", certificate.subject().entityId());
        lines.put("Digest", certificate.subject().entryHashHex());
        lines.put("Message", certificate.message().messageIdHex() + " at height " + certificate.message().height());
        lines.put("Sender", certificate.message().senderHex());
        lines.put("C1 digest", result.digest().name());
        lines.put("C2 command binding", result.commandBinding().name());
        lines.put("C3 message inclusion", result.inclusion().name());
        lines.put("C4 finality", result.finality().name()
                + (result.finality() == AttestVerification.Finality.VALID
                ? " (" + result.certSignatures() + " member signatures)" : ""));
        lines.put("C5 anchor linkage", result.anchor().name());
        lines.put("C6 trail head", result.trailHead().name());
        lines.put("Trust level", result.trustLevel().name());
        lines.put("Verdict", verdict(result));
        lines.forEach((key, value) -> out.printf("%-22s %s%n", key, value));
        for (String failure : result.failures()) {
            out.println("  - " + failure);
        }
    }

    private static String verdict(AttestVerification result) {
        if (!result.consistent()) {
            return "INVALID";
        }
        if (result.trustLevel() == ProofLabVocabulary.TrustLevel.INTERNAL_CONSISTENCY_ONLY) {
            return "CONSISTENT (no trust input supplied; pass --members or --anchor-datum-hex)";
        }
        return "ACCEPTED";
    }

    private static byte[] readBounded(Path path, int maxBytes) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("file not found: " + path);
        }
        if (Files.size(path) > maxBytes) {
            throw new IllegalArgumentException(path + " exceeds " + maxBytes + " bytes");
        }
        return Files.readAllBytes(path);
    }

    static String usage() {
        return """
                yano-attest <command> [options]

                Commands:
                  attest       --url <node> --chain <id> --file <path> [--entity <id>] [--reference <ref>]
                               [--label <text>] [--output <certificate.json>] [--api-key <key>]
                               [--timeout-seconds <n>]
                  certificate  --url <node> --chain <id> --message-id <hex> --output <certificate.json>
                               [--file <path>] [--label <text>] [--api-key <key>]
                  verify       --certificate <certificate.json> [--file <path>] [--members <keys.json>]
                               [--anchor-datum-hex <cbor>] [--application-id <id>] [--json]
                  trail        --url <node> --chain <id> --entity <id> [--api-key <key>]
                  status       --url <node> --chain <id> [--api-key <key>]

                <node> is the REST base URL including the API prefix, for example http://localhost:7070/api/v1.

                Exit codes: 0 accepted with an independently verified anchor; 2 usage; 3 unavailable;
                4 invalid; 5 accepted with caller-pinned members; 6 consistent only (no trust input).
                """;
    }

    static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    /** Minimal {@code command --key value} parser with boolean flags. */
    static final class Arguments {
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
                if (key.equals("json")) {
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
