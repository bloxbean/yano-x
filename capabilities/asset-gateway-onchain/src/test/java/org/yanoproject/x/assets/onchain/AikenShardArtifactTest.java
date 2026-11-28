package org.yanoproject.x.assets.onchain;

import org.julclang.clientlib.JulcScriptAdapter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Aiken backends' committed artifacts: the shard (ADR-059 D8c, r23) and the CIP-113 custody scripts {@code h},
 * {@code e} and the shard binding variant (ADR-061 D3). {@code aiken/plutus.json} is the blueprint
 * {@code aiken build} writes (Aiken v1.1.21; the {@code verifyAikenShard} task rebuilds it and fails on drift), and
 * {@code META-INF/plutus/AssetShardValidatorAiken.plutus.json} is its spend validator in the text-envelope form a
 * deployment parameterizes. This test needs no Aiken toolchain: it checks that the envelope is exactly the
 * blueprint's validator, that the blueprint's hash is the template's, and that the dependencies are pinned.
 */
class AikenShardArtifactTest {
    private static final Path AIKEN = Path.of("aiken");

    private static String field(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        assertThat(matcher.find()).as("field %s", name).isTrue();
        return matcher.group(1);
    }

    @Test
    void theEnvelopeIsTheBlueprintsSpendValidator() throws Exception {
        String blueprint = Files.readString(AIKEN.resolve("plutus.json"));
        int spend = blueprint.indexOf("\"title\": \"asset_shard.asset_shard.spend\"");
        assertThat(spend).as("the blueprint has the asset_shard spend validator").isPositive();
        String validator = blueprint.substring(spend);
        String compiledCode = field(validator, "compiledCode");
        String hash = field(validator, "hash");

        String envelope = Files.readString(GatewayTestBase.AIKEN_SHARD_ARTIFACT);
        assertThat(field(envelope, "type")).isEqualTo("PlutusScriptV3");
        assertThat(field(envelope, "purpose")).isEqualTo("spending");
        byte[] code = HexFormat.of().parseHex(compiledCode);
        assertThat(field(envelope, "cborHex")).as("the envelope wraps the blueprint's compiled code once more")
                .isEqualTo(HexFormat.of().formatHex(cborBytes(code)));
        assertThat(JulcScriptAdapter.scriptHash(JulcScriptAdapter.toProgram(field(envelope, "cborHex"))))
                .as("the blueprint hash is the template's script hash").isEqualTo(hash);

        // Parameters in the D4 order of the julc AssetShardValidator: (shardThreadPolicyId, vaultScriptHash).
        int policy = validator.indexOf("\"title\": \"shard_thread_policy_id\"");
        int vault = validator.indexOf("\"title\": \"vault_script_hash\"");
        assertThat(policy).isPositive();
        assertThat(vault).isGreaterThan(policy);
        assertThat(vault).isLessThan(validator.indexOf("\"compiledCode\""));
    }

    /**
     * ADR-061 D3: the Aiken custody backend's envelopes are their blueprint validators, and each Aiken script takes
     * its julc twin's parameters, in the same order and with the same Data types ({@code byte[]} as
     * {@code ByteArray}, {@code BigInteger} as {@code Int}).
     */
    @Test
    void theCustodyEnvelopesAreTheBlueprintsValidators() throws Exception {
        envelope("ProgrammableVaultValidatorAiken", "programmable_vault.programmable_vault.withdraw",
                "rewarding,certifying", "ProgrammableVaultValidator");
        envelope("ProgrammableVaultExitDelegateAiken", "programmable_vault_exit.programmable_vault_exit.withdraw",
                "rewarding,certifying", "ProgrammableVaultExitDelegate");
        envelope("ProgrammableShardValidatorAiken", "programmable_shard.programmable_shard.spend", "spending",
                "ProgrammableShardValidator");
    }

