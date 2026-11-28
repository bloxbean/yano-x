package org.yanoproject.x.assets.cardano.gateway;

import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;

import java.util.Map;

import static org.yanoproject.x.assets.cardano.gateway.SettingsValues.bounded;
import static org.yanoproject.x.assets.cardano.gateway.SettingsValues.hex;
import static org.yanoproject.x.assets.cardano.gateway.SettingsValues.optional;
import static org.yanoproject.x.assets.cardano.gateway.SettingsValues.required;

/**
 * The {@code domain.*} settings every gateway observer shares (ADR-059 D4), parsed exactly as
 * {@link AssetVaultDepositSettings} parses them, and the {@code GatewayDomain} they derive.
 */
record DomainSettings(int addressNetworkId, long networkMagic, String chainId, Hash32 genesisId, String applicationId,
                      String profileId, long profileVersion, Hash32 domainId) {

    static DomainSettings parse(Map<String, String> settings) {
        int networkId = (int) bounded(required(settings, "domain.address-network-id"), 0, 15,
                "domain.address-network-id");
        long magic = bounded(required(settings, "domain.network-magic"), 0, 0xFFFF_FFFFL, "domain.network-magic");
        String chainId = required(settings, "domain.chain-id");
        Hash32 genesis = Hash32.of(hex(required(settings, "domain.genesis-id"), 32, "domain.genesis-id"));
        String applicationId = optional(settings, "domain.application-id",
                AssetVaultDepositSettings.DEFAULT_APPLICATION_ID);
        String profileId = optional(settings, "domain.profile-id", AssetVaultDepositSettings.DEFAULT_PROFILE_ID);
        long profileVersion = bounded(optional(settings, "domain.profile-version", "1"), 0, Long.MAX_VALUE,
                "domain.profile-version");
        return new DomainSettings(networkId, magic, chainId, genesis, applicationId, profileId, profileVersion,
                GatewayIds.domainId(networkId, magic, chainId, genesis, applicationId, profileId, profileVersion));
    }

    /** The identity fields, in the order and form {@link AssetVaultDepositSettings#identityFields()} uses. */
    void identityFields(Map<String, String> fields) {
        fields.put("domain.address-network-id", String.valueOf(addressNetworkId));
        fields.put("domain.network-magic", String.valueOf(networkMagic));
        fields.put("domain.chain-id", chainId);
        fields.put("domain.genesis-id", genesisId.hex());
        fields.put("domain.application-id", applicationId);
        fields.put("domain.profile-id", profileId);
        fields.put("domain.profile-version", String.valueOf(profileVersion));
        fields.put("domain-id", domainId.hex());
    }
}
