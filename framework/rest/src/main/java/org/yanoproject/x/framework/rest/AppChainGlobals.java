package org.yanoproject.x.framework.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.config.YanoPropertyKeys;
import org.yanoproject.app.spi.RuntimeGlobalsContributor;
import org.yanoproject.x.framework.config.AppChainConfigParser;
import org.yanoproject.x.framework.config.AppChainPropertyKeys;
import org.yanoproject.x.framework.config.AppChainStoragePaths;
import org.yanoproject.x.framework.host.AppLedgerHostPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Projects the typed {@code yano.app-chain.*} configuration into the runtime globals the host passes
 * to plugins (ADR-064 D7): the effective-enable rule, the structured {@code chains} list, indexed-to-map
 * conversion, dynamic prefixes and the storage default. Quarkus typed configuration keeps its
 * environment-variable mapping for dashed keys such as {@code YANO_APP_CHAIN_*}.
 *
 * <p>Startup fails when app chains are enabled but the application-ledger root bundle is missing
 * from {@code yano.plugins.required}, for example because an operator overrode the property.</p>
 */
@ApplicationScoped
public class AppChainGlobals implements RuntimeGlobalsContributor {

    private static final Logger log = LoggerFactory.getLogger(AppChainGlobals.class);

    @Inject
    Config appConfig;

    @ConfigProperty(
            name = AppChainPropertyKeys.STORAGE_PATH,
            defaultValue = AppChainStoragePaths.DEFAULT_STORAGE_PATH)
    String appChainStoragePath;

    @ConfigProperty(name = YanoPropertyKeys.Plugins.REQUIRED)
    Optional<String> requiredPlugins = Optional.empty();

    // Optional so we can tell an EXPLICIT enabled=false from the default:
    // multi-chain config (chains[i]) enables the app chain unless the operator
    // explicitly set enabled=false.
    @ConfigProperty(name = AppChainPropertyKeys.ENABLED)
    Optional<Boolean> appChainEnabledOpt;

    @ConfigProperty(name = AppChainPropertyKeys.CHAIN_ID)
    Optional<String> appChainId;

    @ConfigProperty(name = AppChainPropertyKeys.SIGNING_KEY)
    Optional<String> appChainSigningKey;

    @ConfigProperty(name = AppChainPropertyKeys.MEMBERS)
    Optional<String> appChainMembers;

    @ConfigProperty(name = AppChainPropertyKeys.PEERS)
    Optional<String> appChainPeers;

    @ConfigProperty(name = AppChainPropertyKeys.MAX_MESSAGE_BYTES, defaultValue = "65536")
    int appChainMaxMessageBytes;

    @ConfigProperty(name = AppChainPropertyKeys.SEQUENCER_PROPOSER)
    Optional<String> appChainProposer;

    @ConfigProperty(name = AppChainPropertyKeys.THRESHOLD, defaultValue = "1")
    int appChainThreshold;

    @ConfigProperty(name = AppChainPropertyKeys.BLOCK_INTERVAL_MS, defaultValue = "2000")
    long appChainBlockIntervalMs;

    @ConfigProperty(name = AppChainPropertyKeys.BLOCK_MAX_MESSAGES, defaultValue = "5000")
    int appChainBlockMaxMessages;

    @ConfigProperty(name = AppChainPropertyKeys.BLOCK_MAX_BYTES, defaultValue = "4194304")
    long appChainBlockMaxBytes;

    @ConfigProperty(name = AppChainPropertyKeys.STATE_MACHINE, defaultValue = "ordered-log")
    String appChainStateMachine;

    @ConfigProperty(name = AppChainPropertyKeys.ANCHOR_ENABLED, defaultValue = "false")
    boolean appChainAnchorEnabled;

    @ConfigProperty(name = AppChainPropertyKeys.ANCHOR_SIGNING_KEY)
    Optional<String> appChainAnchorSigningKey;