    private static void envelope(String artifact, String title, String purpose, String julc) throws Exception {
        String blueprint = Files.readString(AIKEN.resolve("plutus.json"));
        int start = blueprint.indexOf("\"title\": \"" + title + "\"");
        assertThat(start).as("the blueprint has %s", title).isPositive();
        String validator = blueprint.substring(start, blueprint.indexOf("\"hash\"", start) + 100);
        String compiledCode = field(validator, "compiledCode");
        String hash = field(validator, "hash");

        String envelope = Files.readString(Path.of("src/main/resources/META-INF/plutus/" + artifact + ".plutus.json"));
        assertThat(field(envelope, "type")).isEqualTo("PlutusScriptV3");
        assertThat(field(envelope, "purpose")).isEqualTo(purpose);
        assertThat(field(envelope, "description")).isEqualTo(julc + " (unparameterized template, Aiken)");
        assertThat(field(envelope, "cborHex")).as("the envelope wraps the blueprint's compiled code once more")
                .isEqualTo(HexFormat.of().formatHex(cborBytes(HexFormat.of().parseHex(compiledCode))));
        assertThat(JulcScriptAdapter.scriptHash(JulcScriptAdapter.toProgram(field(envelope, "cborHex"))))
                .as("the blueprint hash is the template's script hash").isEqualTo(hash);

        List<String> aiken = new ArrayList<>();
        Matcher parameter = Pattern.compile("\\{\\s*\"title\"\\s*:\\s*\"([a-z0-9_]+)\"\\s*,\\s*\"schema\"\\s*:"
                + "\\s*\\{\\s*\"\\$ref\"\\s*:\\s*\"#/definitions/([A-Za-z]+)\"").matcher(
                validator.substring(validator.indexOf("\"parameters\""), validator.indexOf("\"compiledCode\"")));
        while (parameter.find()) {
            aiken.add(parameter.group(1).replaceFirst("^_", "") + ":" + parameter.group(2));
        }
        List<String> expected = new ArrayList<>();
        Matcher param = Pattern.compile("@Param\\s+static\\s+(byte\\[]|BigInteger)\\s+(\\w+);").matcher(
                Files.readString(Path.of("src/main/java/org/yanoproject/x/assets/onchain/" + julc + ".java")));
        while (param.find()) {
            String snake = param.group(2).replaceAll("([A-Z])", "_$1").toLowerCase(java.util.Locale.ROOT);
            expected.add(snake + ":" + (param.group(1).equals("byte[]") ? "ByteArray" : "Int"));
        }
        assertThat(expected).as("the julc parameters of %s", julc).isNotEmpty();
        assertThat(aiken).as("the Aiken parameters of %s, in the julc order and types", title)
                .containsExactlyElementsOf(expected);
    }

    @Test
    void theToolchainAndTheLibraryArePinned() throws Exception {
        String blueprint = Files.readString(AIKEN.resolve("plutus.json"));
        assertThat(field(blueprint, "plutusVersion")).isEqualTo("v3");
        assertThat(blueprint).contains("\"version\": \"v1.1.21+42babe5\"");
        String toml = Files.readString(AIKEN.resolve("aiken.toml"));
        assertThat(toml).contains("compiler = \"v1.1.21\"");
        assertThat(toml).containsPattern(
                "name = \"aiken-lang/merkle-patricia-forestry\"\\s+version = \"v2\\.1\\.0\"\\s+source = \"github\"");
        assertThat(toml).containsPattern("name = \"aiken-lang/stdlib\"\\s+version = \"v2\\.2\\.0\"");
        String lock = Files.readString(AIKEN.resolve("aiken.lock"));
        assertThat(lock).containsPattern(
                "name = \"aiken-lang/merkle-patricia-forestry\"\\s+version = \"v2\\.1\\.0\"\\s+requirements = \\[]");
        assertThat(lock).containsPattern("name = \"aiken-lang/stdlib\"\\s+version = \"v2\\.2\\.0\"\\s+requirements");
    }

    private static byte[] cborBytes(byte[] value) {
        int n = value.length;
        byte[] header = n < 24 ? new byte[] {(byte) (0x40 | n)}
                : n < 0x100 ? new byte[] {0x58, (byte) n}
                : new byte[] {0x59, (byte) (n >>> 8), (byte) n};
        byte[] out = new byte[header.length + n];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(value, 0, out, header.length, n);
        return out;
    }
}
