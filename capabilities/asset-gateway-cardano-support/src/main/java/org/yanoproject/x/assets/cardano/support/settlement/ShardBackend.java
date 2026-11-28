package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The two interchangeable nullifier-shard validators (ADR-059 D8c, r23). Both enforce the same rules on the same
 * datum, redeemer and parameters {@code (shardThreadPolicyId, vaultScriptHash)}; they differ only in their compiled
 * bytes, so the shard script hash and address depend on the backend. A deployment selects one with
 * {@code shard-backend}, and the choice is part of every member's pinned identity.
 *
 * <ul>
 *   <li>{@link #JULC} (default): {@code AssetShardValidator}, julc-compiled, with Yano X's copy of the MPF library.</li>
 *   <li>{@link #AIKEN}: {@code asset_shard.ak}, aiken-lang/merkle-patricia-forestry v2.1.0 behind Yano X's own
 *       proof-shape checks, the same rules as the julc backend.</li>
 * </ul>
 *
 * The templates are the on-chain module's committed artifacts, copied into this library at build time.
 */
public enum ShardBackend {
    JULC("julc", "AssetShardValidator.plutus.json", "ProgrammableShardValidator.plutus.json"),
    AIKEN("aiken", "AssetShardValidatorAiken.plutus.json", "ProgrammableShardValidatorAiken.plutus.json");

    /** The backend when {@code shard-backend} is not set. */
    public static final ShardBackend DEFAULT = JULC;

    static final String TEMPLATE_DIRECTORY = "META-INF/yano/asset-gateway/shard-templates/";
    private static final Pattern CBOR_HEX = Pattern.compile("\"cborHex\"\\s*:\\s*\"([0-9a-fA-F]+)\"");

    private final String id;
    private final String resource;
    private final String programmableResource;
    private volatile byte[] template;
    private volatile byte[] programmableTemplate;

    ShardBackend(String id, String resource, String programmableResource) {
        this.id = id;
        this.resource = TEMPLATE_DIRECTORY + resource;
        this.programmableResource = TEMPLATE_DIRECTORY + programmableResource;
    }

    /** The setting value: {@code julc} or {@code aiken}. */
    public String id() {
        return id;
    }

    /** Parses a {@code shard-backend} value (case-sensitive, trimmed); a blank or absent value is the default. */
    public static ShardBackend parse(String value) {
        if (value == null || value.isBlank()) return DEFAULT;
        String trimmed = value.trim();
        for (ShardBackend backend : values()) {
            if (backend.id.equals(trimmed)) return backend;
        }
        throw new IllegalArgumentException("shard-backend must be julc or aiken, not '" + trimmed + "'");
    }

    /** The shard script of one vault: the parameterized program, its hash and its enterprise address. */
    public record ShardScript(ShardBackend backend, byte[] flat, byte[] scriptHash, byte[] address) {
        public ShardScript {
            Objects.requireNonNull(backend, "backend");
            flat = flat.clone();
            scriptHash = scriptHash.clone();
            address = address.clone();
        }

        @Override
        public byte[] flat() {
            return flat.clone();
        }

        @Override
        public byte[] scriptHash() {
            return scriptHash.clone();
        }

        @Override
        public byte[] address() {
            return address.clone();
        }

        /** The script as the ledger stores it (CBOR byte string of the flat program), hex. */
        public String scriptCborHex() {
            return HexFormat.of().formatHex(FlatTemplates.cborBytes(flat));
        }
    }

    /**
     * The shard script of a vault (D4 step 7): this backend's template applied to {@code (shardPolicyId,
     * vaultScriptHash)}, its Plutus V3 hash and its enterprise address on {@code addressNetworkId}.
     */
    public ShardScript derive(byte[] shardPolicyId, byte[] vaultScriptHash, int addressNetworkId) {
        if (shardPolicyId == null || shardPolicyId.length != 28) {
            throw new IllegalArgumentException("shard policy id must be 28 bytes");
        }
        if (vaultScriptHash == null || vaultScriptHash.length != 28) {
            throw new IllegalArgumentException("vault script hash must be 28 bytes");
        }
        if (addressNetworkId < 0 || addressNetworkId > 15) {
            throw new IllegalArgumentException("address network id is 0..15");
        }
        byte[] flat = FlatTemplates.applyBytes(template(), shardPolicyId, vaultScriptHash);
        byte[] hash = scriptHash(flat);
        byte[] address = new byte[29];
        address[0] = (byte) (0x70 | addressNetworkId);
        System.arraycopy(hash, 0, address, 1, 28);
        return new ShardScript(this, flat, hash, address);
    }

    /**
     * The shard binding variant of a CIP-113 vault (ADR-061 D5, D2 step 7): this backend's
     * {@code ProgrammableShardValidator} applied to {@code (shardPolicyId, custodyScriptHash)}, which binds to the
     * custody script's withdrawal instead of a vault input. The native shard is unchanged.
     */
    public ShardScript deriveProgrammable(byte[] shardPolicyId, byte[] custodyScriptHash, int addressNetworkId) {
        if (shardPolicyId == null || shardPolicyId.length != 28 || custodyScriptHash == null
                || custodyScriptHash.length != 28 || addressNetworkId < 0 || addressNetworkId > 15) {
            throw new IllegalArgumentException("shard policy id and custody hash are 28 bytes, network 0..15");
        }
        byte[] loaded = programmableTemplate;
        if (loaded == null) programmableTemplate = loaded = load(programmableResource);
        byte[] flat = FlatTemplates.applyBytes(loaded, shardPolicyId, custodyScriptHash);
        byte[] hash = scriptHash(flat);
        byte[] address = new byte[29];
        address[0] = (byte) (0x70 | addressNetworkId);
        System.arraycopy(hash, 0, address, 1, 28);
        return new ShardScript(this, flat, hash, address);
    }

    /** The Plutus V3 script hash: {@code blake2b-224(0x03 ‖ CBOR bytes(flat))}. */
    static byte[] scriptHash(byte[] flat) {
        byte[] script = FlatTemplates.cborBytes(flat);
        byte[] tagged = new byte[script.length + 1];
        tagged[0] = 3;
        System.arraycopy(script, 0, tagged, 1, script.length);
        return Blake2bUtil.blake2bHash224(tagged);
    }

    /** The unparameterized template's flat program. */
    byte[] template() {
        byte[] loaded = template;
        if (loaded == null) {
            loaded = load(resource);
            template = loaded;
        }
        return loaded;
    }

    private static byte[] load(String resource) {
        try (InputStream in = ShardBackend.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("shard template missing from the classpath: " + resource);
            Matcher matcher = CBOR_HEX.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (!matcher.find()) throw new IllegalStateException("no cborHex in " + resource);
            // The envelope wraps the script bytes in one more CBOR byte string; the script bytes wrap the flat program.
            return unwrapBytes(unwrapBytes(HexFormat.of().parseHex(matcher.group(1))));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /** The payload of one definite-length CBOR byte string that is the whole of {@code cbor}. */
    static byte[] unwrapBytes(byte[] cbor) {
        if (cbor.length == 0 || (cbor[0] & 0xe0) != 0x40) throw new IllegalArgumentException("not a CBOR byte string");
        int argument = cbor[0] & 0x1f;
        int header;
        long length;
        if (argument < 24) {
            header = 1;
            length = argument;
        } else if (argument == 24) {
            header = 2;
            length = cbor[1] & 0xff;
        } else if (argument == 25) {
            header = 3;
            length = ((cbor[1] & 0xff) << 8) | (cbor[2] & 0xff);
        } else if (argument == 26) {
            header = 5;
            length = ((long) (cbor[1] & 0xff) << 24) | ((cbor[2] & 0xff) << 16) | ((cbor[3] & 0xff) << 8)
                    | (cbor[4] & 0xff);
        } else {
            throw new IllegalArgumentException("unsupported CBOR byte-string length");
        }
        if (header + length != cbor.length) throw new IllegalArgumentException("CBOR byte string length mismatch");
        byte[] payload = new byte[(int) length];
        System.arraycopy(cbor, header, payload, 0, payload.length);
        return payload;
    }
}
