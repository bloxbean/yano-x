package org.yanoproject.x.history.onchain;

import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.Param;
import org.julclang.stdlib.annotation.SpendingValidator;
import org.yanoproject.x.proofs.onchain.MpfPairOnChainVerifier;
import org.yanoproject.x.proofs.onchain.MpfAnchorLib;

import java.math.BigInteger;

/** Fixes fact/completeness keys, epoch and predicate operands outside the redeemer. */
@SpendingValidator
public final class CardanoHistoryPairValidator {
    @Param static byte[] anchorThreadPolicyId;
    @Param static byte[] anchorThreadAssetName;
    @Param static byte[] anchorScriptHash;
    @Param static byte[] expectedChainGenesisId;
    @Param static byte[] expectedApplicationId;
    @Param static byte[] expectedCommitmentProfileId;
    @Param static byte[] expectedFormatFingerprint;
    @Param static byte[] expectedFactKey;
    @Param static byte[] expectedCompletenessKey;
    @Param static BigInteger expectedEpoch;
    @Param static BigInteger expectedPredicate;
    @Param static BigInteger expectedCoin;
    @Param static byte[] expectedAuxiliary;

    private CardanoHistoryPairValidator() { }

    @Entrypoint
    public static boolean validate(PlutusData datum,
                                   MpfPairOnChainVerifier.ProofPair proofs,
                                   ScriptContext context) {
        var anchor = MpfAnchorLib.acceptedAnchor(context, anchorThreadPolicyId,
                anchorThreadAssetName, anchorScriptHash, expectedChainGenesisId,
                expectedApplicationId, expectedCommitmentProfileId, expectedFormatFingerprint);
        return anchor.isPresent()
                && MpfPairOnChainVerifier.verifyBoundAtRoot(proofs,
                anchor.get().stateRoot(), expectedFactKey, expectedCompletenessKey,
                expectedEpoch, expectedPredicate, expectedCoin, expectedAuxiliary);
    }
}
