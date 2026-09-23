package org.yanoproject.x.devtools;

import org.yanoproject.appchain.config.AppChainPropertyRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yanoproject.x.composite.CompositeProfile;
import org.yanoproject.x.composite.CompositeProfileCodec;
import org.yanoproject.x.composite.contracts.CompositeCommitmentV1;
import org.yanoproject.x.dpp.profile.DppGenesis;
import org.yanoproject.x.feed.profile.FeedGenesis;
import org.yanoproject.x.trust.profile.TrustRegistryGenesis;
import org.yanoproject.x.stdlib.contracts.ApprovalsContract;
import org.yanoproject.x.stdlib.contracts.DocTrailContract;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/** Black-box matrix gate that executes the CLI from the final Yano release archive. */
class AppChainFinalDistributionAcceptanceTest {
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_ARCHIVE_ENTRIES = 50_000;
    private static final String[] MEMBER_KEYS = {
            "8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c",
            "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394",
            "ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1"
    };

    @TempDir
    Path temporary;

    @Test
    void finalZipBindingsCompileValidateAndDryRunWithoutTestRuntimeDependencies() throws Exception {
        Path release = extractRelease(Path.of(System.getProperty("yano.test.final-yano-dist-zip")),
                temporary.resolve("binding-release"));
        Path launcher = release.resolve("yano.sh");
        assertThat(release.resolve("tools/yano-appchain/bin/yano-appchain").toFile().setExecutable(true)).isTrue();
        // Use the shipped manual itself as fixture input so its copy/paste examples are also exercised.
        String manual = Files.readString(release.resolve("docs/appchain/DECLARATIVE_BINDINGS_CLI.md"));
        Path document = temporary.resolve("bindings.yaml");
        Path context = temporary.resolve("context.json");
        Path fixture = temporary.resolve("fixture.json");
        Files.writeString(document, fencedBlock(manual, "yaml", 0));
        Files.writeString(context, fencedBlock(manual, "json", 0));
        Files.writeString(fixture, fencedBlock(manual, "json", 1));
        List<String> common = List.of(document.toString(), "--plugins-directory", release.resolve("plugins").toString(),
                "--context", context.toString());
        ObjectMapper json = new ObjectMapper();
        JsonNode rehearsal = null;
        for (String command : List.of("compile", "validate", "dry-run")) {
            List<String> args = new ArrayList<>(List.of("appchain", "bindings", command));
            args.addAll(common);
            if (command.equals("dry-run")) args.addAll(List.of("--fixture", fixture.toString()));
            Result result = run(launcher, args);
            assertThat(result.exit()).as("%s stdout=%s stderr=%s", command, result.output(), result.error()).isZero();
            if (command.equals("compile")) assertThat(result.output().strip()).matches("[0-9a-f]+");
            else if (command.equals("validate")) {
                assertThat(new ObjectMapper().readTree(result.output()).path("valid").booleanValue()).isTrue();
            } else {
                rehearsal = json.readTree(result.output());
                Files.writeString(temporary.resolve("full-rehearsal.json"), result.output());
                String receipt = rehearsal.path("receipts")
                        .get(0).path("receiptHex").textValue();
                var decoded = org.yanoproject.x.composite.contracts.BindingReceiptV1.decode(
                        java.util.HexFormat.of().parseHex(receipt));
                assertThat(decoded.accepted()).isTrue();
                assertThat(decoded.steps()).hasSize(2);
            }
        }
        assertThat(rehearsal).isNotNull();
        Map<String, String> postState = new LinkedHashMap<>();
        for (JsonNode entry : rehearsal.path("postState")) {
            assertThat(postState.put(entry.path("keyHex").textValue(), entry.path("valueHex").textValue())).isNull();
        }
        String sourceId = rehearsal.path("receipts").get(0).path("messageIdHex").textValue();
        JsonNode discovered = json.readTree(successfulBindings(launcher, List.of("receipt-key", sourceId)).output());
        assertThat(postState).containsEntry(discovered.path("stateKeyHex").textValue(),
                rehearsal.path("receipts").get(0).path("receiptHex").textValue());
        assertThat(discovered.path("receiptQueryPath").textValue())
                .isEqualTo("composite/binding-receipt-v1/" + sourceId);

        // The public marker coordinate identifies the exact authoritative profile leaf; no guessed CBOR offsets
        // or scanning for values that happen to look like profiles is involved.
        String canonical = postState.get(HexFormat.of().formatHex(CompositeCommitmentV1.profileMarkerKey()));
        assertThat(canonical).isNotBlank();
        CompositeProfile expected = CompositeProfileCodec.decode(HexFormat.of().parseHex(canonical));
        Path profiles = temporary.resolve("retained-profiles.json");
        json.writeValue(profiles.toFile(), List.of(canonical));
        List<String> preflight = List.of("profile-check", "--profiles", profiles.toString(), "--context",
                context.toString(), "--plugins-directory", release.resolve("plugins").toString());
        JsonNode reproduced = json.readTree(successfulBindings(launcher, preflight).output());
        assertThat(reproduced.path("reproducesProfiles").booleanValue()).isTrue();
        assertThat(reproduced.at("/profiles/0/expectedDigest").textValue())
                .isEqualTo(HexFormat.of().formatHex(expected.digest()));
        var incompatible = new CompositeProfile(2, expected.profileId(), "1.0.0", expected.components(),
                expected.workflows(), expected.queryAliases(), expected.aggregateQueryLimits(), expected.bindingIr());
        json.writeValue(profiles.toFile(), List.of(HexFormat.of().formatHex(incompatible.canonicalBytes())));
        var mismatchArgs = new ArrayList<>(List.of("appchain", "bindings"));
        mismatchArgs.addAll(preflight);
        Result mismatch = run(launcher, mismatchArgs);
        assertThat(mismatch.exit()).as(mismatch.error()).isEqualTo(AppChainDevtoolsCli.EXIT_INVALID_CONFIG);
        JsonNode rejected = json.readTree(mismatch.output());
        assertThat(rejected.path("reproducesProfiles").booleanValue()).isFalse();
        assertThat(rejected.at("/profiles/0/expectedDigest").textValue())
                .isEqualTo(HexFormat.of().formatHex(incompatible.digest()));

        var compactArgs = new ArrayList<>(List.of("dry-run"));
        compactArgs.addAll(common);
        compactArgs.addAll(List.of("--fixture", fixture.toString(), "--continuation-only"));
        Result compact = successfulBindings(launcher, compactArgs);
        JsonNode continuation = json.readTree(compact.output());
        assertThat(continuation.has("receipts")).isFalse();
        assertThat(continuation.path("postState")).isEqualTo(rehearsal.path("postState"));
        Path prior = temporary.resolve("continuation.json");
        Files.writeString(prior, compact.output());
        ObjectNode next = (ObjectNode) json.readTree(fixture.toFile());
        next.put("height", next.path("height").longValue() + 1);
        next.put("timestamp", next.path("timestamp").longValue() + 1);
        json.writeValue(fixture.toFile(), next);
        var replayArgs = new ArrayList<>(List.of("dry-run"));
        replayArgs.addAll(common);
        replayArgs.addAll(List.of("--fixture", fixture.toString(), "--prior-result", prior.toString()));
        JsonNode replay = json.readTree(successfulBindings(launcher, replayArgs).output());
        assertThat(replay.path("receipts")).isEqualTo(rehearsal.path("receipts"));
        assertThat(replay.path("postState")).isEqualTo(rehearsal.path("postState"));
        assertThat(replay.path("assurance").textValue()).contains("not authenticated", "no post-state root");

        // Only public descriptor/proof bytes leave this test fixture. Each real command runs with the packaged
        // launcher classpath, so source/test dependencies cannot conceal missing product profile libraries.
        for (String recipe : List.of("dpp", "feed")) {
            String chain = "packaged-customer-" + recipe;
            var descriptor = recipe.equals("dpp") ? DppGenesis.demo(chain) : FeedGenesis.demo(chain);
            Path actors = temporary.resolve(recipe + "-actors.json");
            Path members = temporary.resolve(recipe + "-members.json");
            Files.writeString(actors, TrustRegistryGenesis.toJson(descriptor));
            json.writeValue(members.toFile(), List.of(MEMBER_KEYS));
            JsonNode generated = json.readTree(successfulBindings(launcher, List.of("recipe", recipe,
                    "--descriptor", actors.toString(), "--members", members.toString(), "--threshold", "2"))
                    .output());
            assertThat(generated.at("/context/chainId").textValue()).isEqualTo(chain);
            Path recipeDocument = temporary.resolve(recipe + "-bindings.yaml");
            Path recipeContext = temporary.resolve(recipe + "-context.json");
            json.writeValue(recipeDocument.toFile(), generated.path("document"));
            json.writeValue(recipeContext.toFile(), generated.path("context"));
            JsonNode valid = json.readTree(successfulBindings(launcher, List.of("validate", recipeDocument.toString(),
                    "--context", recipeContext.toString(), "--plugins-directory",
                    release.resolve("plugins").toString()))
                    .output());
            assertThat(valid.path("valid").booleanValue()).isTrue();
        }
    }

