package org.yanoproject.x.assets.cardano.support.settlement;

import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.Hash32;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The pinned identity of one vault and its shard threads, as its scripts were parameterized (ADR-059 D4, D8, D8b,
 * D8c). The builder and the co-sign body check read every rule from here, never from a request.
 *
 * @param vaultAddress       the exact enterprise vault address (raw bytes): V8 exact addresses
 * @param shardScriptHash    the shard script hash: {@code shardBackend}'s shard validator applied to
 *                           {@code (shardPolicyId, vaultScriptHash)} (checked here, r23)
 * @param shardAddress       the exact enterprise shard address (raw bytes)
 * @param shardPolicyId      the vault's one-shot shard thread policy; token name = the shard index byte
 * @param claimKeyPrefix     the vault's {@code claimKeyPrefix} (V2)
 * @param depositKeyPrefix   the vault's {@code depositKeyPrefix}: a deposit record's key is it followed by the
 *                           {@code DepositId} ({@code Accept}, D8f)
 * @param domainStatusKey    the vault's pinned status key (V3)
 * @param continuationFloor  the profile's {@code continuationFloor} (V6)
 * @param maxSettleBatch     the profile's {@code maxSettleBatch} (16, BUDGETS.md)
 * @param maxExitBatch       the profile's {@code maxExitBatch} (8, BUDGETS.md)
 * @param maxPauseMs         the vault's {@code maxPauseMs} (V3 maximum claim age)
 * @param shardBackend       the deployment's nullifier-shard backend (D8c, r23); the body checks and builders only
 *                           read the pinned shard hash and address, so they serve either backend
 * @param programmable       ADR-061: present for a CIP-113 vault, whose {@code vaultScriptHash} is the custody
 *                           script {@code h}, whose {@code vaultAddress} is the 57-byte vault smart wallet
 *                           {@code base(Script(PLB), Script(h))}, and whose shard is the binding variant
 *                           ({@link ShardBackend#deriveProgrammable})
 */
public record VaultPins(Hash32 vaultId, AssetId asset, int addressNetworkId, byte[] vaultScriptHash,
                        byte[] vaultAddress, byte[] shardPolicyId, byte[] shardScriptHash, byte[] shardAddress,
                        Anchor anchor, byte[] exitModePolicyId, byte[] exitModeScriptHash, byte[] claimKeyPrefix,
                        byte[] depositKeyPrefix, byte[] domainStatusKey, BigInteger continuationFloor,
                        int maxSettleBatch, int maxExitBatch, long maxPauseMs, ShardBackend shardBackend,
                        Optional<Programmable> programmable) {
    /** The on-chain bound of a continuation's id list, whatever the profile says (r16). */
    public static final int ON_CHAIN_MAX_BATCH = 64;

    /**
     * The custody kind of a CIP-113 vault (ADR-061 D1, D3, D12): the pinned deployment, the exit delegate {@code e}
     * and the custody backend that derived {@code h} and {@code e}, and the deployment's L1 locations the builders and
     * members read: the protocol-params and registry-node addresses, the reference-script UTxOs of the CIP-113 scripts
     * a vault transaction runs, and the substandard whose adapter builds the asset's hook redeemer.
     */
    public record Programmable(ProgrammableVaultTxAssembler.Deployment deployment, byte[] exitDelegateHash,
                               ProgrammableBackend backend, byte[] paramsAddress, byte[] registryAddress,
                               List<ResolvedOutput.Outpoint> referenceScripts, String substandard) {
        public Programmable {
            Objects.requireNonNull(deployment, "deployment");
            exitDelegateHash = hash(exitDelegateHash, "exit delegate hash");
            Objects.requireNonNull(backend, "backend");
            paramsAddress = Objects.requireNonNull(paramsAddress, "params address").clone();
            registryAddress = Objects.requireNonNull(registryAddress, "registry address").clone();
            referenceScripts = List.copyOf(referenceScripts);
            Objects.requireNonNull(substandard, "substandard");
        }

        @Override
        public byte[] exitDelegateHash() {
            return exitDelegateHash.clone();
        }

        @Override
        public byte[] paramsAddress() {
            return paramsAddress.clone();
        }

        @Override
        public byte[] registryAddress() {
            return registryAddress.clone();
        }
    }

    /** A native vault's pins. */
    public VaultPins(Hash32 vaultId, AssetId asset, int addressNetworkId, byte[] vaultScriptHash,
                     byte[] vaultAddress, byte[] shardPolicyId, byte[] shardScriptHash, byte[] shardAddress,
                     Anchor anchor, byte[] exitModePolicyId, byte[] exitModeScriptHash, byte[] claimKeyPrefix,
                     byte[] depositKeyPrefix, byte[] domainStatusKey, BigInteger continuationFloor,
                     int maxSettleBatch, int maxExitBatch, long maxPauseMs, ShardBackend shardBackend) {
        this(vaultId, asset, addressNetworkId, vaultScriptHash, vaultAddress, shardPolicyId, shardScriptHash,
                shardAddress, anchor, exitModePolicyId, exitModeScriptHash, claimKeyPrefix, depositKeyPrefix,
                domainStatusKey, continuationFloor, maxSettleBatch, maxExitBatch, maxPauseMs, shardBackend,
                Optional.empty());
    }

    /** The pinned anchor identity (D8b). */
    public record Anchor(byte[] threadPolicyId, byte[] threadAssetName, byte[] scriptHash, byte[] genesisId,
                         byte[] applicationId, byte[] commitmentProfile, byte[] formatFingerprint) {
        public Anchor {
            threadPolicyId = hash(threadPolicyId, "anchor thread policy");
            threadAssetName = Objects.requireNonNull(threadAssetName, "anchor thread name").clone();
            scriptHash = hash(scriptHash, "anchor script hash");
            genesisId = Objects.requireNonNull(genesisId, "genesis id").clone();
            applicationId = Objects.requireNonNull(applicationId, "application id").clone();
            commitmentProfile = Objects.requireNonNull(commitmentProfile, "commitment profile").clone();
            formatFingerprint = Objects.requireNonNull(formatFingerprint, "format fingerprint").clone();
        }
    }

    public VaultPins {
        Objects.requireNonNull(vaultId, "vault id");
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(shardBackend, "shard backend");
        Objects.requireNonNull(programmable, "programmable");
        vaultScriptHash = hash(vaultScriptHash, "vault script hash");
        shardPolicyId = hash(shardPolicyId, "shard policy id");
        shardScriptHash = hash(shardScriptHash, "shard script hash");
        exitModePolicyId = hash(exitModePolicyId, "exit-mode policy id");
        exitModeScriptHash = hash(exitModeScriptHash, "exit-mode script hash");
        if (programmable.isPresent()) {
            if (asset.isAda()) throw new IllegalArgumentException("a CIP-113 vault holds a native asset");
            byte[] wallet = ProgrammableBackend.smartWallet(programmable.get().deployment().plbHash(), vaultScriptHash,
                    addressNetworkId);
            if (vaultAddress == null || !Arrays.equals(vaultAddress, wallet)
                    || programmable.get().deployment().networkId() != addressNetworkId) {
                throw new IllegalArgumentException("vault address must be the vault smart wallet base(Script(PLB),"
                        + " Script(script-hash))");
            }
            vaultAddress = vaultAddress.clone();
        } else {
            vaultAddress = enterpriseScript(vaultAddress, vaultScriptHash, addressNetworkId, "vault address");
        }
        shardAddress = enterpriseScript(shardAddress, shardScriptHash, addressNetworkId, "shard address");
        if (!Arrays.equals(shardScript(shardBackend, programmable.isPresent(), shardPolicyId, vaultScriptHash,
                addressNetworkId).scriptHash(), shardScriptHash)) {
            throw new IllegalArgumentException("shard script hash is not the " + shardBackend.id()
                    + " shard of this vault's shard policy and script hash");
        }
        claimKeyPrefix = Objects.requireNonNull(claimKeyPrefix, "claim key prefix").clone();
        depositKeyPrefix = Objects.requireNonNull(depositKeyPrefix, "deposit key prefix").clone();
        domainStatusKey = Objects.requireNonNull(domainStatusKey, "domain status key").clone();
        if (continuationFloor == null || continuationFloor.signum() <= 0) {
            throw new IllegalArgumentException("continuation floor is positive");
        }
        if (maxSettleBatch < 1 || maxSettleBatch > ON_CHAIN_MAX_BATCH || maxExitBatch < 1
                || maxExitBatch > ON_CHAIN_MAX_BATCH || maxPauseMs < 0) {
            throw new IllegalArgumentException("batch caps are 1-64");
        }
    }

    /** The shard script these pins name, derived from the backend's template (D4 step 7; ADR-061 D5 variant). */
    public ShardBackend.ShardScript shardScript() {
        return shardScript(shardBackend, programmable.isPresent(), shardPolicyId, vaultScriptHash, addressNetworkId);
    }

    private static ShardBackend.ShardScript shardScript(ShardBackend backend, boolean programmable, byte[] policy,
                                                        byte[] vaultHash, int network) {
        return programmable ? backend.deriveProgrammable(policy, vaultHash, network)
                : backend.derive(policy, vaultHash, network);
    }

    /** ADR-061: true for a CIP-113 vault. */
    public boolean cip113() {
        return programmable.isPresent();
    }

    public boolean adaVault() {
        return asset.isAda();
    }

    /** The shard thread token's asset id for {@code shardIndex}. */
    public AssetId shardToken(int shardIndex) {
        return AssetId.of(shardPolicyId, new byte[] {(byte) shardIndex});
    }

    /** The exit-mode thread token (empty name). */
    public AssetId exitModeToken() {
        return AssetId.of(exitModePolicyId, new byte[0]);
    }

    public AssetId anchorToken() {
        return AssetId.of(anchor.threadPolicyId(), anchor.threadAssetName());
    }

    private static byte[] hash(byte[] value, String name) {
        if (value == null || value.length != 28) throw new IllegalArgumentException(name + " must be 28 bytes");
        return value.clone();
    }

    private static byte[] enterpriseScript(byte[] address, byte[] scriptHash, int networkId, String name) {
        if (address == null || address.length != 29 || (address[0] & 0xff) != (0x70 | networkId)
                || !Arrays.equals(Arrays.copyOfRange(address, 1, 29), scriptHash)) {
            throw new IllegalArgumentException(name + " must be the enterprise script address of its hash");
        }
        return address.clone();
    }
}
