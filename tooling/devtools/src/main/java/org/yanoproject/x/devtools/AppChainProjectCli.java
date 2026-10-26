package org.yanoproject.x.devtools;

import org.yanoproject.appchain.config.AppChainPropertyRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileVisitResult;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Guided and non-interactive project initialization/render commands. */
final class AppChainProjectCli {
    private static final List<ConsoleColumn> CAPABILITY_COLUMNS = List.of(
            new ConsoleColumn("CAPABILITY", 25),
            new ConsoleColumn("CATEGORY", 16),
            new ConsoleColumn("AVAILABILITY", 20),
            new ConsoleColumn("MATURITY", 12),
            new ConsoleColumn("SCOPE", 12),
            new ConsoleColumn("SELECTION", 10));

    static final String USAGE = """
            Usage: ./yano.sh appchain recipes [--format text|json]
               or: ./yano.sh appchain capabilities [--format text|json]
               or: ./yano.sh appchain plugin inspect|validate|sign|scaffold [options]
               or: ./yano.sh appchain metadata verify <plugin.jar> --trust-key <key-id=64-hex-public-key>
            Metadata verification options:
              --trust-key <id=public-key>   repeatable trusted Ed25519 raw public key
            Plugin catalog examples:
              plugin scaffold --mode <state-machine|composite-role|effect-executor|sink>
                --id <id> --package <java-package> --yano-version <version> --output <empty-dir>
              plugin sign --catalog <json> --runtime-manifest <json> [--config-metadata <json>]
                --seed-file <file> --key-id <id> --output <signature-json>
              plugin inspect <jar|snapshot> --trust-key <id=public-key>
              plugin validate <jar|snapshot> --trust-key <id=public-key> [--output <snapshot>]
            """.stripTrailing();

    private final BufferedReader input;
    private final PrintWriter out;
    private final ObjectMapper json;
    private final AppChainPropertyRegistry properties;
    private final AppChainProjectCatalog catalog;

    AppChainProjectCli(PrintWriter out) throws IOException {
        this(new BufferedReader(new InputStreamReader(System.in)), out,
                builtInRegistry(), new ObjectMapper());
    }

    static AppChainPropertyRegistry builtInRegistry() throws IOException {
        List<org.yanoproject.appchain.config.AppChainMetadataSource> sources =
                new AppChainDescriptorLoader().loadBuiltInMetadata().stream()
                        .map(org.yanoproject.appchain.config.AppChainMetadataDescriptor::toSource)
                        .toList();
        return AppChainPropertyRegistry.withSources(sources);
    }

    AppChainProjectCli(
            BufferedReader input,
            PrintWriter out,
            AppChainPropertyRegistry properties,
            ObjectMapper json) throws IOException {
        this.input = java.util.Objects.requireNonNull(input, "input");
        this.out = java.util.Objects.requireNonNull(out, "out");
        this.json = java.util.Objects.requireNonNull(json, "json");
        this.properties = java.util.Objects.requireNonNull(properties, "properties");
        this.catalog = new AppChainProjectCatalog(properties);
    }

    int run(String[] arguments) throws IOException {
        int cursor = 0;
        if (cursor < arguments.length && "appchain".equals(arguments[cursor])) cursor++;
        if (cursor >= arguments.length) throw new Usage("An appchain command is required");
        String command = arguments[cursor++];
        String[] remaining = java.util.Arrays.copyOfRange(arguments, cursor, arguments.length);
        if (helpRequested(remaining)) {
            out.println(USAGE);
            return AppChainDevtoolsCli.EXIT_OK;
        }
        return switch (command) {
            case "recipes" -> recipes(parseFormatOnly(remaining));
            case "capabilities" -> capabilities(parseFormatOnly(remaining));
            case "plugin" -> plugin(parsePlugin(remaining));
            case "metadata" -> metadata(parseMetadata(remaining));
            default -> throw new Usage("Unknown appchain project command: " + safe(command));
        };
    }

    private int metadata(MetadataOptions options) throws IOException {
        AppChainProjectModel.MetadataTrustResult result =
                new AppChainMetadataTrustVerifier().verify(
                        options.artifact(), options.trustKeys());
        if (options.format() == Format.JSON) {
            out.println(json.writeValueAsString(result));
        } else {
            out.printf(Locale.ROOT,
                    "%s bundle=%s descriptor=%s key=%s coverage=%s%n",
                    result.status(), result.bundleId(), result.descriptorId(),
                    result.keyId(), result.validationCoverage());
        }
        return AppChainDevtoolsCli.EXIT_OK;
    }