    @ConfigProperty(name = AppChainPropertyKeys.ANCHOR_EVERY_BLOCKS, defaultValue = "10")
    long appChainAnchorEveryBlocks;

    @ConfigProperty(name = AppChainPropertyKeys.ANCHOR_MAX_INTERVAL_MINUTES, defaultValue = "60")
    long appChainAnchorMaxIntervalMinutes;

    @ConfigProperty(name = AppChainPropertyKeys.ANCHOR_METADATA_LABEL, defaultValue = "7014")
    long appChainAnchorMetadataLabel;

    @ConfigProperty(name = AppChainPropertyKeys.ANCHOR_VALIDITY_SLOTS, defaultValue = "7200")
    long appChainAnchorValiditySlots;

    @ConfigProperty(name = AppChainPropertyKeys.ANCHOR_FALLBACK_FEE_LOVELACE, defaultValue = "300000")
    long appChainAnchorFallbackFeeLovelace;

    @ConfigProperty(name = AppChainPropertyKeys.ANCHOR_MODE, defaultValue = "metadata")
    String appChainAnchorMode;

    @ConfigProperty(name = AppChainPropertyKeys.ANCHOR_SCRIPT_VALIDATOR)
    Optional<String> appChainAnchorScriptValidator;

    @ConfigProperty(name = AppChainPropertyKeys.ANCHOR_SCRIPT_THREAD_POLICY)
    Optional<String> appChainAnchorScriptThreadPolicy;

    @ConfigProperty(name = AppChainPropertyKeys.L1_STABILITY_DEPTH, defaultValue = "0")
    int appChainL1StabilityDepth;

    @ConfigProperty(name = AppChainPropertyKeys.WEBHOOKS)
    Optional<String> appChainWebhooks;

    @ConfigProperty(name = AppChainPropertyKeys.RETENTION_ENABLED, defaultValue = "false")
    boolean appChainRetentionEnabled;

    @ConfigProperty(name = AppChainPropertyKeys.RETENTION_KEEP_BLOCKS, defaultValue = "0")
    int appChainRetentionKeepBlocks;

    @ConfigProperty(name = AppChainPropertyKeys.POOL_MAX_MESSAGES, defaultValue = "10000")
    int appChainPoolMaxMessages;

    @ConfigProperty(name = AppChainPropertyKeys.MESSAGE_ENFORCE_SENDER_SEQ, defaultValue = "false")
    boolean appChainEnforceSenderSeq;

    @ConfigProperty(name = AppChainPropertyKeys.MAX_TTL_SECONDS, defaultValue = "3600")
    long appChainMaxTtlSeconds;

    @ConfigProperty(name = AppChainPropertyKeys.DEFAULT_TTL_SECONDS, defaultValue = "600")
    long appChainDefaultTtlSeconds;

    @ConfigProperty(name = AppChainPropertyKeys.VALIDATION_STRICT, defaultValue = "false")
    boolean appChainValidationStrict;

    @Override
    public Map<String, Object> globals() {
        Map<String, Object> globals = new HashMap<>();
        putAppChainGlobals(globals);
        globals.put(AppChainPropertyKeys.STORAGE_PATH, appChainStoragePath);
        if (Boolean.TRUE.equals(globals.get(AppChainPropertyKeys.ENABLED))
                && requiredPlugins.stream().flatMap(v -> Arrays.stream(v.split(",")))
                        .map(String::trim).noneMatch(AppLedgerHostPlugin.BUNDLE_ID::equals)) {
            throw new IllegalStateException(AppChainPropertyKeys.ENABLED + "=true requires bundle "
                    + AppLedgerHostPlugin.BUNDLE_ID + " in " + YanoPropertyKeys.Plugins.REQUIRED);
        }
        return globals;
    }

