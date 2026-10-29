package org.yanoproject.x.appchain.cli;

import org.yanoproject.x.appchain.formats.Canonical;
import org.yanoproject.x.appchain.formats.Draft;
import org.yanoproject.x.appchain.formats.MemberCard;
import org.yanoproject.x.appchain.formats.SealedApp;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** {@code inspect}: shows a draft, member card or sealed file, and checks it. */
final class InspectCommand {
    static final String USAGE = "yano.sh appchain inspect <file> [--json] [--map-genesis <chain>]";

    private final Terminal terminal;

    InspectCommand(Terminal terminal) {
        this.terminal = terminal;
    }

    int run(String[] arguments) {
        Args args = new Args(arguments, Set.of("map-genesis"), Set.of("json"));
        args.maxPositionals(1, USAGE);
        if (args.positional(0) == null) throw CliException.usage("Name a file. Usage: " + USAGE);
        Path file = Path.of(args.positional(0));
        String text = NodeDir.read(file);
        String name = file.getFileName().toString();
        if (name.endsWith(".chain")) {
            SealedApp app = SealedApp.parse(text);
            if (args.value("map-genesis") != null) {
                // The authenticated map's genesis bytes, as offline tools such as `state verify` take them.
                String hex = app.genesis().chain(args.value("map-genesis")).settings()
                        .get("machines.authenticated-map.genesis-cbor-hex");
                if (hex == null) {
                    throw CliException.failure("Chain " + args.value("map-genesis") + " has no authenticated map");
                }
                terminal.say(hex);
                return 0;
            }
            if (args.flag("json")) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("kind", "chain");
                result.put("fingerprint", app.fingerprint());
                result.put("displayFingerprint", app.displayFingerprint());
                result.put("application", app);
                terminal.out().print(Canonical.pretty(result));
            } else {
                Show.sealed(terminal, app);
            }
        } else if (name.endsWith(".member")) {
            MemberCard card = MemberCard.parse(text);
            boolean valid = card.signatureValid();
            if (args.flag("json")) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("kind", "member");
                result.put("signatureValid", valid);
                result.put("keyId", Show.keyId(card.publicKey()));
                result.put("card", card);
                terminal.out().print(Canonical.pretty(result));
            } else {
                terminal.say("Member card for " + card.name() + " (invite " + card.invite() + ")");
                terminal.say("  key id " + Show.keyId(card.publicKey()) + "   node " + card.node() + "   http "
                        + card.http());
                terminal.say("  signature " + (valid ? "valid" : "INVALID"));
            }
            if (!valid) return CliException.FAILURE;
        } else {
            Draft draft = Draft.parse(text);
            if (args.flag("json")) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("kind", "draft");
                result.put("draft", Map.of("name", draft.name(), "invite", draft.invite(),
                        "network", draft.network(), "operators", draft.operators(),
                        "threshold", draft.threshold(), "membership", draft.membership(),
                        "chains", draft.chains().stream().map(Draft.Chain::id).toList()));
                terminal.out().print(Canonical.pretty(result));
            } else {
                terminal.say("Draft " + draft.name() + " on " + draft.network() + ", invite " + draft.invite());
                terminal.say("  operators " + String.join(", ", draft.operators()) + " (lead " + draft.lead()
                        + "), threshold " + draft.threshold() + " = "
                        + Draft.thresholdFor(draft.threshold(), draft.operators().size()) + " signatures");
                for (Draft.Chain chain : draft.chains()) {
                    terminal.say("  chain " + chain.id() + ": " + chain.recipe()
                            + (chain.bindings() == null ? "" : " with " + chain.bindings()));
                    if (chain.topics() != null) terminal.say("    topics: " + chain.topics().summary());
                }
            }
        }
        return 0;
    }
}
