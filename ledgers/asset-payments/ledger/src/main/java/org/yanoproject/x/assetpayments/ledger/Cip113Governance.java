package org.yanoproject.x.assetpayments.ledger;

import org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords;
import org.yanoproject.x.assets.contracts.AssetAccounts;
import org.yanoproject.x.assets.contracts.Hash32;

import java.util.Optional;

/**
 * ADR-061 D14 and D6: the state transitions of the governed {@code ASSET_HOLD}, {@code ASSET_RESUME} and
 * {@code ASSET_REWIRE} commands, as deterministic functions of ledger state. This ledger has no governed command
 * path yet (059's governed asset commands are a later milestone); these are what that path applies.
 *
 * <ul>
 *   <li>{@code ASSET_HOLD(VaultId, reason)} sets a CIP-113 asset's status to {@code ISSUER_HOLD}; it never touches the
 *       domain status, so proof {@code Exit} keeps no brake (I47). Liabilities never change (I36).</li>
 *   <li>{@code ASSET_RESUME(VaultId)} returns a held asset to {@code ACTIVE}.</li>
 *   <li>{@code ASSET_REWIRE} is {@link org.yanoproject.x.assets.contracts.Cip113Wiring#rewire}, which approves only
 *       the head version.</li>
 * </ul>
 */
final class Cip113Governance {
    enum Result { APPLIED, UNKNOWN_VAULT, NOT_CIP113, NOT_HELD, ALREADY_HELD }

    private Cip113Governance() {
    }

    static Result hold(AssetAccounts.Store state, Hash32 vaultId) {
        Optional<PaymentsRecords.RegistryEntryV1> entry = entry(state, vaultId);
        if (entry.isEmpty()) return Result.UNKNOWN_VAULT;
        if (entry.get().custody().isEmpty()) return Result.NOT_CIP113;
        if (entry.get().status().held()) return Result.ALREADY_HELD;
        state.put(AssetPaymentsContract.registryKey(vaultId),
                entry.get().withStatus(PaymentsRecords.AssetStatus.ISSUER_HOLD).encode());
        return Result.APPLIED;
    }

    static Result resume(AssetAccounts.Store state, Hash32 vaultId) {
        Optional<PaymentsRecords.RegistryEntryV1> entry = entry(state, vaultId);
        if (entry.isEmpty()) return Result.UNKNOWN_VAULT;
        if (!entry.get().status().held()) return Result.NOT_HELD;
        state.put(AssetPaymentsContract.registryKey(vaultId),
                entry.get().withStatus(PaymentsRecords.AssetStatus.ACTIVE).encode());
        return Result.APPLIED;
    }

    private static Optional<PaymentsRecords.RegistryEntryV1> entry(AssetAccounts.Store state, Hash32 vaultId) {
        return state.get(AssetPaymentsContract.registryKey(vaultId)).map(PaymentsRecords.RegistryEntryV1::decode);
    }
}
