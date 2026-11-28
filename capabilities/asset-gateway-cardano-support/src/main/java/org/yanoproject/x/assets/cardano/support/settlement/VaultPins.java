package org.yanoproject.x.assets.cardano.support.settlement;

import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.Hash32;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;

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
 */
public record VaultPins(Hash32 vaultId, AssetId asset, int addressNetworkId, byte[] vaultScriptHash,
                        byte[] vaultAddress, byte[] shardPolicyId, byte[] shardScriptHash, byte[] shardAddress,
                        Anchor anchor, byte[] exitModePolicyId, byte[] exitModeScriptHash, byte[] claimKeyPrefix,
                        byte[] depositKeyPrefix, byte[] domainStatusKey, BigInteger continuationFloor,
                        int maxSettleBatch, int maxExitBatch, long maxPauseMs, ShardBackend shardBackend) {
    /** The on-chain bound of a continuation's id list, whatever the profile says (r16). */
    public static final int ON_CHAIN_MAX_BATCH = 64;

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
        vaultScriptHash = hash(vaultScriptHash, "vault script hash");
        shardPolicyId = hash(shardPolicyId, "shard policy id");
        shardScriptHash = hash(shardScriptHash, "shard script hash");
        exitModePolicyId = hash(exitModePolicyId, "exit-mode policy id");
        exitModeScriptHash = hash(exitModeScriptHash, "exit-mode script hash");
        vaultAddress = enterpriseScript(vaultAddress, vaultScriptHash, addressNetworkId, "vault address");
        shardAddress = enterpriseScript(shardAddress, shardScriptHash, addressNetworkId, "shard address");
        if (!Arrays.equals(shardBackend.derive(shardPolicyId, vaultScriptHash, addressNetworkId).scriptHash(),
                shardScriptHash)) {
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

    /** The shard script these pins name, derived from the backend's template (D4 step 7). */
    public ShardBackend.ShardScript shardScript() {
        return shardBackend.derive(shardPolicyId, vaultScriptHash, addressNetworkId);
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
