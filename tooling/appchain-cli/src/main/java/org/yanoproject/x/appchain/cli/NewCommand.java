package org.yanoproject.x.appchain.cli;

import org.yanoproject.x.appchain.formats.Draft;
import org.yanoproject.x.appchain.formats.Names;
import org.yanoproject.x.devtools.AppChainSealResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@code new}: the coordinator's wizard; writes {@code <app>.draft.yaml} with a fresh invite code. */
final class NewCommand {
    static final String USAGE = "yano.sh appchain new [<app>] [--network devnet|preview|preprod|mainnet]"
            + " [--operators alice,bob,carol] [--chain <id>=<recipe>|<id>=bindings:<file>]..."
            + " [--answer <chain>.<name>=<value>]... [--topics <chain>=members|anyone|registrars:<a>+<b>]..."
            + " [--threshold majority|two-thirds|all|<n>]"
            + " [--membership governed|static] [--sequencing fixed|rotating] [--output <dir>]"
            + " [--force] [--yes]";
    private static final String COMPOSITE = "declarative-composite";

    private final Terminal terminal;
    private final Distribution distribution;

    NewCommand(Terminal terminal, Distribution distribution) {
        this.terminal = terminal;
        this.distribution = distribution;
    }

    int run(String[] arguments) throws IOException {
        Args args = new Args(arguments, Set.of("network", "operators", "chain", "answer", "topics", "threshold",
                "membership", "sequencing", "output"),
                Set.of("force", "yes"));
        args.maxPositionals(1, USAGE);
        Path output = args.path("output", Path.of("."));
        String name = Names.name(args.positional(0) != null ? args.positional(0)
                : terminal.ask("Application name", "my-app", "<app>"), "Application name");
        String network = args.value("network") != null ? Names.network(args.value("network"))
                : terminal.choose("Which Cardano network does this application use?", Names.NETWORKS,
                List.of("local test network, created by the lead's node (good for trying things)",
                        "public test network", "public test network", "Cardano mainnet"),
                "devnet", "--network");
        String operatorList = args.value("operators") != null ? args.value("operators")
                : terminal.ask("Who runs a node? Operator names, comma-separated, you first (e.g. alice,bob,carol)",
                null, "--operators");
        List<String> operators = Arrays.stream(operatorList.split(",")).map(String::strip)
                .filter(value -> !value.isEmpty()).toList();

        String membership = args.value("membership", "governed");
        List<Draft.Chain> chains = new ArrayList<>();
        Map<String, String> recipes = AppChainSealResolver.recipes();
        if (!args.values("chain").isEmpty()) {
            for (String spec : args.values("chain")) chains.add(chainFromFlag(spec, output));
        } else {
            do {
                Draft.Chain chain = askChain(chains.isEmpty() ? name : null, recipes, output);
                if ("governed".equals(membership) && args.values("topics").isEmpty()) {
                    chain = chain.withTopics(askTopics(chain.id(), operators));
                }
                chains.add(chain);
            } while (terminal.confirm("Add another chain to this application?", false));
        }

        chains = withAnswers(chains, args.values("answer"));
        chains = withTopics(chains, args.values("topics"));

        String threshold = args.value("threshold", "majority");
        Draft draft = new Draft(name, Names.newInvite(), network, operators, threshold, membership,
                args.value("sequencing", "fixed"), chains);
        Path file = output.resolve(name + ".draft.yaml");
        if (Files.exists(file) && !args.flag("force")) {
            throw CliException.failure(file + " already exists; edit it, or pass --force to start over "
                    + "(a new invite code makes existing member cards invalid)");
        }
        NodeDir.write(file, draft.toYaml());

        int signatures = Draft.thresholdFor(draft.threshold(), operators.size());
        terminal.ok("Wrote " + file);
        terminal.say("  invite code " + draft.invite() + "   operators " + String.join(", ", operators)
                + "   lead " + draft.lead());
        terminal.say("  " + operators.size() + " operators, " + signatures + " signatures per block. While a chain"
                + " grows by one member it briefly needs one more signature.");
        terminal.say("  The lead's key cannot be replaced later: " + draft.lead()
                + " should back up their node directory.");
        terminal.next("send " + file.getFileName() + " to every operator (you too); each runs",
                "  yano.sh appchain request " + file.getFileName(),
                "and sends back <name>.member. Put the cards next to the draft, then run",
                "  yano.sh appchain seal " + file.getFileName());
        return 0;
    }