    /** Executes only the extracted packaged launcher and retains complete failures for dependency diagnostics. */
    private Result successfulBindings(Path launcher, List<String> arguments) throws Exception {
        var command = new ArrayList<>(List.of("appchain", "bindings"));
        command.addAll(arguments);
        Result result = run(launcher, command);
        assertThat(result.exit()).as("%s stdout=%s stderr=%s", arguments.getFirst(), result.output(), result.error())
                .isZero();
        return result;
    }

    /** Rehearses the shipped worked example with fresh commands and processes an explicit idle block. */
    @Test
    void packagedProposalAndTwoVotesCarryStateAndRejectSkippedEmptyHeights() throws Exception {
        Path release = extractRelease(Path.of(System.getProperty("yano.test.final-yano-dist-zip")),
                temporary.resolve("continuation-release"));
        Path launcher = release.resolve("yano.sh");
        assertThat(release.resolve("tools/yano-appchain/bin/yano-appchain").toFile().setExecutable(true)).isTrue();
        String manual = Files.readString(release.resolve("docs/appchain/DECLARATIVE_BINDINGS_CLI.md"));
        var json = new ObjectMapper();
        Path document = temporary.resolve("approval.yml");
        Files.writeString(document, fencedBlock(manual, "yaml", 1));
        Path context = temporary.resolve("approval-context.json");
        ObjectNode input = (ObjectNode) json.readTree(fencedBlock(manual, "json", 0));
        ((ObjectNode) input.path("membership")).put("threshold", 2)
                .putArray("members").add("22".repeat(32)).add("33".repeat(32));
        json.writeValue(context.toFile(), input);
        ObjectNode fixture = (ObjectNode) json.readTree(fencedBlock(manual, "json", 2));
        assertThat(fixture.at("/messages/0/bodyHex").textValue())
                .isEqualTo(HexFormat.of().formatHex(ApprovalsContract.propose("a", new byte[]{1}, 2, 0)));
        Path fixturePath = temporary.resolve("block.json");
        Path prior = temporary.resolve("prior.json");
        String itemKey = HexFormat.of().formatHex(CompositeCommitmentV1.componentKey(
                "reviews", ApprovalsContract.itemKey("a")));
        String auditKey = HexFormat.of().formatHex(CompositeCommitmentV1.componentKey(
                "audit", DocTrailContract.entityKey("a")));
        var retainedReceipts = new LinkedHashMap<String, String>();
        Map<String, String> finalState = null;
        for (int height = 1; height <= 4; height++) {
            fixture.put("height", height).put("timestamp", height * 100);
            if (height == 4) fixture.putArray("messages");
            else if (height > 1) {
                ((ObjectNode) fixture.path("messages").get(0))
                        .put("messageIdHex", (height == 2 ? "44" : "55").repeat(32))
                        .put("senderHex", (height == 2 ? "22" : "33").repeat(32))
                        .put("senderSeq", height == 2 ? 2 : 1)
                        .put("bodyHex", HexFormat.of().formatHex(ApprovalsContract.approve("a")));
            }
            json.writeValue(fixturePath.toFile(), fixture);
            var arguments = new ArrayList<>(List.of("dry-run", document.toString(), "--context", context.toString(),
                    "--plugins-directory", release.resolve("plugins").toString(), "--fixture", fixturePath.toString()));
            if (height > 1) arguments.addAll(List.of("--prior-result", prior.toString()));
            if (height == 4) {
                fixture.put("height", 5);
                json.writeValue(fixturePath.toFile(), fixture);
                var skipped = new ArrayList<>(List.of("appchain", "bindings"));
                skipped.addAll(arguments);
                Result rejection = run(launcher, skipped);
                assertThat(rejection.exit()).isEqualTo(AppChainDevtoolsCli.EXIT_INVALID_CONFIG);
                assertThat(rejection.error()).contains("consecutive");
                fixture.put("height", 4);
                json.writeValue(fixturePath.toFile(), fixture);
            }
            Result execution = successfulBindings(launcher, arguments);
            JsonNode result = json.readTree(execution.output());
            Map<String, String> state = new LinkedHashMap<>();
            result.path("postState").forEach(entry -> state.put(entry.path("keyHex").textValue(),
                    entry.path("valueHex").textValue()));
            var item = ApprovalsContract.decodeItem(HexFormat.of().parseHex(state.get(itemKey)));
            assertThat(item.status()).isEqualTo(height < 3 ? 0 : 1);
            assertThat(item.approvers()).hasSize(Math.min(height - 1, 2));
            if (height < 3) assertThat(state).doesNotContainKey(auditKey);
            else assertThat(DocTrailContract.decodeHead(HexFormat.of().parseHex(state.get(auditKey))).count())
                    .isEqualTo(1);
            if (height == 4) {
                assertThat(result.path("receipts").size()).isZero();
                assertThat(result.path("effects").size()).isZero();
                assertThat(state).containsAllEntriesOf(finalState);
            } else {
                JsonNode receipt = result.path("receipts").get(0);
                var decoded = BindingReceiptV1.decode(HexFormat.of().parseHex(receipt.path("receiptHex").textValue()));
                assertThat(decoded.accepted()).isTrue();
                assertThat(decoded.steps()).hasSize(height == 3 ? 2 : 1);
                JsonNode key = json.readTree(successfulBindings(launcher,
                        List.of("receipt-key", receipt.path("messageIdHex").textValue())).output());
                retainedReceipts.put(key.path("stateKeyHex").textValue(), receipt.path("receiptHex").textValue());
            }
            assertThat(state).containsAllEntriesOf(retainedReceipts);
            finalState = state;
            Files.writeString(prior, execution.output());
        }
    }

