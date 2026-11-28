package org.yanoproject.x.assets.cardano.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yanoproject.x.assets.cardano.support.settlement.ExitRelayer;
import org.yanoproject.x.assets.cardano.support.settlement.PayoutTxBuilder;
import org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput;
import org.yanoproject.x.assets.cardano.support.settlement.ShardMirror;
import org.yanoproject.x.assets.cardano.support.settlement.VaultPins;
import org.yanoproject.x.assets.cardano.support.settlement.VaultReconciliation;
import org.yanoproject.x.assets.cardano.support.settlement.VaultReconciliation.Report;
import org.yanoproject.x.assets.cardano.support.settlement.VaultReconciliation.Verdict;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.GatewayDatums;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.SettlementBatchV1;
import org.yanoproject.x.assets.contracts.SettlementQueries;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/**
 * This node's periodic L1 reconciliation (ADR-059 D15), read-only and node-local: per vault it scans every output at
 * the vault address in its own L1 view, reads the vault's counters, deposit records and {@code RESERVED} claims from
 * committed state, and the shards' live nullifier roots, and reports {@link VaultReconciliation} per unit.
 *
 * <p>The two sides are read at one point: the committed height and the L1 view's applied point, slot <b>and</b> block
 * hash, are read before and after the scan, and a pass whose state moved under it is read again (at most
 * {@link #ATTEMPTS} times) and otherwise reported {@code UNAVAILABLE}, never compared across two points. A view that
 * cannot name its applied point (no slot, or no block hash) is {@code UNAVAILABLE}, never a {@code DISCREPANCY}, and
 * so is any other unavailable read, so a partial or unnamed view never alerts.
 *
 * <p><b>Outputs.</b> The latest reports ({@link #lastReports()}); the {@link TaskProduct#RECONCILIATION} executor
 * snapshot (a {@code DISCREPANCY} pass is a terminal {@code CONFLICT} failure, an {@code UNAVAILABLE} one retryable)
 * in the app-chain status and metrics; a WARN line per discrepant vault with its explanation, and an INFO line when a
 * vault's verdict changes; and, when {@code reconciliation.report-file} is set, the whole report as JSON, replaced
 * atomically each pass. It never writes consensus state: on a discrepancy the runbook's governed {@code PAUSE} is the
 * members' decision (D15).
 */
final class ReconciliationTask {
    private static final Logger LOG = LoggerFactory.getLogger(ReconciliationTask.class);
    static final int ATTEMPTS = 3;

    private final NodeL1View l1;
    private final CommittedClaims committed;
    private final List<VaultPins> vaults;
    private final Optional<Path> reportFile;
    private final LongSupplier clock;
    private final TaskProduct product = new TaskProduct(TaskProduct.RECONCILIATION);
    private final Map<Hash32, Verdict> verdicts = new HashMap<>();
    private volatile List<Report> last = List.of();

