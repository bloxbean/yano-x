package com.bloxbean.cardano.yano.appchain.explorer.cli;

import com.bloxbean.cardano.yano.appchain.attest.client.AttestTrust;
import com.bloxbean.cardano.yano.appchain.explorer.Bundles;
import com.bloxbean.cardano.yano.appchain.explorer.ContentArchiver;
import com.bloxbean.cardano.yano.appchain.explorer.Explorer;
import com.bloxbean.cardano.yano.appchain.explorer.ExplorerException;
import com.bloxbean.cardano.yano.appchain.explorer.ExplorerService;
import com.bloxbean.cardano.yano.appchain.explorer.Follower;
import com.bloxbean.cardano.yano.appchain.explorer.IndexStore;
import com.bloxbean.cardano.yano.appchain.explorer.RowVerifier;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofLabVocabulary;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code yano-explorer}: the Verifiable Explorer command line (ADR-050 §2). Exit codes follow
 * ADR-047: 0 verified with an independent anchor (or a command applied), 2 usage, 3 unavailable,
 * 4 invalid, 5 verified with caller-pinned members, 6 consistent with declared members only.
 */
public final class ExplorerCli {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ExplorerCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || isHelp(args[0])) {
            out.print(usage());
            return args.length == 0 ? 2 : 0;
        }
        Map<String, String> options = new LinkedHashMap<>();
        List<String> positional = new ArrayList<>();
        try {
            parse(Arrays.copyOfRange(args, 1, args.length), options, positional);
            return switch (args[0]) {
                case "chains" -> chains(options, out);
                case "index" -> index(options, out, err);
                case "serve" -> serve(options, out, err);
                case "status" -> status(options, out);
                case "search" -> search(options, positional, out);
                case "subject", "trail" -> subject(args[0], options, positional, out);
                case "row" -> row(options, positional, out);
                case "verify" -> verify(options, out);
                case "export" -> export(options, out);
                case "rebuild" -> rebuild(options, out, err);
                case "archive" -> archive(options, positional, out);
                default -> {
                    err.println("unknown command: " + args[0]);
                    err.print(usage());
                    yield 2;
                }
            };
        } catch (ExplorerException failure) {
            err.println("error: " + failure.getMessage());
            return failure.exitCode();
        } catch (IllegalArgumentException invalid) {
            err.println("error: " + invalid.getMessage());
            return 2;
        } catch (IOException failure) {
            err.println("error: " + failure.getMessage());
            return 3;
        }
    }

    static String usage() {
        return """
                yano-explorer: the Verifiable Explorer (ADR-050)

                Node and index options (index, serve, status, search, trail, row, rebuild, export):
                  --url <node base url>        e.g. http://127.0.0.1:7070 (YANO_EXPLORER_URL)
                  --api-key-file <file>        API key file (or YANO_API_KEY)
                  --db <file>                  SQLite index (default ~/.yano-x/explorer/explorer.db)
                  --content-dir <dir>          archived bodies (default next to the index)
                  --chain <id>[,<id>...]       chains to index; default: every chain the node lists
                  --members <file>             pin members {chainId, memberKeysHex[], threshold}

                Commands:
                  chains                       list the node's chains
                  index [--max-blocks N]       catch the index up to the tip once
                  serve [--bind host] [--port N] [--poll-ms N]
                                               index continuously and serve the read API
                  status                       identity, checkpoint, tip, lag, levels per chain
                  search <query> --chain <id>  message id, height, topic, sender or subject prefix
                  subject <module> <subject> --chain <id> [--no-check] [--proof [--height H]]
                  trail <entity> --chain <id>  the doc-trail subject view
                  row <messageId> --chain <id> [--output <file>]
                                               the row proof bundle for one message
                  verify --bundle <file> [--members <file> | --anchor-datum-hex <hex>]
                                               verify a row or state bundle offline
                  export --chain <id> [--output <file>]
                                               deterministic JSON-lines export
                  rebuild --chain <id>         drop the chain's rows and index again from the node
                  archive add --file <path> [--entry-hash <hex>]
                  archive fetch --url <ref> --allow <prefix>[,<prefix>] [--entry-hash <hex>]

                Exit codes: 0 verified with an independent anchor (or applied), 2 usage, 3 unavailable,
                4 invalid, 5 verified with caller-pinned members, 6 consistent with declared members only.
                """;
    }

    // --- commands ---

    private static int chains(Map<String, String> options, PrintStream out) throws IOException {
        try (Explorer explorer = open(options, false)) {
            for (String chainId : explorer.discoverChains()) out.println(chainId);
        }
        return 0;
    }

    private static int index(Map<String, String> options, PrintStream out, PrintStream err) throws IOException {
        int max = Integer.parseInt(options.getOrDefault("max-blocks", "100000"));
        int failures = 0;
        try (Explorer explorer = open(options, true)) {
            for (String chainId : chains(explorer, options)) {
                try {
                    long before = explorer.store().checkpoint(chainId).height();
                    long height = explorer.catchUp(chainId, max);
                    out.println(chainId + ": indexed to height " + height + " (+" + (height - before) + ")");
                } catch (ExplorerException failure) {
                    failures++;
                    err.println(chainId + ": " + failure.error() + ": " + failure.getMessage());
                }
            }
        }
        return failures == 0 ? 0 : 4;
    }

    private static int serve(Map<String, String> options, PrintStream out, PrintStream err) throws IOException {
        String bind = options.getOrDefault("bind", "127.0.0.1");
        int port = Integer.parseInt(options.getOrDefault("port", "8490"));
        long poll = Long.parseLong(options.getOrDefault("poll-ms", "2000"));
        Explorer explorer = open(options, true);
        List<String> chains = chains(explorer, options);
        for (String chainId : chains) {
            try {
                explorer.follower(chainId).ensureIdentity();
            } catch (ExplorerException failure) {
                err.println(chainId + ": " + failure.getMessage());
            }
        }
        ExplorerService service = ExplorerService.start(explorer, chains, new InetSocketAddress(bind, port), poll);
        out.println("yano-explorer serving " + service.baseUrl() + " for " + chains.size()
                + " chain(s); index " + options.getOrDefault("db", defaultDb().toString()));
        out.flush();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            service.close();
            explorer.close();
        }));
        try {
            Thread.currentThread().join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    private static int status(Map<String, String> options, PrintStream out) throws IOException {
        try (Explorer explorer = open(options, false)) {
            List<String> ids = options.containsKey("chain") ? ExplorerService.csv(options.get("chain")) : explorer.chains();
            for (String chainId : ids) {
                Explorer.ChainView view = explorer.chain(chainId);
                out.println(chainId + ": application " + view.identity().applicationId() + ", profile "
                        + view.identity().profile() + ", state genesis " + view.identity().stateGenesisIdHex());
                out.println("  checkpoint " + view.checkpoint().height() + ", node tip "
                        + (view.tipHeight() < 0 ? "unreachable" : view.tipHeight()) + ", lag "
                        + (view.lagBlocks() < 0 ? "unknown" : view.lagBlocks()));
                out.println("  levels " + view.levels() + ", topics " + view.topics());
                if (!view.diagnostic().isEmpty()) out.println("  diagnostic: " + view.diagnostic());
            }
        }
        return 0;
    }

    private static int search(Map<String, String> options, List<String> positional, PrintStream out) throws IOException {
        if (positional.isEmpty()) throw usage("search needs a query");
        String chainId = requireChain(options);
        try (Explorer explorer = open(options, false)) {
            List<IndexStore.SearchHit> hits = explorer.store().search(chainId, String.join(" ", positional), 50);
            if (hits.isEmpty()) out.println("no hits");
            for (IndexStore.SearchHit hit : hits) {
                out.println(hit.type() + (hit.module().isEmpty() ? "" : " " + hit.module())
                        + (hit.subject().isEmpty() ? "" : " " + hit.subject())
                        + " height " + hit.height() + (hit.index() >= 0 ? " index " + hit.index() : "")
                        + (hit.messageIdHex().isEmpty() ? "" : " message " + hit.messageIdHex())
                        + ": " + hit.detail());
            }
        }
        return 0;
    }

    private static int subject(String command, Map<String, String> options, List<String> positional,
                               PrintStream out) throws IOException {
        String module;
        String subject;
        if ("trail".equals(command)) {
            if (positional.size() != 1) throw usage("trail needs an entity id");
            module = "doc-trail";
            subject = positional.getFirst();
        } else {
            if (positional.size() != 2) throw usage("subject needs a module and a subject");
            module = positional.get(0);
            subject = positional.get(1);
        }
        String chainId = requireChain(options);
        try (Explorer explorer = open(options, false)) {
            if (options.containsKey("proof")) {
                Long height = options.containsKey("height") ? Long.parseLong(options.get("height")) : null;
                Bundles.StateBundle bundle = explorer.stateBundle(chainId, module, subject, height);
                String json = Bundles.toJson(bundle);
                if (options.containsKey("output")) {
                    Files.writeString(Path.of(options.get("output")), json, StandardCharsets.UTF_8);
                    out.println("wrote " + options.get("output"));
                } else {
                    out.println(json);
                }
                return 0;
            }
            Explorer.SubjectView view = explorer.subject(chainId, module, subject, !options.containsKey("no-check"));
            out.println(view.module() + " " + view.kind() + " " + view.subject() + ": " + view.rows().size() + " row(s)");
            for (IndexStore.RowRecord row : view.rows()) {
                out.println("  h" + row.height() + "#" + row.index() + " " + row.op() + " " + row.fields()
                        + " [" + row.level() + "] message " + row.messageIdHex());
            }
            out.println("  derived: " + JSON.writeValueAsString(view.derived()));
            out.println("  state check: " + JSON.writeValueAsString(view.stateCheck()));
        }
        return 0;
    }

    private static int row(Map<String, String> options, List<String> positional, PrintStream out) throws IOException {
        if (positional.size() != 1) throw usage("row needs a message id");
        String chainId = requireChain(options);
        try (Explorer explorer = open(options, false)) {
            Bundles.RowBundle bundle = explorer.rowBundle(chainId, positional.getFirst());
            String json = Bundles.toJson(bundle);
            if (options.containsKey("output")) {
                Files.writeString(Path.of(options.get("output")), json, StandardCharsets.UTF_8);
                out.println("wrote " + options.get("output") + " (" + bundle.ingestLevel() + ")");
            } else {
                out.println(json);
            }
        }
        return 0;
    }

    private static int verify(Map<String, String> options, PrintStream out) throws IOException {
        String file = options.get("bundle");
        if (file == null) throw usage("verify needs --bundle <file>");
        String json = Files.readString(Path.of(file), StandardCharsets.UTF_8);
        AttestTrust trust = trust(options);
        String schema = Bundles.schemaOf(json);
        RowVerifier.Verification verification = switch (schema) {
            case Bundles.ROW_SCHEMA -> RowVerifier.verify(Bundles.rowFromJson(json), trust);
            case Bundles.STATE_SCHEMA -> RowVerifier.verify(Bundles.stateFromJson(json), trust);
            default -> throw new ExplorerException(ExplorerException.Error.INVALID,
                    "the file is neither an " + Bundles.ROW_SCHEMA + " nor an " + Bundles.STATE_SCHEMA + " document");
        };
        for (String check : verification.checks()) out.println("ok   " + check);
        for (String failure : verification.failures()) out.println("FAIL " + failure);
        out.println(schema + ": " + (verification.consistent() ? "consistent" : "INVALID") + " at trust level "
                + verification.trustLevel() + " (" + verification.certSignatures() + " certificate signature(s))");
        if (!verification.consistent()) return 4;
        return exitFor(verification.trustLevel());
    }

    private static int export(Map<String, String> options, PrintStream out) throws IOException {
        String chainId = requireChain(options);
        try (Explorer explorer = open(options, false)) {
            StringBuilder builder = new StringBuilder();
            explorer.store().export(chainId, builder);
            if (options.containsKey("output")) {
                Files.writeString(Path.of(options.get("output")), builder, StandardCharsets.UTF_8);
                out.println("wrote " + options.get("output"));
            } else {
                out.print(builder);
            }
        }
        return 0;
    }

    private static int rebuild(Map<String, String> options, PrintStream out, PrintStream err) throws IOException {
        String chainId = requireChain(options);
        try (Explorer explorer = open(options, true)) {
            explorer.store().reset(chainId);
            long height = explorer.catchUp(chainId, Integer.parseInt(options.getOrDefault("max-blocks", "100000")));
            out.println(chainId + ": rebuilt to height " + height);
        }
        return 0;
    }

    private static int archive(Map<String, String> options, List<String> positional, PrintStream out) throws IOException {
        if (positional.size() != 1) throw usage("archive needs add or fetch");
        IndexStore store = IndexStore.open(dbPath(options));
        try (store) {
            ContentArchiver archiver = new ContentArchiver(store, contentDir(options));
            IndexStore.ContentRecord record = switch (positional.getFirst()) {
                case "add" -> {
                    String file = options.get("file");
                    if (file == null) throw usage("archive add needs --file");
                    yield archiver.add(Files.readAllBytes(Path.of(file)), "file:" + Path.of(file).getFileName(),
                            options.get("entry-hash"));
                }
                case "fetch" -> {
                    String url = options.get("url");
                    if (url == null) throw usage("archive fetch needs --url");
                    yield archiver.fetch(url, ExplorerService.csv(options.get("allow")), options.get("entry-hash"));
                }
                default -> throw usage("archive needs add or fetch");
            };
            out.println(record.status() + " sha256 " + record.sha256Hex() + " blake2b " + record.blake2bHex()
                    + " (" + record.size() + " bytes)");
            return ContentArchiver.MISMATCH.equals(record.status()) ? 4 : 0;
        }
    }

    // --- helpers ---

    private static Explorer open(Map<String, String> options, boolean needsNode) throws IOException {
        String url = options.getOrDefault("url", System.getenv("YANO_EXPLORER_URL"));
        if (url == null || url.isBlank()) {
            if (needsNode) throw usage("--url (or YANO_EXPLORER_URL) is required");
            url = "http://127.0.0.1:7070";
        }
        String apiKey = System.getenv("YANO_API_KEY");
        if (options.containsKey("api-key-file")) {
            apiKey = Files.readString(Path.of(options.get("api-key-file")), StandardCharsets.UTF_8).trim();
        }
        Follower.Trust trust = null;
        if (options.containsKey("members")) {
            AttestTrust.CallerPinned pinned = AttestTrust.CallerPinned.fromJson(
                    Files.readString(Path.of(options.get("members")), StandardCharsets.UTF_8));
            trust = new Follower.Trust(pinned.memberKeysHex(), pinned.threshold());
        }
        IndexStore store = IndexStore.open(dbPath(options));
        return new Explorer(store, new ContentArchiver(store, contentDir(options)), url, apiKey, trust);
    }

    private static List<String> chains(Explorer explorer, Map<String, String> options) {
        if (options.containsKey("chain")) return ExplorerService.csv(options.get("chain"));
        List<String> discovered = explorer.discoverChains();
        if (discovered.isEmpty()) throw new ExplorerException(ExplorerException.Error.UNAVAILABLE, "the node lists no chains");
        return discovered;
    }

    private static AttestTrust trust(Map<String, String> options) throws IOException {
        if (options.containsKey("anchor-datum-hex")) {
            return AttestTrust.IndependentAnchor.fromDatumHex(options.get("anchor-datum-hex"), options.get("application-id"));
        }
        if (options.containsKey("members")) {
            return AttestTrust.CallerPinned.fromJson(Files.readString(Path.of(options.get("members")), StandardCharsets.UTF_8));
        }
        return new AttestTrust.BundleDeclared();
    }

    static int exitFor(ProofLabVocabulary.TrustLevel level) {
        return switch (level) {
            case INDEPENDENTLY_VERIFIED_L1_ANCHOR -> 0;
            case CALLER_PINNED_ANCHOR -> 0;
            case CALLER_PINNED_ROOT -> 5;
            case NODE_CONFIRMED_L1_REFERENCE, INTERNAL_CONSISTENCY_ONLY -> 6;
        };
    }

    private static Path dbPath(Map<String, String> options) {
        return options.containsKey("db") ? Path.of(options.get("db")) : defaultDb();
    }

    private static Path defaultDb() {
        return Path.of(System.getProperty("user.home"), ".yano-x", "explorer", "explorer.db");
    }

    private static Path contentDir(Map<String, String> options) {
        if (options.containsKey("content-dir")) return Path.of(options.get("content-dir"));
        Path db = dbPath(options).toAbsolutePath();
        return db.getParent().resolve("content");
    }

    private static String requireChain(Map<String, String> options) {
        String chain = options.get("chain");
        if (chain == null || chain.isBlank() || chain.contains(",")) throw usage("--chain <id> is required");
        return chain.trim();
    }

    private static void parse(String[] args, Map<String, String> options, List<String> positional) {
        Set<String> flags = new LinkedHashSet<>(List.of("no-check", "proof", "help"));
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--")) {
                String name = arg.substring(2);
                if (flags.contains(name) || i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    options.put(name, "true");
                } else {
                    options.put(name, args[++i]);
                }
            } else {
                positional.add(arg);
            }
        }
    }

    private static boolean isHelp(String arg) {
        return arg.equals("--help") || arg.equals("-h") || arg.equals("help");
    }

    private static ExplorerException usage(String message) {
        return new ExplorerException(ExplorerException.Error.USAGE, message);
    }
}
