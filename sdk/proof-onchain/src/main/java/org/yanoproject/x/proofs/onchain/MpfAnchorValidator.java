package org.yanoproject.x.proofs.onchain;

import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.Param;
import org.julclang.stdlib.annotation.SpendingValidator;

/** Minimal generic MPF validator bound to one application anchor and one exact state key. */
@SpendingValidator
public final class MpfAnchorValidator {
    @Param static byte[] anchorThreadPolicyId;
    @Param static byte[] anchorThreadAssetName;
    @Param static byte[] anchorScriptHash;
    @Param static byte[] expectedChainGenesisId;
    @Param static byte[] expectedApplicationId;
    @Param static byte[] expectedCommitmentProfileId;
    @Param static byte[] expectedFormatFingerprint;
    @Param static byte[] expectedStateKey;

    private MpfAnchorValidator() { }

    @Entrypoint
    public static boolean validate(PlutusData datum, MpfOnChainVerifier.Proof proof,
                                   ScriptContext context) {
        var anchor = MpfAnchorLib.acceptedAnchor(context, anchorThreadPolicyId,
                anchorThreadAssetName, anchorScriptHash, expectedChainGenesisId,
                expectedApplicationId, expectedCommitmentProfileId, expectedFormatFingerprint);
        return anchor.isPresent()
                && Builtins.equalsByteString(proof.key(), expectedStateKey)
                && MpfOnChainVerifier.verifyInclusion(proof, anchor.get().stateRoot());
    }
}
