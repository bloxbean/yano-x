package org.yanoproject.x.assets.cardano.support.settlement;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.Cip113Requirements;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.yanoproject.x.assets.cardano.support.settlement.PayoutWorld.FEE_ADDRESS;

/**
 * ADR-061 §10 P5: {@code params_idx}, {@code wdrl_idx}, every {@code node_idx} and every redeemer index of assembled
 * transactions are recomputed by an independent Python implementation of the ledger orderings
 * ({@code scripts/verify-cip113-assembly.py}) and compared with the assembler's. The vectors move the fee wallet and
 * reference-script UTxOs around the params UTxO and the registry nodes, so their sort positions shift each index.
 */
class ProgrammableAssemblyVectorsTest {
    @Test
    void anIndependentImplementationAgreesWithEveryIndex() throws Exception {
        List<String> vectors = new ArrayList<>();
        int[][] placements = {{0x05, 0x01}, {0x6a, 0xf0}, {0xf9, 0x6b}, {0x6c, 0x60}};
        for (int i = 0; i < placements.length; i++) {
            ProgrammableWorld world = new ProgrammableWorld();
            world.rewards.put(HexFormat.of().formatHex(world.custody.custodyHash()), BigInteger.valueOf(1_000 * i));
            ResolvedOutput fee = world.add(new ResolvedOutput(PayoutWorld.outpoint(placements[i][0], 7), FEE_ADDRESS,
                    BigInteger.valueOf(80_000_000), new TreeMap<>(), Optional.empty(), Optional.empty()));
            ResolvedOutput refScript = world.add(new ResolvedOutput(PayoutWorld.outpoint(placements[i][1], 3),
                    FEE_ADDRESS, BigInteger.valueOf(20_000_000), new TreeMap<>(), Optional.empty(), Optional.of(
                    new ResolvedOutput.ReferenceScript(world.custody.custodyHash(), 1000, Optional.empty()))));
            ProgrammableVaultTxAssembler.Assembled settle = settleWith(world, 1 + i, 2 + i, fee, refScript);
            world.evaluate(settle);
            vectors.add(vector("settle-" + i, world, settle, List.of(world.custody.custodyHash())));

            ProgrammableWorld depositWorld = new ProgrammableWorld();
            TreeMap<AssetId, BigInteger> held = ProgrammableDepositBuilderTest.single(PayoutWorld.TOKEN, 1_000);
            held.put(ProgrammableWorld.ASSET_B, BigInteger.valueOf(40));
            held.put(ProgrammableWorld.UNREGISTERED, BigInteger.valueOf(3));
            ProgrammableDepositBuilder.Built deposit = ProgrammableDepositBuilderTest.deposit(depositWorld, held);
            depositWorld.evaluate(deposit.assembled());
            vectors.add(vector("deposit-" + i, depositWorld, deposit.assembled(), List.of()));
        }
        Path file = Path.of("build/cip113-assembly-vectors.json");
        Files.writeString(file, "[\n" + String.join(",\n", vectors) + "\n]\n");
        ProcessBuilder python = new ProcessBuilder("python3", "-I", "scripts/verify-cip113-assembly.py",
                file.toString()).redirectErrorStream(true);
        Process process;
        try {
            process = python.start();
        } catch (java.io.IOException noPython) {
            assumeThat(false).as("python3 is installed").isTrue();
            return;
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        System.out.println(output);
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains(vectors.size() + " vectors, 0 failed");
    }

    static ProgrammableVaultTxAssembler.Assembled settleWith(ProgrammableWorld world, int k, int shard,
                                                             ResolvedOutput fee, ResolvedOutput refScript) {
        return ProgrammableVaultTxAssemblerTest.settle(world, k, shard, world.wiring(), fee, refScript);
    }

    static String vector(String name, ProgrammableWorld world, ProgrammableVaultTxAssembler.Assembled assembled,
                         List<byte[]> custody) {
        HexFormat hex = HexFormat.of();
        StringBuilder nodes = new StringBuilder();
        for (Cip113Requirements.RegistryNode node : world.registryNodes()) {
            if (!nodes.isEmpty()) nodes.append(',');
            nodes.append('"').append(hex.formatHex(node.outpoint().txId().bytes())).append('#')
                    .append(node.outpoint().index()).append("\":{\"key\":\"").append(hex.formatHex(node.key()))
                    .append("\",\"next\":\"").append(hex.formatHex(node.next())).append("\"}");
        }
        List<String> plbInputs = new ArrayList<>();
        java.util.TreeSet<String> policies = new java.util.TreeSet<>();
        for (ResolvedOutput out : assembled.resolved()) {
            if (world.deployment.programmable(out.address())
                    && assembled.indexes().inputOrder().contains(out.outpoint().toString())) {
                plbInputs.add("\"" + out.outpoint().txIdHex() + "#" + out.outpoint().index() + "\"");
                out.assets().keySet().forEach(asset -> policies.add(hex.formatHex(asset.policyId())));
            }
        }
        List<String> quotedPolicies = policies.stream().map(p -> "\"" + p + "\"").toList();
        List<String> quotedCustody = custody.stream().map(c -> "\"" + hex.formatHex(c) + "\"").toList();
        return "{\"name\":\"" + name + "\",\"cbor\":\"" + hex.formatHex(assembled.cbor()) + "\",\"paramsRef\":\""
                + world.params.outpoint().txIdHex() + "#" + world.params.outpoint().index() + "\",\"dispatcher\":\""
                + hex.formatHex(world.dispatcher) + "\",\"transfer\":\"" + hex.formatHex(world.transfer)
                + "\",\"nodes\":{" + nodes + "},\"plbInputs\":[" + String.join(",", plbInputs) + "],\"policies\":["
                + String.join(",", quotedPolicies) + "],\"custody\":[" + String.join(",", quotedCustody) + "]}";
    }
}
