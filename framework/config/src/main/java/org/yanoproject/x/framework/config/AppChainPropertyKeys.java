package org.yanoproject.x.framework.config;

/**
 * App-chain (parallel application ledger over the appmsg protocol) settings.
 * See adr/app-layer/005-yano-app-chain-framework.md.
 */
public final class AppChainPropertyKeys {
    public static final String ENABLED = "yano.app-chain.enabled";
    /** Node-local root directory containing one RocksDB ledger per hosted app chain. */
    public static final String STORAGE_PATH = "yano.app-chain.storage.path";
    /**
     * Multi-chain config (ADR 006 E5.2): runtime-globals key holding a
     * List of Maps of suffix-keyed chain settings, populated by adapters
     * from indexed properties yano.app-chain.chains[i].&lt;suffix&gt;.
     * When absent, the flat yano.app-chain.* keys define a single chain.
     */
    public static final String CHAINS = "yano.app-chain.chains";
    public static final String CHAIN_ID = "yano.app-chain.chain-id";
    /** This member's Ed25519 private key (hex, 32-byte seed). Required when enabled. */
    public static final String SIGNING_KEY = "yano.app-chain.signing-key";
    /** Comma-separated hex Ed25519 public keys of group members. */
    public static final String MEMBERS = "yano.app-chain.members";
    /** Comma-separated app-group peers as host:port. */
    public static final String PEERS = "yano.app-chain.peers";
    public static final String MAX_MESSAGE_BYTES = "yano.app-chain.max-message-bytes";
    public static final String MAX_TTL_SECONDS = "yano.app-chain.max-ttl-seconds";
    public static final String DEFAULT_TTL_SECONDS = "yano.app-chain.default-ttl-seconds";
    /** Fixed sequencer's Ed25519 public key (hex). Empty = diffusion-only (no ledger). */
    public static final String SEQUENCER_PROPOSER = "yano.app-chain.sequencer.proposer";
    /** Finality certificate signature threshold (n of members). */
    public static final String THRESHOLD = "yano.app-chain.threshold";
    /**
     * Outbound app transport: {@code shared} (default — ride the L1 peer
     * session to a peer that is also the configured upstream; one TCP
     * connection per peer pair, dedicated fallback on outage) or
     * {@code dedicated} (always dial a separate app connection, e.g. for
     * bandwidth isolation from L1 sync).
     */
    public static final String TRANSPORT_MODE = "yano.app-chain.transport.mode";
    public static final String BLOCK_INTERVAL_MS = "yano.app-chain.block.interval-ms";
    public static final String BLOCK_MAX_MESSAGES = "yano.app-chain.block.max-messages";
    /** Primary block-size cap in bytes (serialized block/proposal). */
    public static final String BLOCK_MAX_BYTES = "yano.app-chain.block.max-bytes";
    /** Built-in state machine id; default "ordered-log". */
    public static final String STATE_MACHINE = "yano.app-chain.state-machine";
    /** Require an MPF profile whose state proofs can be consumed by L1 validators. */
    public static final String STATE_L1_PROOF_CONSUMPTION_REQUIRED =
            "yano.app-chain.state.l1-proof-consumption-required";
    /** Node-local opt-in reachability pruning for retained MPF proof roots. */
    public static final String STATE_PROOF_PRUNING_ENABLED =
            "yano.app-chain.state.proof-pruning.enabled";
    /** Number of contiguous finalized app heights kept available for new proofs. */
    public static final String STATE_PROOF_PRUNING_RETAIN_HEIGHTS =
            "yano.app-chain.state.proof-pruning.retain-heights";
    /** Delay between background proof-pruning passes. */
    public static final String STATE_PROOF_PRUNING_INTERVAL_SECONDS =
            "yano.app-chain.state.proof-pruning.interval-seconds";
    public static final String ANCHOR_ENABLED = "yano.app-chain.anchor.enabled";
    /** Anchor wallet Ed25519 payment key (hex, 32-byte seed). */
    public static final String ANCHOR_SIGNING_KEY = "yano.app-chain.anchor.signing-key";
    public static final String ANCHOR_EVERY_BLOCKS = "yano.app-chain.anchor.every-blocks";
    public static final String ANCHOR_MAX_INTERVAL_MINUTES = "yano.app-chain.anchor.max-interval-minutes";
    public static final String ANCHOR_METADATA_LABEL = "yano.app-chain.anchor.metadata-label";
    /** Anchor tx TTL: current L1 slot + this many slots (008.1 I1.5). */
    public static final String ANCHOR_VALIDITY_SLOTS = "yano.app-chain.anchor.validity-slots";
    /** Anchor tx fee when protocol parameters are unavailable. */
    public static final String ANCHOR_FALLBACK_FEE_LOVELACE = "yano.app-chain.anchor.fallback-fee-lovelace";
    /** Anchor mode: metadata (default, A1) or script (A2, ADR 008.4). */
    public static final String ANCHOR_MODE = "yano.app-chain.anchor.mode";
    /** Script-anchor validator artifact ref: builtin:aiken | file:/path | hex:... */
    public static final String ANCHOR_SCRIPT_VALIDATOR = "yano.app-chain.anchor.script.validator";
    /** Script-anchor thread-policy artifact ref (same forms as the validator). */
    public static final String ANCHOR_SCRIPT_THREAD_POLICY = "yano.app-chain.anchor.script.thread-policy";
    /** Depth (L1 blocks) of the stable L1 reference in app blocks; 0 = disabled. */
    public static final String L1_STABILITY_DEPTH = "yano.app-chain.l1.stability-depth";
    /** Comma-separated webhook URLs receiving finalized blocks (at-least-once, cursored). */
    public static final String WEBHOOKS = "yano.app-chain.webhooks";
    /** Prune message bodies below the L1_FINAL anchor (proofs stay valid). */
    public static final String RETENTION_ENABLED = "yano.app-chain.retention.enabled";
    /** Keep bodies of at least this many most-recent blocks regardless of anchor. */
    public static final String RETENTION_KEEP_BLOCKS = "yano.app-chain.retention.keep-blocks";
    /** Pending-pool capacity; full pool = 429 on submit, counted drops inbound. */
    public static final String POOL_MAX_MESSAGES = "yano.app-chain.pool.max-messages";
    /** Consensus-visible sender-seq enforcement (008.1 I1.2); default false. */
    public static final String MESSAGE_ENFORCE_SENDER_SEQ = "yano.app-chain.message.enforce-sender-seq";
    /** Opt-in API-key authentication for READ/SUBMIT; PRIVILEGED always requires a full key. */
    public static final String API_AUTH_ENABLED = "yano.app-chain.api.auth.enabled";
    /**
     * Comma-separated API keys. An unscoped {@code key} enables privileged
     * operations; {@code key=topicA|topicB} is submission-topic scoped when
     * broad API authentication is enabled.
     */
    public static final String API_KEYS = "yano.app-chain.api.keys";
    /** Comma-separated keys authorized only for authenticated-snapshot lifecycle administration. */
    public static final String SNAPSHOT_ADMIN_API_KEYS =
            "yano.app-chain.api.snapshot-admin-keys";
    /** Reject unknown keys only inside runtime-owned namespaces with FULL metadata coverage. */
    public static final String VALIDATION_STRICT = "yano.app-chain.validation.strict";
    /** Project lock identity exposed only through the privileged diagnostics endpoint. */
    public static final String DX_RESOLVED_CONFIG_DIGEST =
            "yano.app-chain.dx.resolved-config-digest";
    /** Release catalog identity exposed only through the privileged diagnostics endpoint. */
    public static final String DX_RELEASE_CATALOG_DIGEST =
            "yano.app-chain.dx.release-catalog-digest";
    private AppChainPropertyKeys() {
    }
}
