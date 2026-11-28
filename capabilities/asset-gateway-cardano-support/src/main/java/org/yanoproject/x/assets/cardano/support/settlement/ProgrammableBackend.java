package org.yanoproject.x.assets.cardano.support.settlement;

import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.Hash32;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The two interchangeable custody backends of a CIP-113 vault (ADR-061 D3): the exit delegate {@code e} and the
 * custody script {@code h}, julc ({@code ProgrammableVaultExitDelegate}, {@code ProgrammableVaultValidator}, the
 * default) or Aiken ({@code programmable_vault_exit.ak}, {@code programmable_vault.ak}). Both take the same
 * parameters, redeemer and datums and enforce the same rules; they differ only in their bytes, so the backend
 * determines {@code e}, {@code h} and the vault smart wallet. One setting, {@code programmable-backend}, selects both.
 *
 * <p>{@link #derive} applies the committed templates (copied from the on-chain module at build time) to a vault's
 * parameters in the D2 order: {@code e} first, from the vault's parameters, then {@code h}, which also takes
 * {@code e}'s hash, then the smart wallet {@code base(Script(PLB), Script(h))}. A node whose pinned hashes are not
 * these fails startup ({@link #requireDerived}).
 */
public enum ProgrammableBackend {
    JULC("julc", "ProgrammableVaultExitDelegate.plutus.json", "ProgrammableVaultValidator.plutus.json"),
    AIKEN("aiken", "ProgrammableVaultExitDelegateAiken.plutus.json", "ProgrammableVaultValidatorAiken.plutus.json");

    /** The backend when {@code programmable-backend} is not set. */
    public static final ProgrammableBackend DEFAULT = JULC;

    static final String TEMPLATE_DIRECTORY = "META-INF/yano/asset-gateway/programmable-templates/";
    private static final Pattern CBOR_HEX = Pattern.compile("\"cborHex\"\\s*:\\s*\"([0-9a-fA-F]+)\"");

    private final String id;
    private final String delegateResource;
    private final String custodyResource;
    private volatile byte[] delegateTemplate;
    private volatile byte[] custodyTemplate;

    ProgrammableBackend(String id, String delegateResource, String custodyResource) {
        this.id = id;
        this.delegateResource = TEMPLATE_DIRECTORY + delegateResource;
        this.custodyResource = TEMPLATE_DIRECTORY + custodyResource;
    }

    public String id() {
        return id;
    }

    /** Parses a {@code programmable-backend} value (case-sensitive, trimmed); blank or absent is the default. */
    public static ProgrammableBackend parse(String value) {
        if (value == null || value.isBlank()) return DEFAULT;
        String trimmed = value.trim();
        for (ProgrammableBackend backend : values()) {
            if (backend.id.equals(trimmed)) return backend;
        }
        throw new IllegalArgumentException("programmable-backend must be julc or aiken, not '" + trimmed + "'");
    }

    /**
     * The custody parameters of one CIP-113 vault, in the D2 order: the native vault's (059-D4 step 5), then the
     * pinned deployment's PLB hash and {@code maxInlineDatumBytes}.
     */
    public record Parameters(Hash32 domainId, AssetId asset, long vaultOrdinal, int addressNetworkId,
                             VaultPins.Anchor anchor, byte[] shardPolicyId, byte[] exitModePolicyId,
                             byte[] exitModeScriptHash, byte[] claimKeyPrefix, byte[] depositKeyPrefix,
                             byte[] exitKeyPrefix, byte[] domainStatusKey, int maxSettleBatch, int maxExitBatch,
                             BigInteger continuationFloor, long exitDelayFloorMs, long exitDelayCeilingMs,
                             long maxPauseMs, byte[] plbHash, int maxInlineDatumBytes) {
        public Parameters {
            Objects.requireNonNull(domainId, "domain id");
            Objects.requireNonNull(asset, "asset");
            Objects.requireNonNull(anchor, "anchor");
            if (asset.isAda()) throw new IllegalArgumentException("a CIP-113 vault holds a native asset");
            if (addressNetworkId < 0 || addressNetworkId > 15) throw new IllegalArgumentException("network id");
            if (plbHash == null || plbHash.length != 28) throw new IllegalArgumentException("PLB hash is 28 bytes");
            if (maxInlineDatumBytes < 40) throw new IllegalArgumentException("maxInlineDatumBytes too small");
            // A Plutus Data byte string is one CBOR chunk of at most 64 bytes in the applied scripts' encoding; a
            // longer parameter would apply as a different program from the one the scripts were derived for.
            for (byte[] value : new byte[][] {anchor.threadAssetName(), anchor.genesisId(), anchor.applicationId(),
                    anchor.commitmentProfile(), anchor.formatFingerprint(), shardPolicyId, exitModePolicyId,
                    exitModeScriptHash, claimKeyPrefix, depositKeyPrefix, exitKeyPrefix, domainStatusKey}) {
                if (value == null || value.length > MAX_PARAMETER_BYTES) {
                    throw new IllegalArgumentException("a custody byte parameter is at most " + MAX_PARAMETER_BYTES
                            + " bytes");
                }
            }
            shardPolicyId = shardPolicyId.clone();
            exitModePolicyId = exitModePolicyId.clone();
            exitModeScriptHash = exitModeScriptHash.clone();
            claimKeyPrefix = claimKeyPrefix.clone();
            depositKeyPrefix = depositKeyPrefix.clone();
            exitKeyPrefix = exitKeyPrefix.clone();
            domainStatusKey = domainStatusKey.clone();
            plbHash = plbHash.clone();
        }

        /** The longest byte parameter: one Plutus Data byte-string chunk. */
        public static final int MAX_PARAMETER_BYTES = 64;

        /** V16: the continuation id cap {@code floor((maxInlineDatumBytes - 6) / 34)}, at most 64. */
        public int maxContinuationIds() {
            return Math.min(64, (maxInlineDatumBytes - 6) / 34);
        }

        /** The Data CBOR of each parameter of {@code e}, in order. */
        byte[][] delegateData() {
            return new byte[][] {
                    b(domainId.bytes()), b(asset.policyId()), b(asset.assetName()), i(vaultOrdinal),
                    i(addressNetworkId), b(anchor.threadPolicyId()), b(anchor.threadAssetName()),
                    b(anchor.scriptHash()), b(anchor.genesisId()), b(anchor.applicationId()),
                    b(anchor.commitmentProfile()), b(anchor.formatFingerprint()), b(shardPolicyId),
                    b(exitModePolicyId), b(exitModeScriptHash), b(claimKeyPrefix), b(depositKeyPrefix),
                    b(exitKeyPrefix), b(domainStatusKey), i(maxSettleBatch), i(maxExitBatch),
                    i(continuationFloor.longValueExact()), i(exitDelayFloorMs), i(exitDelayCeilingMs), i(maxPauseMs),
                    b(plbHash), i(maxInlineDatumBytes)};
        }

        private static byte[] b(byte[] value) {
            return FlatTemplates.cborBytes(value);
        }

        private static byte[] i(long value) {
            if (value < 0) throw new IllegalArgumentException("custody parameters are non-negative");
            if (value < 24) return new byte[] {(byte) value};
            if (value < 256) return new byte[] {0x18, (byte) value};
            if (value < 65_536) return new byte[] {0x19, (byte) (value >> 8), (byte) value};
            if (value < 4_294_967_296L) {
                return new byte[] {0x1a, (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
            }
            byte[] out = new byte[9];
            out[0] = 0x1b;
            for (int k = 0; k < 8; k++) out[8 - k] = (byte) (value >>> (8 * k));
            return out;
        }
    }

    /** The custody of one vault: {@code e} and {@code h} (parameterized flat programs and hashes) and its address. */
    public record Custody(ProgrammableBackend backend, byte[] exitDelegateFlat, byte[] exitDelegateHash,
                          byte[] custodyFlat, byte[] custodyHash, byte[] smartWallet) {
        public Custody {
            Objects.requireNonNull(backend, "backend");
            exitDelegateFlat = exitDelegateFlat.clone();
            exitDelegateHash = exitDelegateHash.clone();
            custodyFlat = custodyFlat.clone();
            custodyHash = custodyHash.clone();
            smartWallet = smartWallet.clone();
        }

        @Override
        public byte[] exitDelegateHash() {
            return exitDelegateHash.clone();
        }

        @Override
        public byte[] custodyHash() {
            return custodyHash.clone();
        }

        @Override
        public byte[] smartWallet() {
            return smartWallet.clone();
        }

        @Override
        public byte[] exitDelegateFlat() {
            return exitDelegateFlat.clone();
        }

        @Override
        public byte[] custodyFlat() {
            return custodyFlat.clone();
        }

        /** {@code h} as the ledger stores it (CBOR byte string of the flat program), hex. */
        public String custodyCborHex() {
            return HexFormat.of().formatHex(FlatTemplates.cborBytes(custodyFlat));
        }

        public String exitDelegateCborHex() {
            return HexFormat.of().formatHex(FlatTemplates.cborBytes(exitDelegateFlat));
        }
    }

    /** {@code e}, then {@code h}, then the vault smart wallet, for {@code parameters} (D2 derivation order). */
    public Custody derive(Parameters parameters) {
        byte[][] delegateParams = parameters.delegateData();
        byte[] delegate = FlatTemplates.applyData(template(true), delegateParams);
        byte[] delegateHash = ShardBackend.scriptHash(delegate);
        byte[][] custodyParams = Arrays.copyOf(delegateParams, delegateParams.length + 1);
        custodyParams[delegateParams.length] = FlatTemplates.cborBytes(delegateHash);
        byte[] custody = FlatTemplates.applyData(template(false), custodyParams);
        byte[] custodyHash = ShardBackend.scriptHash(custody);
        return new Custody(this, delegate, delegateHash, custody, custodyHash,
                smartWallet(parameters.plbHash(), custodyHash, parameters.addressNetworkId()));
    }

    /**
     * Startup: fails unless {@code pinnedCustodyHash} and {@code pinnedExitDelegateHash} are this backend's
     * {@code h} and {@code e} for {@code parameters} (D3).
     */
    public Custody requireDerived(Parameters parameters, byte[] pinnedCustodyHash, byte[] pinnedExitDelegateHash) {
        Custody custody = derive(parameters);
        if (!Arrays.equals(custody.custodyHash(), pinnedCustodyHash)) {
            throw new IllegalArgumentException("pinned script-hash is not the " + id
                    + " custody script of this vault's parameters");
        }
        if (!Arrays.equals(custody.exitDelegateHash(), pinnedExitDelegateHash)) {
            throw new IllegalArgumentException("pinned exit-delegate-hash is not the " + id
                    + " exit delegate of this vault's parameters");
        }
        return custody;
    }

    /** The raw smart wallet {@code base(Script(plb), Script(owner))}: header {@code 0x30 | network}. */
    public static byte[] smartWallet(byte[] plbHash, byte[] ownerScriptHash, int networkId) {
        byte[] address = new byte[57];
        address[0] = (byte) (0x30 | networkId);
        System.arraycopy(plbHash, 0, address, 1, 28);
        System.arraycopy(ownerScriptHash, 0, address, 29, 28);
        return address;
    }

    /** The raw key-owned smart wallet {@code base(Script(plb), Key(owner))}: header {@code 0x10 | network}. */
    public static byte[] keySmartWallet(byte[] plbHash, byte[] ownerKeyHash, int networkId) {
        byte[] address = new byte[57];
        address[0] = (byte) (0x10 | networkId);
        System.arraycopy(plbHash, 0, address, 1, 28);
        System.arraycopy(ownerKeyHash, 0, address, 29, 28);
        return address;
    }

    private byte[] template(boolean delegate) {
        if (delegate) {
            byte[] loaded = delegateTemplate;
            if (loaded == null) delegateTemplate = loaded = load(delegateResource);
            return loaded;
        }
        byte[] loaded = custodyTemplate;
        if (loaded == null) custodyTemplate = loaded = load(custodyResource);
        return loaded;
    }

    private static byte[] load(String resource) {
        try (InputStream in = ProgrammableBackend.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("custody template missing from the classpath: " + resource);
            Matcher matcher = CBOR_HEX.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (!matcher.find()) throw new IllegalStateException("no cborHex in " + resource);
            return ShardBackend.unwrapBytes(ShardBackend.unwrapBytes(HexFormat.of().parseHex(matcher.group(1))));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