    /** Extract a documented fenced example without depending on any production runtime fixture helpers. */
    private static String fencedBlock(String markdown, String language, int index) {
        var blocks = Pattern.compile("```" + language + "\\R(.*?)\\R```",
                Pattern.DOTALL).matcher(markdown);
        for (int found = 0; blocks.find(); found++) if (found == index) return blocks.group(1);
        throw new AssertionError("missing documented " + language + " fixture " + index);
    }

    @Test
    void finalDistributionGeneratesAndValidatesEveryAdvertisedCombination() throws Exception {
        Path archive = Path.of(System.getProperty("yano.test.final-yano-dist-zip"))
                .toAbsolutePath().normalize();
        Path release = extractRelease(archive, temporary.resolve("release"));
        Path launcher = release.resolve("yano.sh");
        assertThat(launcher).isRegularFile();
        assertThat(launcher.toFile().setExecutable(true)).isTrue();
        assertThat(release.resolve("tools/yano-appchain/bin/yano-appchain")
                .toFile().setExecutable(true)).isTrue();
        assertThat(release.resolve("studio/index.html")).isRegularFile();
        assertThat(release.resolve("studio/assets/appchain-release-capability-index.json"))
                .isRegularFile();
        assertThat(release.resolve("studio/assets/appchain-release-acceptance-index.json"))
                .isRegularFile();
        assertThat(release.resolve("skills/configure-yano-appchain/SKILL.md"))
                .isRegularFile();
        assertThat(release.resolve("skills/configure-yano-appchain/agents/openai.yaml"))
                .isRegularFile();
        assertThat(release.resolve("config/schema/appchain-metadata-trust.schema.json"))
                .isRegularFile();
        assertThat(release.resolve("config/schema/appchain-gitops-lock.schema.json"))
                .isRegularFile();
        assertThat(release.resolve("config/schema/appchain-component-catalog.schema.json"))
                .isRegularFile();
        assertThat(release.resolve(
                "config/schema/appchain-component-catalog-snapshot.schema.json"))
                .isRegularFile();
        assertThat(release.resolve("config/schema/appchain-release-acceptance-index.json"))
                .isRegularFile();
        assertStudioBlueprintRoundTrips(release, launcher);

        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectLifecycle lifecycle = new AppChainProjectLifecycle(properties);
        int accepted = 0;
        for (AppChainProjectModel.Recipe recipe : catalog.recipes()) {
            for (String runtime : recipe.runtimeTypes()) {
                for (String deployment : recipe.deploymentTargets()) {
                    Path project = temporary.resolve("matrix").resolve(
                            recipe.id() + "-" + runtime + "-" + deployment);
                    List<String> init = new ArrayList<>(List.of(
                            "appchain", "init", "--non-interactive",
                            "--recipe", recipe.id(), "--network", "preprod",
                            "--members", "3", "--runtime", runtime,
                            "--deployment", deployment,
                            "--output", project.toString(), "--format", "json"));
                    for (String memberKey : MEMBER_KEYS) {
                        init.add("--member-key");
                        init.add(memberKey);
                    }
                    if ("declarative-composite".equals(recipe.id())) {
                        Path bindings = temporary.resolve("matrix-bindings.yaml");
                        Files.writeString(bindings, fencedBlock(Files.readString(
                                release.resolve("docs/appchain/DECLARATIVE_BINDINGS_CLI.md")), "yaml", 0));
                        init.addAll(List.of("--bindings", bindings.toString(), "--plugins-directory",
                                release.resolve("plugins").toString()));
                    } else if ("custom-plugin".equals(recipe.id())) {
                        init.add("--answer");
                        init.add("stateMachine=com.example.acceptance");
                    } else if ("eutxo-zeroj-preview".equals(recipe.id())) {
                        init.add("--acknowledge");
                        init.add("EUTXO_ZEROJ_UNSAFE_DEVELOPMENT_TESTNET");
                        addBridgeAnswers(init);
                        addL2Answers(init);
                    } else if ("eutxo-ledger".equals(recipe.id())
                            || "eutxo-zeroj-validity".equals(recipe.id())) {
                        init.add("--answer");
                        init.add("eutxoGenesisAddress=addr_test1vr8nlm7example");
                        init.add("--answer");
                        init.add("eutxoGenesisLovelace=100000000");
                        if ("eutxo-zeroj-validity".equals(recipe.id())) {
                            addL2Answers(init);
                        }
                    } else if ("eutxo-cardano-bridge".equals(recipe.id())) {
                        init.add("--answer");
                        init.add("bridgeVaultAddress=addr_test1wzvault");
                        init.add("--answer");
                        init.add("bridgeVaultScriptHash=" + "1".repeat(56));
                        init.add("--answer");
                        init.add("bridgeMaxDepositLovelace=100000000");
                        init.add("--answer");
                        init.add("bridgeWithdrawalAddress=addr_test1vwithdrawals");
                        init.add("--answer");
                        init.add("bridgeEpoch=1");
                        init.add("--answer");
                        init.add("bridgeMaxWithdrawalLovelace=50000000");
                        init.add("--answer");
                        init.add("bridgeMaxPendingWithdrawals=100");
                    }

                    Result initialized = run(launcher, init);
                    assertThat(initialized.exit()).as(initialized.error()).isZero();
                    assertThat(initialized.output()).contains("PROJECT_INITIALIZED")
                            .doesNotContain(temporary.toString());
                    Result validated = run(launcher, List.of(
                            "appchain", "config", "validate", "--mode", "project",
                            project.toString(), "--format", "json"));
                    assertThat(validated.exit()).as(validated.error()).isZero();
                    assertThat(validated.output()).contains("VALID_PROJECT");

                    byte[] firstLock = Files.readAllBytes(project.resolve("appchain.lock"));
                    Result rendered = run(launcher, List.of(
                            "appchain", "render", project.toString(), "--format", "json"));
                    assertThat(rendered.exit()).as(rendered.error()).isZero();
                    assertThat(Files.readAllBytes(project.resolve("appchain.lock")))
                            .isEqualTo(firstLock);

                    for (String target : List.of("helm", "kustomize")) {
                        Path output = temporary.resolve("gitops").resolve(
                                recipe.id() + "-" + runtime + "-" + deployment + "-" + target);
                        Result exported = run(launcher, List.of(
                                "appchain", "gitops", project.toString(), "--target", target,
                                "--output", output.toString(), "--format", "json"));
                        assertThat(exported.exit()).as(exported.error()).isZero();
                        assertThat(exported.output()).contains("GITOPS_EXPORTED");
                        assertThat(output.resolve("gitops.lock")).isRegularFile();
                    }

                    if ("host".equals(deployment)) {
                        assertThat(project.resolve("scripts/start-node")).isRegularFile();
                        assertThat(project.resolve("compose.yaml")).doesNotExist();
                    } else {
                        assertThat(project.resolve("compose.yaml")).isRegularFile();
                        assertThat(project.resolve("scripts/start-node")).doesNotExist();
                    }
                    if ("jvm".equals(runtime)) {
                        Result ci = run(project.resolve("ci/verify"), List.of(),
                                Map.of("YANO_HOME", release.toString()));
                        assertThat(ci.exit()).as("stdout=%s%n stderr=%s",
                                ci.output(), ci.error()).isZero();
                        assertThat(ci.output()).contains("VALID_PROJECT");
                    }

                    AppChainProjectModel.ProjectValidation projectValidation =
                            lifecycle.validate(project);
                    assertThat(projectValidation.lock().runtime()).isEqualTo(runtime);
                    assertThat(projectValidation.lock().deployment()).isEqualTo(deployment);
                    assertThat(projectValidation.lock().acknowledgements())
                            .doesNotContain("PUBLIC_MEMBER_IDENTITIES_REQUIRED_BEFORE_START");
                    assertTrackedOutputIsPortableAndSecretFree(project);
                    accepted++;
                }
            }
        }
        int advertised = catalog.recipes().stream()
                .mapToInt(recipe -> recipe.runtimeTypes().size()
                        * recipe.deploymentTargets().size())
                .sum();
        assertThat(accepted).isEqualTo(advertised);
        assertThat(catalog.releaseAcceptanceIndex().recipes()).hasSameSizeAs(catalog.recipes());
    }

