package org.yanoproject.x.assets.contracts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * ADR-061 D10: what the pinned CIP-113 scripts require of a transaction, derived from the policies actually present
 * in its programmable (PLB) inputs, by the one function that the assembler and the members' body check both run.
 *
 * <ul>
 *   <li>each distinct non-ADA policy of the PLB inputs, in ascending byte order, gets one registry proof:
 *       {@code TokenExists} against its registry node if it is registered, {@code TokenDoesNotExist} against the
 *       covering node otherwise;</li>
 *   <li>each registered policy present gets its transfer hook as a withdrawal, with its substandard adapter's
 *       redeemer, plus the reference inputs the adapter names; two policies whose nodes name the same hook
 *       credential share one withdrawal, whose redeemers must be byte-equal, or the transaction cannot be assembled;
 *       reference outpoints are de-duplicated;</li>
 *   <li>lovelace-only inputs contribute nothing;</li>
 *   <li>the live wiring contributes the dispatcher and its transfer delegate.</li>
 * </ul>
 *
 * Every index (node, params, withdrawal) is computed later, by the assembler, against the final ledger order.
 */
public record Cip113Requirements(byte[] dispatcher, byte[] transferDelegate, List<Proof> proofs, List<Hook> hooks,
                                 List<ProgrammableDepositDatumV1.Outpoint> references) {

    /** A credential: a script or key hash. */
    public record Credential(boolean script, byte[] hash) {
        public Credential {
            if (hash == null || hash.length != 28) throw new IllegalArgumentException("credential hash is 28 bytes");
            hash = hash.clone();
        }

        @Override
        public byte[] hash() {
            return hash.clone();
        }

        String key() {
            return (script ? "s:" : "k:") + HexFormat.of().formatHex(hash);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Credential that && script == that.script && Arrays.equals(hash, that.hash);
        }

        @Override
        public int hashCode() {
            return key().hashCode();
        }
    }

    /** A registry node as a builder reads it from L1: its outpoint and the fields the transfer path uses. */
    public record RegistryNode(ProgrammableDepositDatumV1.Outpoint outpoint, byte[] key, byte[] next,
                               Credential transferLogic, byte[] globalStateCs) {
        public RegistryNode {
            Objects.requireNonNull(outpoint, "outpoint");
            Objects.requireNonNull(transferLogic, "transfer logic");
            key = key.clone();
            next = next.clone();
            globalStateCs = globalStateCs.clone();
        }

        @Override
        public byte[] key() {
            return key.clone();
        }

        @Override
        public byte[] next() {
            return next.clone();
        }

        @Override
        public byte[] globalStateCs() {
            return globalStateCs.clone();
        }

        /** True when this node covers {@code policy}: {@code key < policy < next}, bytewise. */
        public boolean covers(byte[] policy) {
            return Arrays.compareUnsigned(key, policy) < 0 && Arrays.compareUnsigned(policy, next) < 0;
        }
    }

    /** The builder's live view of the registry. */
    public interface Registry {
        /** The node whose key is {@code policy}, when the policy is registered. */
        Optional<RegistryNode> node(byte[] policy);

        /** The node that covers {@code policy}, when it is not registered. */
        Optional<RegistryNode> covering(byte[] policy);
    }

    /** What a substandard adapter returns for one registered policy (D10 rule 7). */
    public record HookCall(byte[] redeemer, List<ProgrammableDepositDatumV1.Outpoint> references) {
        public HookCall {
            redeemer = redeemer.clone();
            references = List.copyOf(references);
        }

        @Override
        public byte[] redeemer() {
            return redeemer.clone();
        }
    }

    /** One adapter per supported substandard: the hook redeemer and extra reference inputs of a policy. */
    @FunctionalInterface
    public interface Adapter {
        Optional<HookCall> call(byte[] policy, RegistryNode node);
    }

    /** One registry proof: the policy, whether it is registered, and the node it is proved against. */
    public record Proof(byte[] policy, boolean registered, RegistryNode node) {
        public Proof {
            policy = policy.clone();
            Objects.requireNonNull(node, "node");
        }

        @Override
        public byte[] policy() {
            return policy.clone();
        }
    }

    /** One hook withdrawal: its credential, the policies it serves and its merged redeemer. */
    public record Hook(Credential credential, List<byte[]> policies, byte[] redeemer) {
        public Hook {
            Objects.requireNonNull(credential, "credential");
            policies = policies.stream().map(byte[]::clone).toList();
            redeemer = redeemer.clone();
        }

        @Override
        public byte[] redeemer() {
            return redeemer.clone();
        }
    }

    /** Assembly is refused (D10): an unregistered policy without a covering node, no adapter, or a conflict. */
    public static final class Unassemblable extends RuntimeException {
        public Unassemblable(String message) {
            super(message);
        }
    }

    public Cip113Requirements {
        dispatcher = dispatcher.clone();
        transferDelegate = transferDelegate.clone();
        proofs = List.copyOf(proofs);
        hooks = List.copyOf(hooks);
        references = List.copyOf(references);
    }

    @Override
    public byte[] dispatcher() {
        return dispatcher.clone();
    }

    @Override
    public byte[] transferDelegate() {
        return transferDelegate.clone();
    }

    /**
     * The requirements of a transaction whose PLB inputs hold {@code inputs} (each a map from policy to asset name to
     * quantity; lovelace is not a policy), under the live dispatcher and its transfer delegate.
     *
     * @throws Unassemblable when a policy cannot be proved or a hook cannot be called with one compatible redeemer
     */
    public static Cip113Requirements of(List<Map<byte[], Map<byte[], java.math.BigInteger>>> inputs,
                                        byte[] dispatcher, byte[] transferDelegate, Registry registry,
                                        Adapter adapter) {
        TreeMap<byte[], Boolean> policies = new TreeMap<>(Arrays::compareUnsigned);
        for (Map<byte[], Map<byte[], java.math.BigInteger>> value : inputs) {
            for (Map.Entry<byte[], Map<byte[], java.math.BigInteger>> entry : value.entrySet()) {
                if (entry.getKey().length > 0 && !entry.getValue().isEmpty()) policies.put(entry.getKey(), true);
            }
        }
        List<Proof> proofs = new ArrayList<>();
        Map<String, Hook> hooks = new LinkedHashMap<>();
        Map<String, ProgrammableDepositDatumV1.Outpoint> references = new LinkedHashMap<>();
        for (byte[] policy : policies.keySet()) {
            Optional<RegistryNode> exact = registry.node(policy);
            if (exact.isPresent() && Arrays.equals(exact.get().key(), policy)) {
                RegistryNode node = exact.get();
                proofs.add(new Proof(policy, true, node));
                add(references, node.outpoint());
                HookCall call = adapter.call(policy, node).orElseThrow(() -> new Unassemblable(
                        "no supported adapter for registered policy " + HexFormat.of().formatHex(policy)));
                call.references().forEach(ref -> add(references, ref));
                Credential credential = node.transferLogic();
                Hook existing = hooks.get(credential.key());
                if (existing == null) {
                    hooks.put(credential.key(), new Hook(credential, List.of(policy), call.redeemer()));
                } else if (!Arrays.equals(existing.redeemer(), call.redeemer())) {
                    throw new Unassemblable("adapters disagree on the redeemer of hook " + credential.key());
                } else {
                    List<byte[]> served = new ArrayList<>(existing.policies());
                    served.add(policy);
                    hooks.put(credential.key(), new Hook(credential, served, existing.redeemer()));
                }
            } else {
                RegistryNode covering = registry.covering(policy).filter(node -> node.covers(policy))
                        .orElseThrow(() -> new Unassemblable("no covering registry node for policy "
                                + HexFormat.of().formatHex(policy)));
                proofs.add(new Proof(policy, false, covering));
                add(references, covering.outpoint());
            }
        }
        return new Cip113Requirements(dispatcher, transferDelegate, proofs, new ArrayList<>(hooks.values()),
                new ArrayList<>(references.values()));
    }

    private static void add(Map<String, ProgrammableDepositDatumV1.Outpoint> references,
                            ProgrammableDepositDatumV1.Outpoint outpoint) {
        references.putIfAbsent(outpoint.txId() + "#" + outpoint.index(), outpoint);
    }

    /** The registered policies present, in ascending order. */
    public List<byte[]> registeredPolicies() {
        return proofs.stream().filter(Proof::registered).map(Proof::policy)
                .sorted(Comparator.comparing(p -> p, Arrays::compareUnsigned)).toList();
    }
}
