package org.yanoproject.x.appchain.cli;

import org.yanoproject.x.appchain.formats.Draft;
import org.yanoproject.x.appchain.formats.Names;
import org.yanoproject.x.devtools.AppChainSealResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@code new}: the coordinator's wizard; writes {@code <app>.draft.yaml} with a fresh invite code. */
final class NewCommand {
    static final String USAGE = "yano.sh appchain new [<app>] [--network devnet|preview|preprod|mainnet]"
            + " [--operators alice,bob,carol] [--chain <id>=<recipe>|<id>=bindings:<file>]..."
            + " [--answer <chain>.<name>=<value>]... [--threshold majority|two-thirds|all|<n>]"
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
        Args args = new Args(arguments, Set.of("network", "operators", "chain", "answer", "threshold", "membership",
                "sequencing", "output"),
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

        List<Draft.Chain> chains = new ArrayList<>();
        Map<String, String> recipes = AppChainSealResolver.recipes();
        if (!args.values("chain").isEmpty()) {
            for (String spec : args.values("chain")) chains.add(chainFromFlag(spec, output));
        } else {
            do {
                chains.add(askChain(chains.isEmpty() ? name : null, recipes, output));
            } while (terminal.confirm("Add another chain to this application?", false));
        }

        chains = withAnswers(chains, args.values("answer"));

        String threshold = args.value("threshold", "majority");
        String membership = args.value("membership", "governed");
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
                    chain.composite(), chain.authenticatedMap(), chain.anchor(), chain.settings()));
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
