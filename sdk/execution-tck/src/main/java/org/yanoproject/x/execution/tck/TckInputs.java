package org.yanoproject.x.execution.tck;

import org.yanoproject.x.execution.abi.v1.BlockInfo;
import org.yanoproject.x.execution.abi.v1.EffectResultInput;
import org.yanoproject.x.execution.abi.v1.EntryInputs;
import org.yanoproject.x.execution.abi.v1.Hashes;
import org.yanoproject.x.execution.abi.v1.MessageInput;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Deterministic sample inputs. Guest SDKs can reuse them in their own tests. */
public final class TckInputs {

    public static final String CHAIN_ID = "tck-chain";
    public static final byte[] CONFIG = "tck-config".getBytes(StandardCharsets.US_ASCII);

    private TckInputs() {
    }

    public static BlockInfo block(long height) {
        return new BlockInfo(CHAIN_ID, height, 1_700_000_000_000L + height * 1000, 10_000 + height,
                filled(32, (byte) 0xB1), filled(32, (byte) 0xA0), filled(32, (byte) 0x50));
    }

    public static MessageInput message(long height, int index, byte[] body) {
        byte[] id = Hashes.blake2b256(ByteBuffer.allocate(12).putLong(height).putInt(index).array());
        return new MessageInput(id, "t", filled(32, (byte) 0x11), index, 0, body, index);
    }

    public static byte[] applyMessage(long height, int index, byte[] body) {
        return new EntryInputs.ApplyMessage(block(height), message(height, index, body), CONFIG).encode();
    }

    public static byte[] validate(long candidateHeight, byte[] body) {
        return new EntryInputs.Validate(CHAIN_ID, candidateHeight, message(candidateHeight, 0, body), CONFIG).encode();
    }

    public static byte[] query(long committedHeight, String path, byte[] params) {
        return new EntryInputs.Query(CHAIN_ID, committedHeight, path, params, CONFIG).encode();
    }

    public static byte[] onEffectResult(long height, EffectResultInput result) {
        return new EntryInputs.OnEffectResult(block(height), result, CONFIG).encode();
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] be64(long n) {
        return ByteBuffer.allocate(8).putLong(n).array();
    }

    private static byte[] filled(int length, byte value) {
        byte[] out = new byte[length];
        Arrays.fill(out, value);
        return out;
    }
}