    @Test
    void finalDistributionRunsEutxoValidityLifecyclePolicy()
            throws Exception {
        Path archive = Path.of(System.getProperty(
                        "yano.test.final-yano-dist-zip"))
                .toAbsolutePath().normalize();
        Path release = extractRelease(
                archive, temporary.resolve("validity-release"));
        Path launcher = release.resolve("yano.sh");
        assertThat(launcher.toFile().setExecutable(true)).isTrue();
        assertThat(release.resolve(
                        "tools/yano-appchain/bin/yano-appchain")
                .toFile().setExecutable(true)).isTrue();
        assertThat(release.resolve(
                "config/schema/eutxo-zk-network-acceptance.schema.json"))
                .isRegularFile();
        assertThat(release.resolve(
                "config/schema/"
                        + "eutxo-zk-preview-release-contract.schema.json"))
                .isRegularFile();
        Path evidence = release.resolve(
                "evidence/eutxo-zk/network-acceptance-v1.json");
        assertThat(evidence).isRegularFile();
        assertThat(Files.readString(evidence))
                .contains("\"authorizationProfile\""
                                + ": \"zeroj-jubjub-dev-v1\"",
                        "\"liveDepositToWithdrawal\"",
                        "EutxoZkRollupDevnetE2ETest"
                                + "#depositFinalizeProveSettleAndWithdrawOnDevnet",
                        "\"status\": \"NOT_EXERCISED\"");
        Path releaseContract = release.resolve(
                "evidence/eutxo-zk/preview-release-contract-v1.json");
        assertThat(releaseContract).isRegularFile();
        assertThat(Files.readString(releaseContract))
                .contains("\"releaseDecision\""
                                + ": \"EXPERIMENTAL_TESTNET_ONLY\"",
                        "\"mainnet\": \"REJECTED\"",
                        "\"trustedProverRequired\": true");

        Path sessionKey = temporary.resolve("l2-session-key.enc");
        Result keyGenerated = run(launcher, List.of(
                        "appchain", "validity", "key", "generate",
                        "--output", sessionKey.toString(),
                        "--password-env", "YANO_TEST_L2_PASSWORD"),
                Map.of("YANO_TEST_L2_PASSWORD",
                        "acceptance-password"));
        assertThat(keyGenerated.exit())
                .as(keyGenerated.error()).isZero();
        assertThat(keyGenerated.output())
                .contains("L2_SESSION_KEY_CREATED")
                .doesNotContain("acceptance-password");
        assertThat(new ObjectMapper().readTree(
                keyGenerated.output()).path("publicKey").asText())
                .matches("[0-9a-f]{64}");
        assertThat(sessionKey).isRegularFile();

        Path devnet = temporary.resolve("payments-zk-devnet");
        Result initialized = run(launcher,
                previewInit(devnet, "devnet", false));
        assertThat(initialized.exit()).as(initialized.error()).isZero();
        Result validated = run(launcher, List.of(
                "appchain", "config", "validate",
                "--mode", "project", devnet.toString(),
                "--format", "json"));
        assertThat(validated.exit()).as(validated.error()).isZero();
        Result bootstrapped = run(launcher, List.of(
                "appchain", "validity", "bootstrap",
                "--project", devnet.toString()));
        assertThat(bootstrapped.exit()).as(bootstrapped.error()).isZero();
        assertThat(bootstrapped.output())
                .contains("CONTRACTS_PLANNED_CEREMONY_REQUIRED",
                        "zeroj-jubjub-dev-v1",
                        "disposable-test-funds-only");
        assertThat(Files.readString(devnet.resolve(
                "runtime/validity/contract-plan.json")))
                .contains("PLANNED_NOT_SUBMITTED");
        Result status = run(launcher, List.of(
                "appchain", "validity", "status",
                "--project", devnet.toString()));
        assertThat(status.exit()).as(status.error()).isZero();
        assertThat(status.output())
                .contains("CONTRACTS_PLANNED_CEREMONY_REQUIRED",
                        "cardano-payment-b16");

        for (String network : List.of("preview", "preprod")) {
            Path missingAcknowledgement = temporary.resolve(
                    "payments-zk-" + network + "-rejected");
            Result rejected = run(launcher, previewInit(
                    missingAcknowledgement, network, false));
            assertThat(rejected.exit()).isNotZero();
            assertThat(rejected.error())
                    .contains("EUTXO_ZEROJ_UNSAFE_DEVELOPMENT_TESTNET");

            Path acknowledged = temporary.resolve(
                    "payments-zk-" + network);
            Result accepted = run(launcher, previewInit(
                    acknowledged, network, true));
            assertThat(accepted.exit()).as(accepted.error()).isZero();
            assertThat(Files.readString(
                    acknowledged.resolve("appchain.lock")))
                    .contains("EUTXO_ZEROJ_UNSAFE_DEVELOPMENT_TESTNET");
            Result publicBootstrap = run(launcher, List.of(
                    "appchain", "validity", "bootstrap",
                    "--project", acknowledged.toString()));
            assertThat(publicBootstrap.exit())
                    .as(publicBootstrap.error()).isZero();
        }

        Result mainnet = run(launcher, previewInit(
                temporary.resolve("payments-zk-mainnet"),
                "mainnet", true));
        assertThat(mainnet.exit()).isNotZero();
        assertThat(mainnet.error().toLowerCase())
                .contains("mainnet");
    }

