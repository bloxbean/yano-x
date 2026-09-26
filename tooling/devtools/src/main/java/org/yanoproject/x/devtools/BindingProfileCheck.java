package org.yanoproject.x.devtools;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yanoproject.api.appchain.AppQueryContext;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.x.composite.CompositeProfile;
import org.yanoproject.x.composite.CompositeProfileCodec;
import org.yanoproject.x.composite.CompositeStateKeys;
import org.yanoproject.x.composite.contracts.BindingIrV1;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only compatibility preflight against the actual candidate plugin catalog.
 * It verifies executable profile reconstruction, not retained state, binary semantic equivalence or migration.
 */
public final class BindingProfileCheck {
    private static final String ASSURANCE = "Profile reconstruction only; not migration, replay qualification, "
            + "proof verification, or binary semantic equivalence";
    private static final String USAGE = "Usage: bindings profile-check --profiles <profiles.json> "
            + "--context <context.json> --plugins-directory <directory>";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(48)
                    .maxStringLength(131072).maxNumberLength(32).build()).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

    private BindingProfileCheck() { }

    /**
     * Runs with {@code profile-check} at argument zero; never opens retained stores or invokes machine init/apply.
     * Plugin constructors execute trusted installed code under the normal host catalog lifetime.
     *
     * @return 0 for exact reconstruction, 2 for invalid/incompatible input, 64 for usage, or 74 for I/O
     */
    public static int run(String[] args, PrintWriter out, PrintWriter err) {
        Map<String, Path> options = new LinkedHashMap<>();
        if (args.length != 7 || !"profile-check".equals(args[0])) {
            err.println(USAGE);
            err.flush();
            return 64;
        }
        for (int i = 1; i < args.length; i += 2) {
            String key = args[i];
            if (!List.of("--profiles", "--context", "--plugins-directory").contains(key)
                    || args[i + 1].isBlank() || options.containsKey(key)) {
                err.println(USAGE);
                err.flush();
                return 64;
            }
            options.put(key, Path.of(args[i + 1]));
        }
        try {
            List<CompositeProfile> profiles = parse(read(options.get("--profiles"), 8_400_000));
            var context = JSON.readValue(read(options.get("--context"), 1_048_576),
                    BindingCatalogSession.ContextInput.class);
            try (var environment = BindingPluginEnvironment.open(options.get("--plugins-directory"))) {
                Report report = check(profiles, new BindingCatalogSession(environment.providers(), context));
                out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
                return report.reproducesProfiles() ? 0 : 2;
            }
        } catch (JsonProcessingException error) {
            err.println("Profile JSON is invalid: " + diagnostic(error));
            return 2;
        } catch (IOException error) {
            err.println("Profile input could not be read: " + diagnostic(error));
            return 74;
        } catch (RuntimeException error) {
            err.println("Profile input is invalid: " + diagnostic(error));
            return 2;
        } finally {
            out.flush();
            err.flush();
        }
    }

    static List<CompositeProfile> parse(String input) throws IOException {
        JsonNode root = JSON.readTree(input);
        if (root == null || !root.isArray() || root.isEmpty() || root.size() > 64) {
            throw new IllegalArgumentException("profiles must be a JSON array of 1-64 canonical profile hex strings");
        }
        List<CompositeProfile> result = new ArrayList<>();
        var seen = new HashSet<String>();
        for (JsonNode item : root) {
            if (!item.isTextual() || item.textValue().isBlank() || item.textValue().length() > 131072) {
                throw new IllegalArgumentException("each profile must be nonempty canonical profile hex");
            }
            CompositeProfile profile = CompositeProfileCodec.decode(HexFormat.of().parseHex(item.textValue()));
            if (profile.schemaVersion() != 2 || !BindingCatalogSession.MACHINE.equals(profile.profileId())
                    || profile.bindingIr().length == 0) {
                throw new IllegalArgumentException("profile must be a declarative schema-v2 profile with explicit IR");
            }
            BindingIrV1.decode(profile.bindingIr());
            if (!seen.add(HexFormat.of().formatHex(profile.canonicalBytes()))) {
                throw new IllegalArgumentException("duplicate canonical profile");
            }
            result.add(profile);
        }
        return List.copyOf(result);
    }

    static Report check(List<CompositeProfile> profiles, BindingCatalogSession session) {
        AppStateMachine machine;
        try {
            machine = session.validateCatalog(profiles.stream()
                    .map(profile -> BindingIrV1.decode(profile.bindingIr())).toList());
        } catch (RuntimeException error) {
            return new Report(false, ASSURANCE, profiles.stream().map(profile -> result(profile, false,
                    "candidate catalog construction failed: " + diagnostic(error))).toList());
        }
        List<ProfileResult> results = new ArrayList<>();
        for (CompositeProfile profile : profiles) {
            MarkerContext marker = new MarkerContext(profile.canonicalBytes());
            try {
                byte[] actual = machine.query("composite/active-profile-v1", new byte[0], marker);
                boolean same = Arrays.equals(actual, profile.canonicalBytes());
                results.add(result(profile, same, same ? "" : "candidate returned different canonical profile bytes"));
            } catch (RuntimeException error) {
                results.add(result(profile, false, "expected profile absent or incompatible: " + diagnostic(error)));
            } finally {
                marker.expire();
            }
        }
        return new Report(results.stream().allMatch(ProfileResult::reproducesProfile), ASSURANCE, List.copyOf(results));
    }

    private static ProfileResult result(CompositeProfile profile, boolean matches, String diagnostic) {
        return new ProfileResult(HexFormat.of().formatHex(profile.digest()), profile.profileVersion(), matches,
                diagnostic);
    }

    record Report(boolean reproducesProfiles, String assurance, List<ProfileResult> profiles) { }
    record ProfileResult(String expectedDigest, String expectedProfileVersion, boolean reproducesProfile,
                         String diagnostic) { }

    private static String read(Path path, int maximum) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(maximum + 1); }
        if (bytes.length > maximum) throw new IllegalArgumentException("profile input exceeds byte limit");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String diagnostic(Exception error) {
        String text = error.getMessage();
        if (text == null) return error.getClass().getSimpleName();
        text = text.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ");
        return text.substring(0, Math.min(512, text.length()));
    }

    /** Synthetic read-only marker view; its zero root is not a claimed state proof or retained snapshot. */
    private static final class MarkerContext implements AppQueryContext {
        private final byte[] profile;
        private boolean active = true;
        MarkerContext(byte[] profile) { this.profile = profile.clone(); }
        private synchronized void requireActive() {
            if (!active) throw new IllegalStateException("profile-check query context expired");
        }
        synchronized void expire() { active = false; }
        @Override public Optional<byte[]> get(byte[] key) {
            requireActive();
            return Arrays.equals(key, CompositeStateKeys.profileMarkerKey())
                    ? Optional.of(profile.clone()) : Optional.empty();
        }
        @Override public byte[] stateRoot() { requireActive(); return new byte[32]; }
        @Override public long committedHeight() { requireActive(); return 0; }
    }
}
