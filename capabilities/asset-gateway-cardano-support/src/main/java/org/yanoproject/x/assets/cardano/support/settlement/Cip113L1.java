package org.yanoproject.x.assets.cardano.support.settlement;

import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.Cip113Requirements;
import org.yanoproject.x.assets.contracts.ProgrammableDepositDatumV1;
import org.yanoproject.x.assets.contracts.internal.PlutusDataReader;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * What a builder or member reads of a pinned CIP-113 deployment from its own L1 view (ADR-061 D10 rules 2, 6 and 7,
 * D13 step 3b): the live protocol-params UTxO, the registry nodes, and the substandard adapter of the vault's asset.
 * Every read authenticates by token, never by address alone, and never throws on L1 content.
 *
 * <ul>
 *   <li>The params UTxO is the one output carrying the params policy's {@code ProtocolParams} token.</li>
 *   <li>A registry node is an output carrying one token of the registry node policy whose name is the node's key
 *       (the origin node's name is empty), with a {@code RegistryNode} datum: {@code Constr 0 [key, next,
 *       minting_logic, transfer_logic, third_party_logic, unfracking_logic, global_state_cs]}.</li>
 * </ul>
 */
public final class Cip113L1 {
    /** The Yano X test substandard of the devnet drill: its hook takes {@code Constr 0 []} and no state. */
    public static final String TEST_SUBSTANDARD = "yano-x-test";

    private static final byte[] UNIT = {(byte) 0xd8, 0x79, (byte) 0x80};

    private Cip113L1() {
    }

    /** The live params UTxO among {@code outputs}: exactly one output carries the token, or none is live. */
    public static Optional<ResolvedOutput> params(List<ResolvedOutput> outputs, byte[] paramsPolicy) {
        AssetId token = AssetId.of(paramsPolicy, ProgrammableVaultTxAssembler.PROTOCOL_PARAMS_NAME);
        List<ResolvedOutput> found = outputs.stream().filter(out -> BigInteger.ONE.equals(out.quantityOf(token)))
                .toList();
        return found.size() == 1 ? Optional.of(found.getFirst()) : Optional.empty();
    }

    /** The registry node {@code out} carries, authenticated by its registry token; empty for anything else. */
    public static Optional<Cip113Requirements.RegistryNode> registryNode(ResolvedOutput out,
                                                                         byte[] registryNodePolicy) {
        if (out.inlineDatum().isEmpty()) return Optional.empty();
        if (!(PlutusDataReader.read(out.inlineDatum().get()) instanceof PlutusDataReader.Constr node)
                || node.index() != 0 || node.fields().size() != 7
                || !(node.fields().get(0) instanceof byte[] key) || !(node.fields().get(1) instanceof byte[] next)
                || !(node.fields().get(6) instanceof byte[] globalState)) {
            return Optional.empty();
        }
        byte[] tokenName = null;
        for (Map.Entry<AssetId, BigInteger> asset : out.assets().entrySet()) {
            if (!Arrays.equals(asset.getKey().policyId(), registryNodePolicy)) continue;
            if (tokenName != null || !BigInteger.ONE.equals(asset.getValue())) return Optional.empty();
            tokenName = asset.getKey().assetName();
        }
        if (tokenName == null || !Arrays.equals(tokenName, key)) return Optional.empty();
        // The origin node names no transfer logic (an empty key credential); it is only ever a covering node.
        Cip113Requirements.Credential transfer = credential(node.fields().get(3))
                .orElse(new Cip113Requirements.Credential(false, new byte[28]));
        return Optional.of(new Cip113Requirements.RegistryNode(new ProgrammableDepositDatumV1.Outpoint(
                out.outpoint().txId(), out.outpoint().index()), key, next, transfer, globalState));
    }

    /** The registry over {@code outputs}, the outputs at the registry address in this node's view. */
    public static Cip113Requirements.Registry registry(List<ResolvedOutput> outputs, byte[] registryNodePolicy) {
        List<Cip113Requirements.RegistryNode> nodes = new ArrayList<>();
        for (ResolvedOutput out : outputs) registryNode(out, registryNodePolicy).ifPresent(nodes::add);
        return new Cip113Requirements.Registry() {
            @Override
            public Optional<Cip113Requirements.RegistryNode> node(byte[] policy) {
                return nodes.stream().filter(node -> Arrays.equals(node.key(), policy)).findFirst();
            }

            @Override
            public Optional<Cip113Requirements.RegistryNode> covering(byte[] policy) {
                return nodes.stream().filter(node -> node.covers(policy)).findFirst();
            }
        };
    }

    /**
     * The builder's L1 for the assembler: the registry over {@code registryOutputs}, and outpoint reads from
     * {@code resolve}.
     */
    public static ProgrammableVaultTxAssembler.L1 l1(List<ResolvedOutput> registryOutputs, byte[] registryNodePolicy,
                                                     Function<ResolvedOutput.Outpoint, Optional<ResolvedOutput>>
                                                             resolve) {
        Cip113Requirements.Registry registry = registry(registryOutputs, registryNodePolicy);
        return new ProgrammableVaultTxAssembler.L1() {
            @Override
            public Optional<Cip113Requirements.RegistryNode> node(byte[] policy) {
                return registry.node(policy);
            }

            @Override
            public Optional<Cip113Requirements.RegistryNode> covering(byte[] policy) {
                return registry.covering(policy);
            }

            @Override
            public Optional<ResolvedOutput> resolve(ProgrammableDepositDatumV1.Outpoint outpoint) {
                if (outpoint.index() > Integer.MAX_VALUE) return Optional.empty();
                return resolve.apply(new ResolvedOutput.Outpoint(outpoint.txId(), (int) outpoint.index()));
            }
        };
    }

    /**
     * The substandard adapter of the vault asset's policy (D10 rule 7). The Yano X test substandard's hook takes
     * {@code Constr 0 []} and no state. Any other substandard has no adapter in this release, so a transaction with
     * its policy cannot be assembled ({@link Cip113Requirements.Unassemblable}), and a member refuses to sign one.
     */
    public static Cip113Requirements.Adapter adapter(String substandard, byte[] assetPolicy) {
        byte[] policy = assetPolicy.clone();
        if (!TEST_SUBSTANDARD.equals(substandard)) return (p, node) -> Optional.empty();
        return (p, node) -> Arrays.equals(p, policy)
                ? Optional.of(new Cip113Requirements.HookCall(UNIT, List.of())) : Optional.empty();
    }

    private static Optional<Cip113Requirements.Credential> credential(Object field) {
        if (!(field instanceof PlutusDataReader.Constr credential) || credential.index() > 1
                || credential.fields().size() != 1 || !(credential.fields().getFirst() instanceof byte[] hash)
                || hash.length != 28) {
            return Optional.empty();
        }
        return Optional.of(new Cip113Requirements.Credential(credential.index() == 1, hash));
    }
}
