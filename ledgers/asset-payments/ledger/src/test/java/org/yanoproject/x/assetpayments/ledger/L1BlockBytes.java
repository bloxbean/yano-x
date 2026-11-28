package org.yanoproject.x.assetpayments.ledger;

import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import org.yanoproject.x.assets.contracts.AssetId;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.TreeMap;

/**
 * Builds real Conway block bytes ({@code [7, [header, bodies, witnesses, aux, invalid]]}) for tests,
 * so outputs go through the node's own block parser before the observer sees them. Header fields
 * that the parser does not check (VRF, KES, signature) are fixed filler.
 */
final class L1BlockBytes {
    record Output(byte[] address, long lovelace, java.util.Map<AssetId, Long> assets, byte[] inlineDatum) { }

    record Tx(int inputSeed, List<Output> outputs) { }

    private L1BlockBytes() {
    }

    static byte[] block(long blockNumber, long slot, List<Tx> transactions, List<Integer> invalid) {
        Array bodies = new Array();
        Array witnesses = new Array();
        for (Tx tx : transactions) {
            bodies.add(body(tx));
            witnesses.add(new Map());
        }
        Array invalidTxs = new Array();
        invalid.forEach(index -> invalidTxs.add(new UnsignedInteger(index)));
        Array block = new Array();
        block.add(header(blockNumber, slot));
        block.add(bodies);
        block.add(witnesses);
        block.add(new Map());
        block.add(invalidTxs);
        Array envelope = new Array();
        envelope.add(new UnsignedInteger(7));
        envelope.add(block);
        return encode(envelope);
    }

    /** A block of already-encoded transaction bodies and witness sets (for example, a built transaction's). */
    static byte[] blockOf(long blockNumber, long slot, List<DataItem> bodies, List<DataItem> witnessSets) {
        Array bodyArray = new Array();
        bodies.forEach(bodyArray::add);
        Array witnessArray = new Array();
        witnessSets.forEach(witnessArray::add);
        Array block = new Array();
        block.add(header(blockNumber, slot));
        block.add(bodyArray);
        block.add(witnessArray);
        block.add(new Map());
        block.add(new Array());
        Array envelope = new Array();
        envelope.add(new UnsignedInteger(7));
        envelope.add(block);
        return encode(envelope);
    }

    static Block parse(byte[] bytes) {
        return BlockSerializer.INSTANCE.deserialize(bytes);
    }

    private static DataItem body(Tx tx) {
        Map body = new Map();
        Array inputs = new Array();
        Array input = new Array();
        byte[] txIn = new byte[32];
        java.util.Arrays.fill(txIn, (byte) tx.inputSeed());
        input.add(new ByteString(txIn));
        input.add(new UnsignedInteger(tx.inputSeed()));
        inputs.add(input);
        body.put(new UnsignedInteger(0), inputs);
        Array outputs = new Array();
        for (Output output : tx.outputs()) outputs.add(output(output));
        body.put(new UnsignedInteger(1), outputs);
        body.put(new UnsignedInteger(2), new UnsignedInteger(200_000));
        return body;
    }

    private static DataItem output(Output output) {
        Map map = new Map();
        map.put(new UnsignedInteger(0), new ByteString(output.address()));
        if (output.assets().isEmpty()) {
            map.put(new UnsignedInteger(1), new UnsignedInteger(output.lovelace()));
        } else {
            Array value = new Array();
            value.add(new UnsignedInteger(output.lovelace()));
            TreeMap<String, Map> policies = new TreeMap<>();
            for (var entry : new TreeMap<>(output.assets()).entrySet()) {
                String policy = java.util.HexFormat.of().formatHex(entry.getKey().policyId());
                policies.computeIfAbsent(policy, ignored -> new Map())
                        .put(new ByteString(entry.getKey().assetName()),
                                new UnsignedInteger(BigInteger.valueOf(entry.getValue())));
            }
            Map multiAsset = new Map();
            policies.forEach((policy, names) ->
                    multiAsset.put(new ByteString(java.util.HexFormat.of().parseHex(policy)), names));
            value.add(multiAsset);
            map.put(new UnsignedInteger(1), value);
        }
        if (output.inlineDatum() != null) {
            Array datumOption = new Array();
            datumOption.add(new UnsignedInteger(1));
            ByteString datum = new ByteString(output.inlineDatum());
            datum.setTag(24);
            datumOption.add(datum);
            map.put(new UnsignedInteger(2), datumOption);
        }
        return map;
    }

    private static DataItem header(long blockNumber, long slot) {
        Array body = new Array();
        body.add(new UnsignedInteger(blockNumber));
        body.add(new UnsignedInteger(slot));
        body.add(new ByteString(new byte[32]));
        body.add(new ByteString(new byte[32]));
        body.add(new ByteString(new byte[32]));
        Array vrf = new Array();
        vrf.add(new ByteString(new byte[64]));
        vrf.add(new ByteString(new byte[80]));
        body.add(vrf);
        body.add(new UnsignedInteger(1024));
        body.add(new ByteString(new byte[32]));
        Array opCert = new Array();
        opCert.add(new ByteString(new byte[32]));
        opCert.add(new UnsignedInteger(0));
        opCert.add(new UnsignedInteger(0));
        opCert.add(new ByteString(new byte[64]));
        body.add(opCert);
        Array version = new Array();
        version.add(new UnsignedInteger(10));
        version.add(new UnsignedInteger(0));
        body.add(version);
        Array header = new Array();
        header.add(body);
        header.add(new ByteString(new byte[448]));
        return header;
    }

    private static byte[] encode(DataItem item) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new CborEncoder(out).nonCanonical().encode(item);
            return out.toByteArray();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