    private void putAppChainGlobals(Map<String, Object> globals) {
        List<Map<String, Object>> appChainList = parseAppChainChains();
        // Effective enablement: explicit flag wins; otherwise presence of a
        // multi-chain list enables. An explicit enabled=false always disables.
        boolean appChainEnabled = appChainEnabledOpt.orElse(!appChainList.isEmpty());
        globals.put(AppChainPropertyKeys.ENABLED, appChainEnabled);
        appChainId.ifPresent(v -> globals.put(AppChainPropertyKeys.CHAIN_ID, v));
        appChainSigningKey.ifPresent(v -> globals.put(AppChainPropertyKeys.SIGNING_KEY, v));
        appChainMembers.ifPresent(v -> globals.put(AppChainPropertyKeys.MEMBERS, v));
        appChainPeers.ifPresent(v -> globals.put(AppChainPropertyKeys.PEERS, v));
        globals.put(AppChainPropertyKeys.MAX_MESSAGE_BYTES, appChainMaxMessageBytes);
        globals.put(AppChainPropertyKeys.MAX_TTL_SECONDS, appChainMaxTtlSeconds);
        globals.put(AppChainPropertyKeys.DEFAULT_TTL_SECONDS, appChainDefaultTtlSeconds);
        appChainProposer.ifPresent(v -> globals.put(AppChainPropertyKeys.SEQUENCER_PROPOSER, v));
        globals.put(AppChainPropertyKeys.THRESHOLD, appChainThreshold);
        globals.put(AppChainPropertyKeys.BLOCK_INTERVAL_MS, appChainBlockIntervalMs);
        globals.put(AppChainPropertyKeys.BLOCK_MAX_MESSAGES, appChainBlockMaxMessages);
        globals.put(AppChainPropertyKeys.BLOCK_MAX_BYTES, appChainBlockMaxBytes);
        globals.put(AppChainPropertyKeys.STATE_MACHINE, appChainStateMachine);
        globals.put(AppChainPropertyKeys.ANCHOR_ENABLED, appChainAnchorEnabled);
        appChainAnchorSigningKey.ifPresent(v -> globals.put(AppChainPropertyKeys.ANCHOR_SIGNING_KEY, v));
        globals.put(AppChainPropertyKeys.ANCHOR_EVERY_BLOCKS, appChainAnchorEveryBlocks);
        globals.put(AppChainPropertyKeys.ANCHOR_MAX_INTERVAL_MINUTES, appChainAnchorMaxIntervalMinutes);
        globals.put(AppChainPropertyKeys.ANCHOR_METADATA_LABEL, appChainAnchorMetadataLabel);
        globals.put(AppChainPropertyKeys.ANCHOR_VALIDITY_SLOTS, appChainAnchorValiditySlots);
        globals.put(AppChainPropertyKeys.ANCHOR_FALLBACK_FEE_LOVELACE, appChainAnchorFallbackFeeLovelace);
        globals.put(AppChainPropertyKeys.ANCHOR_MODE, appChainAnchorMode);
        appChainAnchorScriptValidator.ifPresent(
                v -> globals.put(AppChainPropertyKeys.ANCHOR_SCRIPT_VALIDATOR, v));
        appChainAnchorScriptThreadPolicy.ifPresent(
                v -> globals.put(AppChainPropertyKeys.ANCHOR_SCRIPT_THREAD_POLICY, v));
        globals.put(AppChainPropertyKeys.L1_STABILITY_DEPTH, appChainL1StabilityDepth);
        appChainWebhooks.ifPresent(v -> globals.put(AppChainPropertyKeys.WEBHOOKS, v));
        globals.put(AppChainPropertyKeys.RETENTION_ENABLED, appChainRetentionEnabled);
        globals.put(AppChainPropertyKeys.RETENTION_KEEP_BLOCKS, appChainRetentionKeepBlocks);
        globals.put(AppChainPropertyKeys.POOL_MAX_MESSAGES, appChainPoolMaxMessages);
        globals.put(AppChainPropertyKeys.MESSAGE_ENFORCE_SENDER_SEQ, appChainEnforceSenderSeq);
        globals.put(AppChainPropertyKeys.VALIDATION_STRICT, appChainValidationStrict);
        // Dynamic plugin config is copied verbatim so extension settings can
        // be enabled from node-app config, not only through direct runtime
        // construction in tests. Keep this list shared with indexed-chain
        // parsing below: omitting effects.* here silently disables ADR-010 in
        // a packaged node even though the configuration is present.
        forwardAppChainDynamicKeys(globals);
        if (!appChainList.isEmpty() && appChainEnabled) {
            globals.put(AppChainPropertyKeys.CHAINS, appChainList);
            log.info("App-chain multi-chain config: {} chain(s)", appChainList.size());
        } else if (!appChainList.isEmpty()) {
            log.info("App-chain multi-chain config present but yano.app-chain.enabled=false — not started");
        }
        appConfig.getOptionalValue("yano.app-chain.network-magic", String.class)
                .ifPresent(value -> globals.put("yano.app-chain.network-magic", value));
    }