    private int plugin(PluginOptions options) throws IOException {
        return switch (options.action()) {
            case "inspect", "validate" -> inspectPlugin(options);
            case "sign" -> signPlugin(options);
            case "scaffold" -> scaffoldPlugin(options);
            default -> throw new Usage("Unknown plugin subcommand: " + safe(options.action()));
        };
    }

    private int inspectPlugin(PluginOptions options) throws IOException {
        AppChainComponentCatalogLoader loader = new AppChainComponentCatalogLoader();
        AppChainComponentCatalogLoader.Loaded loaded = isPluginArchive(options.input())
                ? loader.loadJar(options.input(), options.trustKeys())
                : loader.loadSnapshot(options.input(), options.trustKeys());
        // Applying the product catalog enforces graph, namespace, tier, and native-claim rules.
        AppChainPropertyRegistry extended = loader.extendRegistry(properties, List.of(loaded));
        new AppChainProjectCatalog(extended, List.of(loaded));
        if (options.output() != null) {
            writeNewFile(options.output(), loaded.snapshotBytes(), "catalog snapshot output");
        }
        if (options.format() == Format.JSON) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "PLUGIN_CATALOG_VALID");
            result.put("catalogId", loaded.catalog().catalogId());
            result.put("bundleId", loaded.catalog().bundleId());
            result.put("bundleVersion", loaded.catalog().bundleVersion());
            result.put("artifact", loaded.catalog().artifact());
            result.put("capabilities", loaded.catalog().capabilities());
            result.put("publisherKeyId", loaded.trust().keyId());
            result.put("catalogDigest", loaded.catalogSha256());
            result.put("artifactDigest", loaded.snapshot().artifactSha256());
            result.put("snapshotOutput", options.output() == null
                    ? null : fileName(options.output().toAbsolutePath().normalize()));
            out.println(json.writeValueAsString(result));
        } else {
            out.printf(Locale.ROOT,
                    "PLUGIN_CATALOG_VALID catalog=%s bundle=%s version=%s key=%s%n",
                    loaded.catalog().catalogId(), loaded.catalog().bundleId(),
                    loaded.catalog().bundleVersion(), loaded.trust().keyId());
            if ("inspect".equals(options.action())) {
                for (AppChainProjectModel.Capability capability
                        : loaded.catalog().capabilities()) {
                    out.printf(Locale.ROOT, "%s\t%s\t%s\t%s%n",
                            capability.id(), capability.category(), capability.availability(),
                            capability.description());
                }
            }
            if (options.output() != null) {
                out.println("SNAPSHOT_WRITTEN "
                        + fileName(options.output().toAbsolutePath().normalize()));
            }
        }
        return AppChainDevtoolsCli.EXIT_OK;
    }

    private int signPlugin(PluginOptions options) throws IOException {
        AppChainComponentCatalogLoader loader = new AppChainComponentCatalogLoader();
        AppChainComponentCatalogLoader.SignedCatalog signed = loader.sign(
                options.catalog(), options.runtimeManifest(), options.configurationMetadata(),
                options.keyId(), options.seedFile());
        loader.writeSignature(options.output(), signed);
        if (options.format() == Format.JSON) {
            out.println(json.writeValueAsString(Map.of(
                    "status", "PLUGIN_CATALOG_SIGNED",
                    "publisherKeyId", options.keyId(),
                    "publisherPublicKey", signed.publicKeyHex(),
                    "output", fileName(options.output().toAbsolutePath().normalize()))));
        } else {
            out.printf(Locale.ROOT, "PLUGIN_CATALOG_SIGNED key=%s public-key=%s output=%s%n",
                    options.keyId(), signed.publicKeyHex(),
                    fileName(options.output().toAbsolutePath().normalize()));
        }
        return AppChainDevtoolsCli.EXIT_OK;
    }

    private int scaffoldPlugin(PluginOptions options) throws IOException {
        AppChainPluginScaffolder.Result result = new AppChainPluginScaffolder().scaffold(
                options.mode(), options.componentId(), options.packageName(),
                valueOr(options.yanoVersion(), toolVersion()), options.output());
        if (options.format() == Format.JSON) {
            out.println(json.writeValueAsString(Map.of(
                    "status", "PLUGIN_SCAFFOLDED",
                    "mode", result.mode(),
                    "componentId", result.componentId(),
                    "catalogId", result.catalogId(),
                    "output", fileName(result.output()))));
        } else {
            out.printf(Locale.ROOT, "PLUGIN_SCAFFOLDED mode=%s id=%s output=%s%n",
                    result.mode(), result.componentId(), fileName(result.output()));
        }
        return AppChainDevtoolsCli.EXIT_OK;
    }

    static AppChainProjectModel.AuthenticatedMapIntent defaultAuthenticatedMapIntent() {
        return new AppChainProjectModel.AuthenticatedMapIntent(
                "mpf-blake2b256-v1",
                "00".repeat(32),
                128,
                65_536,
                List.of(new AppChainProjectModel.AuthenticatedMapCollectionIntent(
                        "records", "open", false, 128, 65_536,
                        "opaque", null)),
                List.of());
    }

    private int recipes(Format format) throws IOException {
        if (format == Format.JSON) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "RECIPE_CATALOG");
            result.put("schemaVersion", "v1alpha1");
            result.put("recipes", catalog.recipes());
            out.println(json.writeValueAsString(result));
        } else {
            for (AppChainProjectModel.Recipe recipe : catalog.recipes()) {
                out.printf(Locale.ROOT, "%s\t%s\t%s\t%s\t%s%n",
                        recipe.id(), recipe.category(), recipe.availability(),
                        recipe.maturity(), recipe.description());
            }
        }
        return AppChainDevtoolsCli.EXIT_OK;
    }

    private int capabilities(Format format) throws IOException {
        if (format == Format.JSON) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "CAPABILITY_CATALOG");
            result.put("schemaVersion", "v1alpha1");
            result.put("capabilities", catalog.capabilities());
            out.println(json.writeValueAsString(result));
        } else {
            printCapabilityTable(catalog.capabilities());
        }
        return AppChainDevtoolsCli.EXIT_OK;
    }

    private void printCapabilityTable(List<AppChainProjectModel.Capability> capabilities) {
        String border = consoleBorder(CAPABILITY_COLUMNS);
        out.println(border);
        printConsoleRow(CAPABILITY_COLUMNS.stream().map(ConsoleColumn::heading).toList());
        out.println(border);
        for (AppChainProjectModel.Capability capability : capabilities) {
            printConsoleRow(List.of(
                    capability.id(),
                    capability.category(),
                    capability.availability(),
                    capability.maturity(),
                    capability.effectiveScope(),
                    capability.effectiveSelectable() ? "selectable" : "derived"));
            printConsoleDetail("Description", capability.description(), border.length());
            out.println(border);
        }
    }

    private void printConsoleRow(List<String> values) {
        List<List<String>> wrapped = new ArrayList<>(CAPABILITY_COLUMNS.size());
        int height = 1;
        for (int index = 0; index < CAPABILITY_COLUMNS.size(); index++) {
            List<String> lines = wrapConsoleValue(values.get(index),
                    CAPABILITY_COLUMNS.get(index).width());
            wrapped.add(lines);
            height = Math.max(height, lines.size());
        }
        for (int line = 0; line < height; line++) {
            StringBuilder row = new StringBuilder("| ");
            for (int column = 0; column < CAPABILITY_COLUMNS.size(); column++) {
                if (column > 0) row.append(" | ");
                List<String> lines = wrapped.get(column);
                String value = line < lines.size() ? lines.get(line) : "";
                row.append(padConsoleValue(value, CAPABILITY_COLUMNS.get(column).width()));
            }
            out.println(row.append(" |").toString());
        }
    }

    private void printConsoleDetail(String label, String value, int tableWidth) {
        String prefix = label + ": ";
        int contentWidth = tableWidth - 4;
        int valueWidth = contentWidth - prefix.length();
        List<String> lines = wrapConsoleValue(value, valueWidth);
        for (int index = 0; index < lines.size(); index++) {
            String start = index == 0 ? prefix : " ".repeat(prefix.length());
            out.println("| " + padConsoleValue(start + lines.get(index), contentWidth) + " |");
        }
    }

    private static String consoleBorder(List<ConsoleColumn> columns) {
        return "+-" + columns.stream()
                .map(column -> "-".repeat(column.width()))
                .collect(java.util.stream.Collectors.joining("-+-")) + "-+";
    }

    private static List<String> wrapConsoleValue(String value, int width) {
        String remaining = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        if (remaining.isEmpty()) return List.of("");
        List<String> lines = new ArrayList<>();
        while (remaining.length() > width) {
            int split = remaining.lastIndexOf(' ', width);
            if (split <= 0) split = width;
            lines.add(remaining.substring(0, split));
            remaining = remaining.substring(split).stripLeading();
        }
        lines.add(remaining);
        return List.copyOf(lines);
    }

    private static String padConsoleValue(String value, int width) {
        return value + " ".repeat(Math.max(0, width - value.length()));
    }

    private static void parseTrustKey(String assignment, Map<String, String> trustKeys) {
        int separator = assignment.indexOf('=');
        if (separator < 1 || separator == assignment.length() - 1) {
            throw new Usage("--trust-key must be key-id=64-hex-public-key");
        }
        String id = assignment.substring(0, separator);
        String key = assignment.substring(separator + 1);
        if (trustKeys.putIfAbsent(id, key) != null) {
            throw new Usage("--trust-key id may be specified once: " + safe(id));
        }
        if (trustKeys.size() > 32) {
            throw new Usage("at most 32 publisher trust keys are accepted");
        }
    }

    private static Format parseFormatOnly(String[] arguments) {
        if (arguments.length == 0) return Format.TEXT;
        if (arguments.length == 2 && "--format".equals(arguments[0])) {
            return parseFormat(arguments[1]);
        }
        throw new Usage("recipes accepts only --format text|json");
    }

    private static PluginOptions parsePlugin(String[] arguments) {
        if (arguments.length == 0) {
            throw new Usage("plugin requires inspect, validate, sign, or scaffold");
        }
        String action = arguments[0];
        if (!Set.of("inspect", "validate", "sign", "scaffold").contains(action)) {
            throw new Usage("plugin requires inspect, validate, sign, or scaffold");
        }
        Path input = null;
        Path output = null;
        Path catalog = null;
        Path runtimeManifest = null;
        Path configurationMetadata = null;
        Path seedFile = null;
        String keyId = null;
        String mode = null;
        String componentId = null;
        String packageName = null;
        String yanoVersion = null;
        Format format = Format.TEXT;
        Map<String, String> trustKeys = new LinkedHashMap<>();
        for (int cursor = 1; cursor < arguments.length; cursor++) {
            String argument = arguments[cursor];
            switch (argument) {
                case "--output" -> {
                    if (output != null) throw new Usage("--output may be specified once");
                    output = path(value(arguments, ++cursor, argument));
                }
                case "--trust-key" -> parseTrustKey(
                        value(arguments, ++cursor, argument), trustKeys);
                case "--catalog" -> {
                    if (catalog != null) throw new Usage("--catalog may be specified once");
                    catalog = path(value(arguments, ++cursor, argument));
                }
                case "--runtime-manifest" -> {
                    if (runtimeManifest != null) {
                        throw new Usage("--runtime-manifest may be specified once");
                    }
                    runtimeManifest = path(value(arguments, ++cursor, argument));
                }
                case "--config-metadata" -> {
                    if (configurationMetadata != null) {
                        throw new Usage("--config-metadata may be specified once");
                    }
                    configurationMetadata = path(value(arguments, ++cursor, argument));
                }
                case "--seed-file" -> {
                    if (seedFile != null) throw new Usage("--seed-file may be specified once");
                    seedFile = path(value(arguments, ++cursor, argument));
                }
                case "--key-id" -> keyId = once(
                        keyId, value(arguments, ++cursor, argument), argument);
                case "--mode" -> mode = once(
                        mode, value(arguments, ++cursor, argument), argument);
                case "--id" -> componentId = once(
                        componentId, value(arguments, ++cursor, argument), argument);
                case "--package" -> packageName = once(
                        packageName, value(arguments, ++cursor, argument), argument);
                case "--yano-version" -> yanoVersion = once(
                        yanoVersion, value(arguments, ++cursor, argument), argument);
                case "--format" -> format = parseFormat(
                        value(arguments, ++cursor, argument));
                default -> {
                    if (argument.startsWith("--")) {
                        throw new Usage("Unknown plugin option: " + safe(argument));
                    }
                    if (input != null) {
                        throw new Usage("plugin subcommand accepts one positional input");
                    }
                    input = path(argument);
                }
            }
        }
        if (Set.of("inspect", "validate").contains(action)) {
            if (input == null || trustKeys.isEmpty()) {
                throw new Usage(action + " requires an artifact/snapshot and --trust-key");
            }
            if (catalog != null || runtimeManifest != null || seedFile != null || keyId != null
                    || mode != null || componentId != null || packageName != null) {
                throw new Usage("sign/scaffold options are invalid for " + action);
            }
        } else if ("sign".equals(action)) {
            if (input != null || catalog == null || runtimeManifest == null || seedFile == null
                    || keyId == null || output == null || !trustKeys.isEmpty()) {
                throw new Usage("sign requires --catalog, --runtime-manifest, --seed-file, "
                        + "--key-id, and --output");
            }
        } else if (input != null || mode == null || componentId == null
                || packageName == null || output == null || !trustKeys.isEmpty()
                || catalog != null || runtimeManifest != null || seedFile != null
                || keyId != null || configurationMetadata != null) {
            throw new Usage("scaffold requires --mode, --id, --package, and --output");
        }
        return new PluginOptions(action, input, output, Map.copyOf(trustKeys),
                catalog, runtimeManifest, configurationMetadata, seedFile, keyId,
                mode, componentId, packageName, yanoVersion, format);
    }

    private static MetadataOptions parseMetadata(String[] arguments) {
        if (arguments.length == 0 || !"verify".equals(arguments[0])) {
            throw new Usage("metadata requires the verify subcommand");
        }
        Path artifact = null;
        Map<String, String> trustKeys = new LinkedHashMap<>();
        Format format = Format.TEXT;
        for (int cursor = 1; cursor < arguments.length; cursor++) {
            String argument = arguments[cursor];
            if ("--trust-key".equals(argument)) {
                parseTrustKey(value(arguments, ++cursor, argument), trustKeys);
            } else if ("--format".equals(argument)) {
                format = parseFormat(value(arguments, ++cursor, argument));
            } else if (argument.startsWith("--")) {
                throw new Usage("Unknown metadata verify option: " + safe(argument));
            } else if (artifact != null) {
                throw new Usage("metadata verify accepts one plugin artifact");
            } else {
                artifact = path(argument);
            }
        }
        if (artifact == null || trustKeys.isEmpty()) {
            throw new Usage("metadata verify requires a plugin artifact and --trust-key");
        }
        return new MetadataOptions(artifact, Map.copyOf(trustKeys), format);
    }

    private static Format parseFormat(String value) {
        return switch (value) {
            case "text" -> Format.TEXT;
            case "json" -> Format.JSON;
            default -> throw new Usage("--format must be text or json");
        };
    }

    private static String once(String prior, String value, String option) {
        if (prior != null) throw new Usage(option + " may be specified once");
        return value;
    }

    private static String value(String[] arguments, int index, String option) {
        if (index >= arguments.length) throw new Usage(option + " requires a value");
        return arguments[index];
    }

    private static Path path(String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException failure) {
            throw new Usage("Path is invalid");
        }
    }

    private static boolean isPluginArchive(Path path) {
        String file = path.getFileName() == null ? ""
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return file.endsWith(".jar") || file.endsWith(".zip");
    }

    private static void writeNewFile(Path output, byte[] bytes, String label) throws IOException {
        Path target = output.toAbsolutePath().normalize();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " already exists");
        }
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static boolean helpRequested(String[] arguments) {
        for (String argument : arguments) {
            if ("help".equals(argument) || "-h".equals(argument) || "--help".equals(argument)) {
                return true;
            }
        }
        return false;
    }

    private static String slug(String value) {
        if (value == null || value.isBlank()) return "yano-appchain";
        String slug = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty() || !Character.isLetter(slug.charAt(0))) slug = "appchain-" + slug;
        return slug.length() <= 63 ? slug : slug.substring(0, 63).replaceAll("-+$", "");
    }

    private static String toolVersion() {
        String version = AppChainProjectCli.class.getPackage().getImplementationVersion();
        if (version == null || version.isBlank()) {
            version = System.getProperty("yano.version", "development");
        }
        return version;
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? "<project>" : safe(name.toString());
    }

    private static String valueOr(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String safe(String value) {
        if (value == null) return "<missing>";
        return value.codePoints().limit(256)
                .collect(StringBuilder::new,
                        (builder, codePoint) -> builder.appendCodePoint(
                                Character.isISOControl(codePoint) ? '?' : codePoint),
                        StringBuilder::append)
                .toString();
    }

    enum Format { TEXT, JSON }

    record MetadataOptions(
            Path artifact,
            Map<String, String> trustKeys,
            Format format) {
    }

    record PluginOptions(
            String action,
            Path input,
            Path output,
            Map<String, String> trustKeys,
            Path catalog,
            Path runtimeManifest,
            Path configurationMetadata,
            Path seedFile,
            String keyId,
            String mode,
            String componentId,
            String packageName,
            String yanoVersion,
            Format format) {
    }

    private record ConsoleColumn(String heading, int width) {
        private ConsoleColumn {
            if (heading == null || heading.isBlank() || width < heading.length()) {
                throw new IllegalArgumentException("console column is invalid");
            }
        }
    }

    static final class Usage extends IllegalArgumentException {
        Usage(String message) {
            super(message);
        }
    }
}
