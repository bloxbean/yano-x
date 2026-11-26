package org.yanoproject.x.framework.cardano;

import org.yanoproject.x.framework.api.appchain.AppChainConfig;

import java.util.Map;

/**
 * The script hash a chain's pinned anchor identity must name (ADR-059 D8b): the framework's own anchor validator
 * artifact, parameterized for a thread policy and the chain's anchor profile, exactly as
 * {@code ScriptAnchorService.installPinnedIdentity} derives it. In the framework's package because the artifact
 * resolver is package-private.
 */
public final class AnchorPinFixtures {
    private AnchorPinFixtures() {
    }

    public static byte[] validatorScriptHash(byte[] threadPolicyId, Map<String, String> chainSettings,
                                             long protocolMagic) {
        AnchorScriptArtifacts artifacts = new AnchorScriptArtifacts(AppChainConfig.AnchorScriptConfig.defaults(),
                AnchorProfileSettings.from(chainSettings, protocolMagic));
        return AnchorScriptArtifacts.scriptHash(artifacts.validator(threadPolicyId));
    }
}