    private static List<String> previewInit(
            Path output,
            String network,
            boolean acknowledge
    ) {
        List<String> command = new ArrayList<>(List.of(
                "appchain", "init", "--non-interactive",
                "--recipe", "eutxo-zeroj-preview",
                "--network", network,
                "--members", "3",
                "--runtime", "jvm",
                "--deployment", "host",
                "--name", output.getFileName().toString(),
                "--chain-id", output.getFileName().toString(),
                "--output", output.toString(),
                "--format", "json"));
        for (String memberKey : MEMBER_KEYS) {
            command.add("--member-key");
            command.add(memberKey);
        }
        addBridgeAnswers(command);
        addL2Answers(command);
        if (acknowledge) {
            command.add("--acknowledge");
            command.add(
                    "EUTXO_ZEROJ_UNSAFE_DEVELOPMENT_TESTNET");
        }
        return command;
    }

    private static void addBridgeAnswers(List<String> command) {
        command.addAll(List.of(
                "--answer", "bridgeVaultAddress=addr_test1wzvault",
                "--answer", "bridgeVaultScriptHash=" + "1".repeat(56),
                "--answer", "bridgeMaxDepositLovelace=100000000",
                "--answer", "bridgeWithdrawalAddress=addr_test1vwithdrawals",
                "--answer", "bridgeEpoch=1",
                "--answer", "bridgeMaxWithdrawalLovelace=50000000",
                "--answer", "bridgeMaxPendingWithdrawals=100"));
    }