    ReconciliationTask(NodeL1View l1, CommittedClaims committed, List<VaultPins> vaults, Optional<Path> reportFile,
                       LongSupplier clock) {
        this.l1 = Objects.requireNonNull(l1, "l1");
        this.committed = Objects.requireNonNull(committed, "committed");
        this.vaults = List.copyOf(vaults);
        this.reportFile = Objects.requireNonNull(reportFile, "report file");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    TaskProduct product() {
        return product;
    }

    List<Report> lastReports() {
        return last;
    }

    /** One pass over every vault; returns one line per vault. */
    synchronized String runOnce() {
        List<Report> reports = new ArrayList<>();
        for (VaultPins pins : vaults) {
            Report report;
            try {
                report = reconcile(pins);
            } catch (RuntimeException failure) {
                report = Report.unavailable(pins.vaultId(), pins.asset(), "the pass failed: " + failure);
            }
            reports.add(report);
            log(report);
        }
        last = List.copyOf(reports);
        boolean conflict = reports.stream().anyMatch(report -> report.verdict() == Verdict.DISCREPANCY);
        boolean unavailable = reports.stream().anyMatch(report -> report.verdict() == Verdict.UNAVAILABLE);
        product.record(conflict ? TaskProduct.Pass.CONFLICT
                : unavailable ? TaskProduct.Pass.WAITING : TaskProduct.Pass.ACTED);
        reportFile.ifPresent(this::write);
        return String.join("; ", reports.stream().map(report -> "vault " + report.vaultId() + " "
                + report.verdict()).toList());
    }

    private Report reconcile(VaultPins pins) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            Optional<SettlementQueries.CountersView> before = committed.counters(pins.vaultId());
            Optional<VaultReconciliation.L1Point> pointBefore = l1.appliedPoint();
            if (before.isEmpty()) return unavailable(pins, "the committed counters are unavailable");
            if (pointBefore.isEmpty()) {
                return unavailable(pins, "the L1 view names no applied point (slot and block hash)");
            }
            Optional<List<ResolvedOutput>> outputs = l1.scan(pins.vaultAddress());
            if (outputs.isEmpty()) return unavailable(pins, "the L1 view could not read the whole vault address");
            Optional<CommittedClaims.Index> index = committed.index();
            if (index.isEmpty()) return unavailable(pins, "the committed claim index is unavailable");
            Map<Hash32, Optional<DepositRecordV1>> records = new HashMap<>();
            for (ResolvedOutput out : outputs.get()) {
                if (PayoutTxBuilder.accepted(out, pins)) continue;
                Hash32 depositId = GatewayIds.depositId(pins.vaultId(), out.outpoint().txId(), out.outpoint().index());
                Optional<Optional<DepositRecordV1>> record = committed.depositLookup(depositId);
                if (record.isEmpty()) return unavailable(pins, "a committed deposit record is unavailable");
                records.put(depositId, record.get());
            }
            Set<Integer> ahead = new TreeSet<>();
            for (int shard = 0; shard < SettlementBatchV1.SHARDS; shard++) {
                Optional<Hash32> root = l1.shard(pins, shard).flatMap(ResolvedOutput::inlineDatum)
                        .flatMap(GatewayDatums::shardDatum).map(GatewayDatums.ShardDatum::nullifierRoot);
                // A shard not on L1 (not bootstrapped) has paid nothing, so it is never ahead.
                if (root.isPresent() && !ShardMirror.of(shard, index.get().paid(pins.vaultId(), shard))
                        .matches(root.get())) {
                    ahead.add(shard);
                }
            }
            Optional<SettlementQueries.CountersView> after = committed.counters(pins.vaultId());
            Optional<VaultReconciliation.L1Point> pointAfter = l1.appliedPoint();
            if (pointAfter.isEmpty()) {
                return unavailable(pins, "the L1 view names no applied point (slot and block hash)");
            }
            if (after.isEmpty() || after.get().height() != before.get().height() || !pointAfter.equals(pointBefore)) {
                continue; // committed state or the L1 view moved during the scan: read both again
            }
            List<ClaimLeafV1> reserved = index.get().reserved(pins.vaultId()).stream()
                    .map(ExitRelayer.Candidate::leaf).toList();
            return VaultReconciliation.reconcile(VaultReconciliation.Vault.of(pins),
                    new VaultReconciliation.Consensus(before.get().height(), before.get().counters(),
                            id -> records.getOrDefault(id, Optional.empty()), reserved, ahead),
                    new VaultReconciliation.L1Scan(pointBefore, outputs.get(), true));
        }
        return unavailable(pins, "committed state or the L1 view kept moving during the scan");
    }

    private static Report unavailable(VaultPins pins, String why) {
        return Report.unavailable(pins.vaultId(), pins.asset(), why);
    }

    private void log(Report report) {
        Verdict previous = verdicts.put(report.vaultId(), report.verdict());
        if (report.verdict() == Verdict.DISCREPANCY) {
            for (VaultReconciliation.Unit unit : report.units()) {
                if (unit.verdict() != Verdict.DISCREPANCY) continue;
                LOG.warn("asset reconciliation DISCREPANCY: vault {} unit {} at committed height {}, L1 {}: {}",
                        report.vaultId(), unit.unit(), report.height(), report.l1Point().map(point -> "slot "
                                + point.slot() + " block " + point.blockHash()).orElse("unknown"),
                        unit.explanation());
            }
        } else if (previous != report.verdict()) {
            LOG.info("asset reconciliation: vault {} is {} (was {}){}", report.vaultId(), report.verdict(),
                    previous == null ? "unknown" : previous, report.notes().isEmpty() ? "" : ": " + report.notes());
        }
    }

    private void write(Path file) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schema", "yano-x-asset-reconciliation-v1");
        document.put("generatedAtMs", clock.getAsLong());
        document.put("vaults", last.stream().map(Report::toMap).toList());
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, Json.write(document) + "\n", StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException failure) {
            LOG.warn("asset reconciliation report {} could not be written: {}", file, failure.toString());
        }
    }

    /** The few JSON shapes a report holds: maps, lists, strings, numbers, booleans. */
    static final class Json {
        private Json() {
        }

        static String write(Object value) {
            StringBuilder out = new StringBuilder();
            append(out, value);
            return out.toString();
        }

        private static void append(StringBuilder out, Object value) {
            switch (value) {
                case null -> out.append("null");
                case Map<?, ?> map -> {
                    out.append('{');
                    boolean first = true;
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (!first) out.append(',');
                        first = false;
                        string(out, String.valueOf(entry.getKey()));
                        out.append(':');
                        append(out, entry.getValue());
                    }
                    out.append('}');
                }
                case List<?> list -> {
                    out.append('[');
                    for (int i = 0; i < list.size(); i++) {
                        if (i > 0) out.append(',');
                        append(out, list.get(i));
                    }
                    out.append(']');
                }
                case Number number -> out.append(number);
                case Boolean bool -> out.append(bool);
                default -> string(out, value.toString());
            }
        }

        private static void string(StringBuilder out, String text) {
            out.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            out.append('"');
        }
    }
}
