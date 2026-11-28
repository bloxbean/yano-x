package org.yanoproject.x.assets.onchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import org.julclang.core.PlutusData;
import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.StakingCredential;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.DepositAdmission;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.VaultOutputShape;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/** Shared constants and encoders for the gateway validator tests. */
final class GatewayFixtures {
    /** The validators plus the proof-onchain libraries, synced by {@code prepareJulcTestSources}. */
    static final Path SOURCES = Path.of("build/generated/julc-test-sources");
    static final Hash32 GENESIS = Hash32.of(filled(0x33, 32));
    static final Hash32 DOMAIN = GatewayIds.domainId(0, 1, "payments-1", GENESIS, "asset-payments",
            "anchored-claim-vault-v1", 1);
    static final AssetId TOKEN = AssetId.of(filled(0x7a, 28), "USDM".getBytes());

    private GatewayFixtures() {
    }

    static byte[] cborUInt(BigInteger n) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (n.compareTo(BigInteger.valueOf(24)) < 0) {
            out.write(n.intValue());
        } else {
            int width = n.compareTo(BigInteger.valueOf(256)) < 0 ? 1
                    : n.compareTo(BigInteger.valueOf(65_536)) < 0 ? 2
                    : n.compareTo(BigInteger.valueOf(4_294_967_296L)) < 0 ? 4 : 8;
            out.write(width == 1 ? 24 : width == 2 ? 25 : width == 4 ? 26 : 27);
            byte[] raw = n.toByteArray();
            byte[] fixed = new byte[width];
            int copy = Math.min(raw.length, width);
            System.arraycopy(raw, raw.length - copy, fixed, width - copy, copy);
            out.writeBytes(fixed);
        }
        return out.toByteArray();
    }

    static byte[] enterpriseKeyAddress(byte[] keyHash) {
        return concat(new byte[] {0x60}, keyHash);
    }

    static byte[] baseKeyAddress(byte[] keyHash, byte[] stakeKeyHash) {
        return concat(concat(new byte[] {0x00}, keyHash), stakeKeyHash);
    }

    /** A julc ledger address from raw Shelley base, pointer or enterprise bytes. */
    static Address ledgerAddress(byte[] raw) {
        int type = (raw[0] & 0xff) >> 4;
        byte[] payment = Arrays.copyOfRange(raw, 1, 29);
        if (type == 4 || type == 5) {
            long[] ptr = new long[3];
            int at = 29;
            for (int i = 0; i < 3; i++) {
                long n = 0;
                int b;
                do {
                    b = raw[at++] & 0xff;
                    n = n * 128 + (b & 0x7f);
                } while ((b & 0x80) != 0);
                ptr[i] = n;
            }
            Credential credential = type == 4 ? new Credential.PubKeyCredential(new PubKeyHash(payment))
                    : new Credential.ScriptCredential(new ScriptHash(payment));
            return new Address(credential, Optional.of(new StakingCredential.StakingPtr(BigInteger.valueOf(ptr[0]),
                    BigInteger.valueOf(ptr[1]), BigInteger.valueOf(ptr[2]))));
        }
        Credential paymentCredential = (type & 1) == 0 && type != 7
                ? new Credential.PubKeyCredential(new PubKeyHash(payment))
                : new Credential.ScriptCredential(new ScriptHash(payment));
        if (type == 6 || type == 7) {
            paymentCredential = type == 6 ? new Credential.PubKeyCredential(new PubKeyHash(payment))
                    : new Credential.ScriptCredential(new ScriptHash(payment));
            return new Address(paymentCredential, Optional.empty());
        }
        byte[] stake = Arrays.copyOfRange(raw, 29, 57);
        Credential stakeCredential = (type & 2) == 0
                ? new Credential.PubKeyCredential(new PubKeyHash(stake))
                : new Credential.ScriptCredential(new ScriptHash(stake));
        return new Address(paymentCredential, Optional.of(new StakingCredential.StakingHash(stakeCredential)));
    }

    static PlutusData plutusAddress(byte[] raw) {
        return ledgerAddress(raw).toPlutusData();
    }

    static Address scriptAddress(byte[] scriptHash) {
        return new Address(new Credential.ScriptCredential(new ScriptHash(scriptHash)), Optional.empty());
    }

    static Address scriptAddressWithStake(byte[] scriptHash, byte[] stakeKeyHash) {
        return new Address(new Credential.ScriptCredential(new ScriptHash(scriptHash)),
                Optional.of(new StakingCredential.StakingHash(
                        new Credential.PubKeyCredential(new PubKeyHash(stakeKeyHash)))));
    }

    static DepositRecordV1 creditedRecord(Hash32 vault, Hash32 tx, long index, long amount) {
        return new DepositRecordV1(GatewayIds.depositId(vault, tx, index), vault, tx, index, 9_000, 77,
                VaultOutputShape.DEPOSIT, DepositRecordV1.Status.CREDITED, DepositAdmission.Reason.NONE,
                filled(0xa1, 28), BigInteger.valueOf(amount), BigInteger.valueOf(2_000_000), 0,
                Hash32.of(filled(0x5e, 32)), BigInteger.valueOf(amount), BigInteger.valueOf(2_000_000));
    }

    static DepositRecordV1 uncreditedRecord(Hash32 vault, Hash32 tx, long index, DepositAdmission.Reason reason) {
        return new DepositRecordV1(GatewayIds.depositId(vault, tx, index), vault, tx, index, 9_000, 77,
                VaultOutputShape.DEPOSIT, DepositRecordV1.Status.UNCREDITED, reason, new byte[0],
                BigInteger.valueOf(5), BigInteger.valueOf(2_000_000), 0, Hash32.of(filled(0x5e, 32)),
                BigInteger.ZERO, BigInteger.ZERO);
    }

    static byte[] blake2b256(byte[] value) {
        return Blake2bUtil.blake2bHash256(value);
    }

    static byte[] blake2b224(byte[] value) {
        return Blake2bUtil.blake2bHash224(value);
    }

    static byte[] concat(byte[] left, byte[] right) {
        byte[] out = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }

    static byte[] filled(int value, int length) {
        byte[] out = new byte[length];
        Arrays.fill(out, (byte) value);
        return out;
    }

    /** An in-memory node store for the cardano-client-lib MPF trie. */
    static final class MapNodeStore implements NodeStore {
        private final Map<String, byte[]> nodes = new HashMap<>();

        @Override
        public byte[] get(byte[] hash) {
            return nodes.get(HexFormat.of().formatHex(hash));
        }

        @Override
        public void put(byte[] hash, byte[] nodeBytes) {
            nodes.put(HexFormat.of().formatHex(hash), nodeBytes);
        }

        @Override
        public void delete(byte[] hash) {
            nodes.remove(HexFormat.of().formatHex(hash));
        }
    }
}