    private static void addL2Answers(List<String> command) {
        command.addAll(List.of(
                "--answer",
                "eutxoL2Address=addr_test1vr8nlm7example",
                "--answer", "eutxoL2PublicKey=" + "2".repeat(64)));
    }

    private void assertStudioBlueprintRoundTrips(Path release, Path launcher) throws Exception {
        Path project = Files.createDirectory(temporary.resolve("studio-round-trip"));
        String script = """
                import fs from 'node:fs';
                const core = await import(process.argv[1]);
                const release = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
                const intent = {
                  recipe:'audit-log', network:'devnet', members:3,
                  finality:'two-thirds', sequencing:'fixed', runtime:'jvm',
                  deployment:'host', name:'studio-round-trip', chainId:'studio-round-trip'
                };
                process.stdout.write(core.blueprintYaml(intent, release.yanoVersion));
                """;
        Process node = new ProcessBuilder("node", "--input-type=module", "-e", script,
                release.resolve("studio/studio-core.mjs").toUri().toString(),
                release.resolve("studio/assets/appchain-release-capability-index.json").toString())
                .start();
        assertThat(node.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        String yaml = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String error = new String(node.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(node.exitValue()).as(error).isZero();
        Files.writeString(project.resolve("appchain.yaml"), yaml, StandardCharsets.UTF_8);

        Result rendered = run(launcher, List.of("appchain", "render",
                project.toString(), "--format", "json"));

        assertThat(rendered.exit()).as(rendered.error()).isZero();
        assertThat(rendered.output()).contains("PROJECT_RENDERED");
        assertThat(project.resolve("appchain.lock")).isRegularFile();
    }

    private Path extractRelease(Path archive, Path output) throws IOException {
        Files.createDirectories(output);
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            if (zip.size() > MAX_ARCHIVE_ENTRIES) fail("Yano distribution has too many entries");
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path target = output.resolve(entry.getName()).normalize();
                if (!target.startsWith(output)) fail("Unsafe distribution entry");
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (var input = zip.getInputStream(entry)) {
                        Files.copy(input, target);
                    }
                }
            }
        }
        try (var children = Files.list(output)) {
            List<Path> roots = children.filter(Files::isDirectory).toList();
            assertThat(roots).hasSize(1);
            return roots.getFirst();
        }
    }

    private static void assertTrackedOutputIsPortableAndSecretFree(Path project)
            throws IOException {
        StringBuilder tracked = new StringBuilder();
        try (var paths = Files.walk(project)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                tracked.append(Files.readString(path, StandardCharsets.UTF_8));
            }
        }
        assertThat(tracked.toString())
                .doesNotContain("0101010101010101010101010101010101010101010101010101010101010101")
                .doesNotContain("0202020202020202020202020202020202020202020202020202020202020202")
                .doesNotContain(temporaryPathMarker(project));
    }

    private static String temporaryPathMarker(Path project) {
        Path matrix = project.getParent();
        return matrix == null || matrix.getParent() == null
                ? "path-that-must-not-appear" : matrix.getParent().toString();
    }

    private Result run(Path launcher, List<String> arguments) throws Exception {
        return run(launcher, arguments, Map.of());
    }

    private Result run(
            Path launcher,
            List<String> arguments,
            Map<String, String> environment) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("bash");
        command.add(launcher.toString());
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(temporary.toFile());
        builder.environment().putAll(environment);
        // Genesis recipes can exceed an OS pipe buffer; waiting before draining pipes would deadlock.
        Path stdout = Files.createTempFile(temporary, "packaged-cli-", ".out");
        Path stderr = Files.createTempFile(temporary, "packaged-cli-", ".err");
        builder.redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        Process process = builder.start();
        if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            fail("final-distribution CLI exceeded " + PROCESS_TIMEOUT);
        }
        return new Result(process.exitValue(),
                Files.readString(stdout, StandardCharsets.UTF_8), Files.readString(stderr, StandardCharsets.UTF_8));
    }

    private record Result(int exit, String output, String error) {
    }
}
