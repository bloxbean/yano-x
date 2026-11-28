package org.yanoproject.x.assets.onchain;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pinned CIP-113 v0.0.1 blueprint ({@code cip113/plutus.json}, cip113-programmable-tokens {@code 6b75ba32}) and
 * a fixture deployment applied from it, as ADR-061 §10 P2 requires: the real PLB, dispatcher and transfer delegate,
 * applied with a devnet deployment's parameters.
 *
 * <p>The fixture deployment follows the deployer's derivation: the protocol-params policy from a seed outpoint; the
 * issuance template's policy from another seed and the always-fail hash; the registry from a third seed and the
 * template's policy; PLB from the params policy; the delegates from PLB, the registry node policy and the inline
 * datum bound (1,024); the dispatcher from the transfer and third-party hashes and 28 zero bytes for unfracking
 * (unfracking disabled, as on every public deployment).
 */
final class Cip113Blueprint {
    static final String SHA256 = "b6c8cb096a15e02f1b9c719fb8c617b624c1aa7719f2258d45faa3e8f144e7b9";
    static final int MAX_INLINE_DATUM_BYTES = 1024;

    private static final String TEXT = load();

    /** The fixture deployment. */
    static final Deployment DEVNET = Deployment.derive(seed(0xd1), seed(0xd2), seed(0xd3));

    record Deployment(Program plb, Program dispatcher, Program transfer, Program thirdParty, byte[] paramsPolicy,
                      byte[] plbHash, byte[] dispatcherHash, byte[] transferHash, byte[] thirdPartyHash,
                      byte[] issuanceLogicHash, byte[] upgradeHash, byte[] registryNodePolicy) {

        static Deployment derive(PlutusData paramsSeed, PlutusData templateSeed, PlutusData registrySeed) {
            Program params = applied("protocol_params.protocol_params.mint", paramsSeed);
            byte[] paramsPolicy = hash(params);
            byte[] alwaysFail = hash(applied("always_fail.always_fail.spend", PlutusData.bytes(new byte[0])));
            byte[] templatePolicy = hash(applied("issuance_cbor_hex_mint.issuance_cbor_hex_mint.mint",
                    templateSeed, PlutusData.bytes(alwaysFail)));
            byte[] registry = hash(applied("registry.registry.mint", registrySeed, PlutusData.bytes(templatePolicy)));
            Program plb = applied("programmable_logic_base.programmable_logic_base.spend",
                    PlutusData.bytes(paramsPolicy));
            byte[] plbHash = hash(plb);
            PlutusData plbCredential = scriptCredential(plbHash);
            PlutusData bound = PlutusData.integer(MAX_INLINE_DATUM_BYTES);
            Program transfer = applied("transfer.transfer.withdraw", plbCredential, PlutusData.bytes(registry), bound);
            Program thirdParty = applied("third_party.third_party.withdraw", plbCredential,
                    PlutusData.bytes(registry), bound);
            byte[] transferHash = hash(transfer);
            byte[] thirdPartyHash = hash(thirdParty);
            Program dispatcher = applied("programmable_logic_global.programmable_logic_global.withdraw",
                    PlutusData.bytes(transferHash), PlutusData.bytes(thirdPartyHash), PlutusData.bytes(new byte[28]));
            byte[] issuanceLogic = hash(applied("issuance_logic.issuance_logic.withdraw", plbCredential,
                    PlutusData.bytes(registry), PlutusData.bytes(paramsPolicy), bound));
            return new Deployment(plb, dispatcher, transfer, thirdParty, paramsPolicy, plbHash, hash(dispatcher),
                    transferHash, thirdPartyHash, issuanceLogic, GatewayFixtures.filled(0xc9, 28), registry);
        }

        /** {@code ProtocolParams}: dispatcher, issuance logic, transfer, third party, upgrade authority, no nominee. */
        PlutusData paramsDatum() {
            return paramsDatum(dispatcherHash);
        }

        PlutusData paramsDatum(byte[] dispatcher) {
            return PlutusData.constr(0, scriptCredential(dispatcher), scriptCredential(issuanceLogicHash),
                    scriptCredential(transferHash), scriptCredential(thirdPartyHash), scriptCredential(upgradeHash),
                    PlutusData.constr(1));
        }
    }

    private Cip113Blueprint() {
    }

    /** The validator {@code title}'s compiled code with {@code params} applied. */
    static Program applied(String title, PlutusData... params) {
        Program template = template(title);
        return params.length == 0 ? template : template.applyParams(params);
    }

    static Program template(String title) {
        int at = TEXT.indexOf("\"title\": \"" + title + "\"");
        if (at < 0) {
            throw new IllegalArgumentException("no validator " + title);
        }
        Matcher code = Pattern.compile("\"compiledCode\"\\s*:\\s*\"([0-9a-f]+)\"").matcher(TEXT);
        if (!code.find(at)) {
            throw new IllegalStateException("no compiled code after " + title);
        }
        return JulcScriptAdapter.toProgram(wrapped(code.group(1)));
    }

    /** The blueprint's own {@code hash} of the unparameterized {@code title}. */
    static String blueprintHash(String title) {
        int at = TEXT.indexOf("\"title\": \"" + title + "\"");
        Matcher hash = Pattern.compile("\"hash\"\\s*:\\s*\"([0-9a-f]{56})\"").matcher(TEXT);
        if (at < 0 || !hash.find(at)) {
            throw new IllegalArgumentException("no hash for " + title);
        }
        return hash.group(1);
    }

    static byte[] hash(Program program) {
        return HexFormat.of().parseHex(JulcScriptAdapter.scriptHash(program));
    }

    static PlutusData scriptCredential(byte[] hash) {
        return PlutusData.constr(1, PlutusData.bytes(hash));
    }

    static PlutusData keyCredential(byte[] hash) {
        return PlutusData.constr(0, PlutusData.bytes(hash));
    }

    private static PlutusData seed(int fill) {
        return PlutusData.constr(0, PlutusData.bytes(GatewayFixtures.filled(fill, 32)), PlutusData.integer(0));
    }

    /** One more CBOR byte-string layer: the blueprint's code is single-wrapped, the envelope double-wrapped. */
    private static String wrapped(String singleCborHex) {
        byte[] inner = HexFormat.of().parseHex(singleCborHex);
        int n = inner.length;
        byte[] head = n < 24 ? new byte[] {(byte) (0x40 + n)}
                : n < 256 ? new byte[] {0x58, (byte) n}
                : new byte[] {0x59, (byte) (n >> 8), (byte) n};
        return HexFormat.of().formatHex(head) + singleCborHex;
    }

    private static String load() {
        try (InputStream in = Cip113Blueprint.class.getResourceAsStream("/cip113/plutus.json")) {
            if (in == null) {
                throw new IllegalStateException("cip113/plutus.json is missing from the test resources");
            }
            byte[] bytes = in.readAllBytes();
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!sha.equals(SHA256)) {
                throw new IllegalStateException("cip113/plutus.json is not the pinned blueprint: sha256 " + sha);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