    /** Applies {@code --topics chain=members|anyone|registrars:alice+bob}. */
    private static List<Draft.Chain> withTopics(List<Draft.Chain> chains, List<String> flags) {
        Map<String, Draft.Topics> topics = new HashMap<>();
        for (String flag : flags) {
            int equals = flag.indexOf('=');
            if (equals <= 0) {
                throw CliException.usage("--topics must look like games=members or games=registrars:alice");
            }
            String id = flag.substring(0, equals);
            if (topics.containsKey(id)) throw CliException.usage("--topics names chain '" + id + "' twice");
            String value = flag.substring(equals + 1);
            if (chains.stream().noneMatch(chain -> chain.id().equals(id))) {
                throw CliException.usage("--topics names chain '" + id + "', which the application does not have");
            }
            List<String> registrars = value.startsWith("registrars:")
                    ? Arrays.stream(value.substring("registrars:".length()).split("\\+")).map(String::strip)
                    .filter(item -> !item.isEmpty()).toList() : List.of();
            String create = value.startsWith("registrars:") ? "registrars" : value;
            topics.put(id, new Draft.Topics(create, registrars, registrars.isEmpty() ? 0 : 1, "open", Map.of()));
        }
        return chains.stream().map(chain -> topics.containsKey(chain.id()) ? chain.withTopics(topics.get(chain.id()))
                : chain).toList();
    }

    /** The wizard's one topics question; most chains answer no. */
    private Draft.Topics askTopics(String id, List<String> operators) {
        if (!terminal.confirm("Protect topics on '" + id + "' with namespaces, so each topic has owners and a "
                + "writer policy?", false)) {
            return null;
        }
        String create = terminal.choose("Who may create a top-level namespace on '" + id + "'?",
                Draft.Topics.CREATE, List.of("the members, by a vote that reaches the block threshold",
                        "named operators sign it alone (members can still vote)",
                        "anyone, first come (members can still recover a namespace)"),
                "members", "--topics " + id + "=<who>");
        List<String> registrars = List.of();
        if ("registrars".equals(create)) {
            registrars = Arrays.stream(terminal.ask("Registrars (operator names, comma-separated)",
                    operators.getFirst(), "--topics " + id + "=registrars:<a>+<b>").split(","))
                    .map(String::strip).filter(item -> !item.isEmpty()).toList();
        }
        String unregistered = terminal.choose("Messages on topics that no namespace covers:",
                Draft.Topics.UNREGISTERED,
                List.of("accept them as today; protect only what you register",
                        "refuse them; the chain then makes blocks only from registered topics"), "open",
                "unregistered: in the draft");
        return new Draft.Topics(create, registrars, registrars.isEmpty() ? 0 : 1, unregistered, Map.of());
    }

    /** Adds {@code --answer chain.name=value} values, then asks for any answer a recipe still needs. */
    private List<Draft.Chain> withAnswers(List<Draft.Chain> chains, List<String> flags) throws IOException {
        List<Draft.Chain> result = new ArrayList<>();
        for (Draft.Chain chain : chains) {
            Map<String, String> answers = new java.util.LinkedHashMap<>(chain.answers());
            for (String flag : flags) {
                int dot = flag.indexOf('.');
                int equals = flag.indexOf('=');
                if (dot <= 0 || equals < dot) {
                    throw CliException.usage("--answer must look like orders.eutxoGenesisLovelace=100000000");
                }
                if (flag.substring(0, dot).equals(chain.id())) {
                    answers.put(flag.substring(dot + 1, equals), flag.substring(equals + 1));
                }
            }
            for (String needed : AppChainSealResolver.recipeAnswers(chain.recipe())) {
                if (!answers.containsKey(needed)) {
                    answers.put(needed, terminal.ask("Chain '" + chain.id() + "' (" + chain.recipe() + ") needs "
                            + needed, null, "--answer " + chain.id() + "." + needed + "=<value>"));
                }
            }
            result.add(new Draft.Chain(chain.id(), chain.recipe(), chain.capabilities(), answers, chain.bindings(),
                    chain.composite(), chain.authenticatedMap(), chain.anchor(), chain.settings(), chain.topics()));
        }
        return result;
    }

