package org.yanoproject.x.proofs.onchain;

import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.Param;
import org.julclang.stdlib.annotation.SpendingValidator;

/** Generic same-root predicate validator bound to one application anchor. */
@SpendingValidator
public final class MpfPairAnchorValidator {
    @Param static byte[] anchorThreadPolicyId;
    @Param static byte[] anchorThreadAssetName;
    @Param static byte[] anchorScriptHash;
    @Param static byte[] expectedChainGenesisId;
    @Param static byte[] expectedApplicationId;
    @Param static byte[] expectedCommitmentProfileId;
    @Param static byte[] expectedFormatFingerprint;

    private MpfPairAnchorValidator() { }

    @Entrypoint
    public static boolean validate(PlutusData datum,
                                   MpfPairOnChainVerifier.ProofPair proofs,
                                   ScriptContext context) {
        var anchor = MpfAnchorLib.acceptedAnchor(context, anchorThreadPolicyId,
                anchorThreadAssetName, anchorScriptHash, expectedChainGenesisId,
                expectedApplicationId, expectedCommitmentProfileId, expectedFormatFingerprint);
        return anchor.isPresent()
                && MpfPairOnChainVerifier.verifyAtRoot(proofs, anchor.get().stateRoot());
    }
}
