package org.yanoproject.x.assets.cardano.support.settlement;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-059 r23: the runtime derives the shard script of either backend from its template exactly as julc's
 * {@code applyParams} and script hash do, so a member can check its pinned shard hash and address without a UPLC
 * toolchain.
 */
class ShardBackendTest {
    private static final Path ONCHAIN = Path.of("../asset-gateway-onchain/src/main/resources/META-INF/plutus");

    @Test
    void theDerivedShardScriptIsJulcsParameterApplication() {
        Random random = new Random(59);
        for (ShardBackend backend : ShardBackend.values()) {
            Program template = JulcScriptAdapter.toProgram(HexFormat.of().formatHex(
                    FlatTemplates.cborBytes(FlatTemplates.cborBytes(backend.template()))));
            for (int i = 0; i < 8; i++) {
                byte[] policy = new byte[28];
                byte[] vault = new byte[28];
                random.nextBytes(policy);
                random.nextBytes(vault);
                Program applied = template.applyParams(PlutusData.bytes(policy), PlutusData.bytes(vault));
                ShardBackend.ShardScript script = backend.derive(policy, vault, 0);
                assertThat(HexFormat.of().formatHex(script.scriptHash())).as("%s hash", backend)
                        .isEqualTo(JulcScriptAdapter.scriptHash(applied));
                assertThat(HexFormat.of().formatHex(FlatTemplates.cborBytes(HexFormat.of().parseHex(
                        script.scriptCborHex())))).as("%s script bytes", backend)
                        .isEqualTo(JulcScriptAdapter.fromProgram(applied).getCborHex());
                assertThat(script.address()[0]).isEqualTo((byte) 0x70);
            }
        }
    }

    @Test
    void theBackendsHaveDistinctScriptsForTheSameParameters() {
        byte[] policy = filled(0x71, 28);
        byte[] vault = filled(0x54, 28);
        ShardBackend.ShardScript julc = ShardBackend.JULC.derive(policy, vault, 1);
        ShardBackend.ShardScript aiken = ShardBackend.AIKEN.derive(policy, vault, 1);
        assertThat(julc.scriptHash()).isNotEqualTo(aiken.scriptHash());
        assertThat(aiken.address()[0]).isEqualTo((byte) 0x71);
        assertThat(ShardBackend.AIKEN.derive(policy, filled(0x55, 28), 1).scriptHash())
                .isNotEqualTo(aiken.scriptHash());
    }

    @Test
    void theBundledTemplatesAreTheOnChainModulesArtifacts() throws Exception {
        for (ShardBackend backend : ShardBackend.values()) {
            String name = backend == ShardBackend.JULC ? "AssetShardValidator" : "AssetShardValidatorAiken";
            String json = Files.readString(ONCHAIN.resolve(name + ".plutus.json"));
            assertThat(json).contains(HexFormat.of().formatHex(
                    FlatTemplates.cborBytes(FlatTemplates.cborBytes(backend.template()))));
        }
    }

    @Test
    void theSettingAcceptsJulcAndAikenAndDefaultsToJulc() {
        assertThat(ShardBackend.parse(null)).isEqualTo(ShardBackend.JULC);
        assertThat(ShardBackend.parse(" ")).isEqualTo(ShardBackend.JULC);
        assertThat(ShardBackend.parse("julc")).isEqualTo(ShardBackend.JULC);
        assertThat(ShardBackend.parse(" aiken ")).isEqualTo(ShardBackend.AIKEN);
        assertThatThrownBy(() -> ShardBackend.parse("Aiken")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShardBackend.parse("plutarch")).isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] filled(int value, int length) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
