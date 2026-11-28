package org.yanoproject.x.assets.onchain;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.testkit.ContractTest;
import org.julclang.testkit.SourceDiscovery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checked-in deploy artifacts ({@code src/main/resources/META-INF/plutus/*.plutus.json}) are the exact
 * unparameterized templates a deployment parameterizes; deploy tooling loads them and never compiles from source.
 * This test recompiles every validator and fails on any drift until the bundle is regenerated with
 * {@code -Dyano.regenerate.plutus=true}.
 *
 * <p>Derivation order (ADR-059 D4): seeds; {@code GatewayDomain}; then the domain scripts in this order:
 * {@code ExitModeThreadPolicy(seed)}, {@code ForcedInboxValidator(domain, anchor identity, exitModeThreadPolicyId,
 * forcedKeyPrefix, maxMintWindowMs)} (policy id = script hash = inbox address), {@code ExitModeThreadValidator(
 * exitModeThreadPolicyId, anchor identity, forcedInboxPolicyId, timeouts, forcedKeyPrefix, domainStatusKey)}; then per
 * vault: {@code VaultId}, {@code AssetShardThreadPolicy(seed)}, {@code AssetVaultValidator(VaultId fields, network,
 * anchor identity, shardThreadPolicyId, exitModeThreadPolicyId, exitModeScriptHash, key prefixes, profile)}, the
 * vault's enterprise address, and last {@code AssetShardValidator(shardThreadPolicyId, vaultScriptHash)}.
 */
class GatewayArtifactBundleTest extends ContractTest {
    private static final Path RESOURCES = Path.of("src/main/resources/META-INF/plutus");
    private static final Pattern CBOR_HEX = Pattern.compile("\"cborHex\"\\s*:\\s*\"([0-9a-fA-F]+)\"");

    private record Artifact(Class<?> validator, String purpose) {
    }

    private static final List<Artifact> ARTIFACTS = List.of(
            new Artifact(ExitModeThreadPolicy.class, "minting"),
            new Artifact(ForcedInboxValidator.class, "minting,spending"),
            new Artifact(ExitModeThreadValidator.class, "spending"),
            new Artifact(AssetShardThreadPolicy.class, "minting"),
            new Artifact(AssetVaultValidator.class, "spending"),
            new Artifact(AssetShardValidator.class, "spending"));

    @BeforeAll
    static void crypto() {
        initCrypto();
    }

    @Test
    void bundledArtifactsMatchTheSourceCompile() throws Exception {
        boolean regenerate = Boolean.getBoolean("yano.regenerate.plutus");
        for (Artifact artifact : ARTIFACTS) {
            var compiled = SourceDiscovery.compile(artifact.validator(), GatewayFixtures.SOURCES);
            assertThat(compiled.hasErrors()).as("%s compiles", artifact.validator().getSimpleName()).isFalse();
            String cborHex = JulcScriptAdapter.fromProgram(compiled.program()).getCborHex();
            String name = artifact.validator().getSimpleName();
            Path file = RESOURCES.resolve(name + ".plutus.json");
            String json = """
                    {
                      "type": "PlutusScriptV3",
                      "purpose": "%s",
                      "description": "%s (unparameterized template, julc)",
                      "cborHex": "%s"
                    }
                    """.formatted(artifact.purpose(), name, cborHex);
            if (regenerate || !Files.exists(file)) {
                Files.createDirectories(RESOURCES);
                Files.writeString(file, json);
                System.out.println("[artifacts] wrote " + file);
                continue;
            }
            Matcher matcher = CBOR_HEX.matcher(Files.readString(file, StandardCharsets.UTF_8));
            assertThat(matcher.find()).as("%s carries cborHex", file).isTrue();
            assertThat(matcher.group(1))
                    .as("%s matches the source compile (regenerate with -Dyano.regenerate.plutus=true)", file)
                    .isEqualTo(cborHex);
        }
    }
}