    private Draft.Chain chainFromFlag(String spec, Path output) throws IOException {
        int equals = spec.indexOf('=');
        if (equals <= 0) throw CliException.usage("--chain must look like orders=audit-log or flow=bindings:flow.yaml");
        String id = Names.chainId(spec.substring(0, equals));
        String value = spec.substring(equals + 1);
        if (value.startsWith("bindings:")) {
            return composite(id, Path.of(value.substring("bindings:".length())), output);
        }
        Map<String, String> recipes = AppChainSealResolver.recipes();
        if (COMPOSITE.equals(value)) {
            throw CliException.usage("Chain '" + id + "' composes state machines; pass --chain " + id
                    + "=bindings:<file>");
        }
        if (!recipes.containsKey(value)) {
            throw CliException.usage("Unknown recipe '" + value + "' for chain '" + id + "'. Recipes: "
                    + String.join(", ", recipes.keySet().stream().filter(recipe -> !COMPOSITE.equals(recipe))
                    .toList()));
        }
        return new Draft.Chain(id, value, List.of(), Map.of(), null, null, null, null, Map.of());
    }

    private Draft.Chain askChain(String suggested, Map<String, String> recipes, Path output) throws IOException {
        String id = Names.chainId(terminal.ask("Chain id", suggested == null ? null : suggested, "--chain"));
        String kind = terminal.choose("Does chain '" + id + "' run one state machine, or several working together?",
                List.of("one", "several"),
                List.of("pick a ready-made recipe", "compose state machines with bindings (a starter is copied)"),
                "one", "--chain");
        if ("one".equals(kind)) {
            List<String> ids = recipes.keySet().stream().filter(recipe -> !COMPOSITE.equals(recipe)).toList();
            String recipe = terminal.choose("Recipe for '" + id + "'", ids,
                    ids.stream().map(recipes::get).toList(), "audit-log", "--chain");
            return new Draft.Chain(id, recipe, List.of(), Map.of(), null, null, null, null, Map.of());
        }
        List<Path> starters = starters();
        if (starters.isEmpty()) {
            throw CliException.failure("No binding starters in " + distribution.bindingStarters()
                    + "; pass --chain " + id + "=bindings:<file>");
        }
        String starter = terminal.choose("Starter bindings for '" + id + "'",
                starters.stream().map(path -> path.getFileName().toString()).toList(), null,
                starters.getFirst().getFileName().toString(), "--chain");
        return composite(id, distribution.bindingStarters().resolve(starter), output);
    }

    /** Copies the bindings next to the draft so the coordinator can edit them before seal. */
    private Draft.Chain composite(String id, Path source, Path output) throws IOException {
        if (!Files.isRegularFile(source)) throw CliException.failure("No bindings file " + source);
        Path copy = output.resolve(id + ".bindings.yaml");
        if (!copy.toAbsolutePath().normalize().equals(source.toAbsolutePath().normalize())) {
            Files.createDirectories(output);
            Files.copy(source, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return new Draft.Chain(id, COMPOSITE, List.of(), Map.of(), "./" + copy.getFileName(), null, null, null,
                Map.of());
    }

    private List<Path> starters() throws IOException {
        Path directory = distribution.bindingStarters();
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.toString().endsWith(".yaml")).sorted().toList();
        }
    }

}
