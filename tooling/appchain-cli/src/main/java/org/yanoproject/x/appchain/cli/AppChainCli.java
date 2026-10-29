package org.yanoproject.x.appchain.cli;

import org.yanoproject.x.appchain.formats.FormatException;
import org.yanoproject.x.devtools.AppChainDevtoolsCli;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

/**
 * {@code yano.sh appchain}: the app-chain setup verbs of ADR-056. Every other verb goes to the devtools CLI.
 */
public final class AppChainCli {
    static final Set<String> VERBS = Set.of("new", "request", "seal", "inspect", "join", "start", "stop",
            "status", "approve", "anchor", "try", "invite", "ui", "topics");

    private static final String HELP = """
            Set up and run an app chain with other operators.

            Practise on one machine:
              try                       run every step with three simulated operators

            In a browser:
              ui                        the same steps on a local page (127.0.0.1 only)

            Coordinator:
              new [<app>]               create <app>.draft.yaml (a wizard asks the questions)
              invite <draft> --serve    hand out one link per operator; cards and the sealed file travel by link
              seal <app>.draft.yaml     seal the draft with the member cards beside it → <app>.chain

            Every operator:
              request <draft|chain|link>  make this node's key and member card (<name>.member)
              join <app>.chain          check the sealed file and set up this node
              start | stop              run or stop this node
              status [<app>]            where the application is, and what to do next
              approve <name>.member     add a member (--remove <name> removes one)
              anchor [<app>]            optional: anchor a chain to Cardano from this node
              topics approve <plan>     vote on a namespace change (chains whose draft has topics:)
              inspect <file>            show and check a draft, card or sealed file

            Namespace owners and application developers:
              topics plan|sign|submit   manage a namespace's owners, writers and payload schema
              topics resolve|delegate   check a topic's policy; issue a session-key credential

            Each command prints a Next: line. Add --help to a command for its options.
            Other tools: recipes, capabilities, bindings, state, role, plugin, metadata, config,
            authenticated-map, eutxo (see their --help).
            """;

    private AppChainCli() {
    }

    public static void main(String[] arguments) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        boolean interactive = System.console() != null && !Arrays.asList(arguments).contains("--yes");
        System.exit(run(arguments, new Terminal(in, out, interactive), System.err));
    }

    static int run(String[] arguments, Terminal terminal, PrintStream err) {
        return run(arguments, terminal, err, null);
    }

    /** {@code distribution} replaces the located distribution (tests); {@code null} locates it. */
    static int run(String[] arguments, Terminal terminal, PrintStream err, Distribution distribution) {
        java.util.function.Supplier<Distribution> located = () -> distribution != null ? distribution
                : Distribution.locate();
        java.util.function.Supplier<Distribution> optional = () -> distribution != null ? distribution
                : Distribution.optional();
        String[] args = arguments.length > 0 && "appchain".equals(arguments[0])
                ? Arrays.copyOfRange(arguments, 1, arguments.length) : arguments;
        if (args.length == 0 || "help".equals(args[0]) || "--help".equals(args[0]) || "-h".equals(args[0])) {
            terminal.out().print(HELP);
            return 0;
        }
        String verb = args[0];
        if (!VERBS.contains(verb)) {
            PrintWriter out = new PrintWriter(terminal.out(), true, StandardCharsets.UTF_8);
            PrintWriter error = new PrintWriter(err, true, StandardCharsets.UTF_8);
            return new AppChainDevtoolsCli().run(args, out, error);
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        if ("topics".equals(verb) && !(rest.length > 0 && "approve".equals(rest[0]))) {
            try {
                return new TopicsCommand(terminal, err).run(rest);
            } catch (CliException failure) {
                err.println(failure.getMessage());
                return failure.exitCode();
            } catch (java.io.IOException error) {
                err.println("I/O error: " + error.getMessage());
                return CliException.FAILURE;
            }
        }
        if (Arrays.asList(rest).contains("--help")) {
            terminal.say("Usage: " + usage(verb));
            return 0;
        }
        try {
            return switch (verb) {
                case "new" -> new NewCommand(terminal, optional.get()).run(rest);
                case "request" -> new RequestCommand(terminal, located).run(rest);
                case "seal" -> new SealCommand(terminal, located.get()).run(rest);
                case "inspect" -> new InspectCommand(terminal).run(rest);
                case "join" -> new JoinCommand(terminal, located.get()).run(rest);
                case "start" -> new StartCommand(terminal, located.get()).run(rest);
                case "stop" -> new StopCommand(terminal).run(rest);
                case "status" -> new StatusCommand(terminal).run(rest);
                case "approve" -> new ApproveCommand(terminal, located.get()).run(rest);
                case "anchor" -> new AnchorCommand(terminal, located.get()).run(rest);
                case "try" -> new TryCommand(terminal, err).run(rest);
                case "invite" -> new InviteCommand(terminal, located.get(), err).run(rest);
                case "ui" -> new UiCommand(terminal, optional.get(), err).run(rest);
                case "topics" -> new TopicsCommand(terminal, err).run(rest);
                default -> throw CliException.usage("'" + verb + "' is not available in this build yet");
            };
        } catch (CliException failure) {
            err.println(failure.getMessage());
            return failure.exitCode();
        } catch (FormatException invalid) {
            err.println(invalid.getMessage());
            return CliException.FAILURE;
        } catch (java.io.IOException | java.io.UncheckedIOException error) {
            err.println("I/O error: " + error.getMessage());
            return CliException.FAILURE;
        }
    }

    static String usage(String verb) {
        return switch (verb) {
            case "new" -> NewCommand.USAGE;
            case "request" -> RequestCommand.USAGE;
            case "seal" -> SealCommand.USAGE;
            case "inspect" -> InspectCommand.USAGE;
            case "join" -> JoinCommand.USAGE;
            case "start" -> StartCommand.USAGE;
            case "stop" -> StopCommand.USAGE;
            case "status" -> StatusCommand.USAGE;
            case "approve" -> ApproveCommand.USAGE;
            case "anchor" -> AnchorCommand.USAGE;
            case "try" -> TryCommand.USAGE;
            case "invite" -> InviteCommand.USAGE;
            case "ui" -> UiCommand.USAGE;
            case "topics" -> TopicsCommand.APPROVE_USAGE;
            default -> "yano.sh appchain " + verb;
        };
    }
}