    /**
     * Multi-chain config (ADR app-layer/006 E5.2): reads indexed properties
     * yano.app-chain.chains[i].&lt;suffix&gt; into suffix-keyed maps for the runtime.
     */
    List<Map<String, Object>> parseAppChainChains() {
        var config = appConfig;
        List<Map<String, Object>> chains = new ArrayList<>();
        String[] suffixes = {
                "chain-id", "signing-key", "members", "peers",
                "sequencer.proposer", "threshold",
                "block.interval-ms", "block.max-messages", "block.max-bytes",
                "state-machine",
                "max-message-bytes", "max-ttl-seconds", "default-ttl-seconds",
                "anchor.enabled", "anchor.signing-key", "anchor.every-blocks",
                "anchor.max-interval-minutes", "anchor.metadata-label",
                "anchor.validity-slots", "anchor.fallback-fee-lovelace",
                "anchor.mode", "anchor.script.validator", "anchor.script.thread-policy",
                "l1.stability-depth", "webhooks",
                "retention.enabled", "retention.keep-blocks",
                "pool.max-messages", "message.enforce-sender-seq"
        };
        for (int i = 0; i < 50; i++) {
            String prefix = "yano.app-chain.chains[" + i + "].";
            Optional<String> chainId = config.getOptionalValue(prefix + "chain-id", String.class);
            if (chainId.isEmpty()) {
                break;
            }
            Map<String, Object> chain = new LinkedHashMap<>();
            for (String suffix : suffixes) {
                config.getOptionalValue(prefix + suffix, String.class)
                        .ifPresent(value -> chain.put(suffix, value));
            }
            // Dynamic plugin keys: chains[i].<extension>.* -> suffix.
            for (String property : config.getPropertyNames()) {
                if (isAppChainDynamicKey(property, prefix)) {
                    config.getOptionalValue(property, String.class)
                            .ifPresent(value -> chain.put(property.substring(prefix.length()), value));
                }
            }
            chains.add(chain);
        }
        return chains;
    }

    private static final List<String> APP_CHAIN_DYNAMIC_PREFIXES = AppChainConfigParser.dynamicPrefixes();

    private static boolean isAppChainDynamicKey(String property, String base) {
        return APP_CHAIN_DYNAMIC_PREFIXES.stream()
                .anyMatch(prefix -> property.startsWith(base + prefix));
    }

    void forwardAppChainDynamicKeys(Map<String, Object> globals) {
        for (String prefix : APP_CHAIN_DYNAMIC_PREFIXES) {
            forwardDynamicKeys("yano.app-chain." + prefix, globals);
        }
    }

    /** Copy every config property starting with {@code prefix} into globals verbatim. */
    private void forwardDynamicKeys(String prefix, Map<String, Object> globals) {
        var config = appConfig;
        for (String property : config.getPropertyNames()) {
            if (property.startsWith(prefix)) {
                config.getOptionalValue(property, String.class)
                        .ifPresent(value -> globals.put(property, value));
            }
        }
    }
}
