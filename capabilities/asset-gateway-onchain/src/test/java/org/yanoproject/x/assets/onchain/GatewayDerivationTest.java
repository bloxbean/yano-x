package org.yanoproject.x.assets.onchain;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.GatewayIds;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-059 D4: every script of a domain and a vault is derived from the committed templates in one acyclic order. Each
 * step uses only hashes computed before it: the exit-mode policy, then the forced inbox (whose policy id is its
 * script hash), then the exit-mode validator, then per vault the shard policy, the vault and last the shard.
 */
class GatewayDerivationTest extends GatewayTestBase {
    private static final Pattern CBOR_HEX = Pattern.compile("\"cborHex\"\\s*:\\s*\"([0-9a-fA-F]+)\"");

    private static Program template(String name) throws Exception {
        Matcher matcher = CBOR_HEX.matcher(Files.readString(
                Path.of("src/main/resources/META-INF/plutus/" + name + ".plutus.json")));
        assertThat(matcher.find()).isTrue();
        return JulcScriptAdapter.toProgram(matcher.group(1));
    }

    private static byte[] hash(Program program) {
        return HexFormat.of().parseHex(JulcScriptAdapter.scriptHash(program));
    }

    @Test
    void theDomainAndVaultScriptsDeriveInTheAdrOrder() throws Exception {
        PlutusData[] anchor = {PlutusData.bytes(ANCHOR_POLICY), PlutusData.bytes(ANCHOR_NAME),
                PlutusData.bytes(ANCHOR_HASH), PlutusData.bytes(GENESIS), PlutusData.bytes(APPLICATION),
                PlutusData.bytes(PROFILE), PlutusData.bytes(FINGERPRINT)};
        // Step 3.1-3.3: the domain scripts.
        byte[] exitPolicy = hash(template("ExitModeThreadPolicy").applyParams(PlutusData.bytes(filled(0x01, 32)),
                PlutusData.integer(0)));
        byte[] inbox = hash(template("ForcedInboxValidator").applyParams(PlutusData.bytes(DOMAIN.bytes()),
                anchor[0], anchor[1], anchor[2], anchor[3], anchor[4], anchor[5], anchor[6],
                PlutusData.bytes(exitPolicy), PlutusData.bytes(FORCED_PREFIX), PlutusData.integer(600_000)));
        byte[] exitValidator = hash(template("ExitModeThreadValidator").applyParams(PlutusData.bytes(exitPolicy),
                anchor[0], anchor[1], anchor[2], anchor[3], anchor[4], anchor[5], anchor[6], PlutusData.bytes(inbox),
                PlutusData.integer(HEARTBEAT), PlutusData.integer(FORCED_DEADLINE), PlutusData.integer(MAX_PAUSE),
                PlutusData.bytes(FORCED_PREFIX), PlutusData.bytes(STATUS_KEY)));
        // Steps 4-7: per vault.
        byte[] shardPolicy = hash(template("AssetShardThreadPolicy").applyParams(
                PlutusData.bytes(filled(0x02, 32)), PlutusData.integer(0)));
        PlutusData[] vaultParams = vaultParams(TOKEN);
        vaultParams[12] = PlutusData.bytes(shardPolicy);
        vaultParams[13] = PlutusData.bytes(exitPolicy);
        vaultParams[14] = PlutusData.bytes(exitValidator);
        byte[] vault = hash(template("AssetVaultValidator").applyParams(vaultParams));
        byte[] shard = hash(template("AssetShardValidator").applyParams(PlutusData.bytes(shardPolicy),
                PlutusData.bytes(vault)));

        for (byte[] h : new byte[][] {exitPolicy, inbox, exitValidator, shardPolicy, vault, shard}) {
            assertThat(h).hasSize(28);
        }
        assertThat(new String[] {hex(exitPolicy), hex(inbox), hex(exitValidator), hex(shardPolicy), hex(vault),
                hex(shard)}).doesNotHaveDuplicates();

        // A second vault of the same domain shares the domain scripts and gets its own shard policy and shard.
        byte[] adaShardPolicy = hash(template("AssetShardThreadPolicy").applyParams(
                PlutusData.bytes(filled(0x03, 32)), PlutusData.integer(0)));
        PlutusData[] adaParams = vaultParams(org.yanoproject.x.assets.contracts.AssetId.ADA);
        adaParams[12] = PlutusData.bytes(adaShardPolicy);
        adaParams[13] = PlutusData.bytes(exitPolicy);
        adaParams[14] = PlutusData.bytes(exitValidator);
        byte[] adaVault = hash(template("AssetVaultValidator").applyParams(adaParams));
        assertThat(adaVault).isNotEqualTo(vault);
        assertThat(hash(template("AssetShardValidator").applyParams(PlutusData.bytes(adaShardPolicy),
                PlutusData.bytes(adaVault)))).isNotEqualTo(shard);
        // The vault id the vault computes on chain is the contract's.
        assertThat(GatewayIds.vaultId(DOMAIN, TOKEN, 0)).isEqualTo(TOKEN_VAULT);
    }

    private static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }
}
