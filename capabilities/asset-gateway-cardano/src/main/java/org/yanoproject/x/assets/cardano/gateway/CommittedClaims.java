package org.yanoproject.x.assets.cardano.gateway;

import org.yanoproject.x.assets.cardano.support.settlement.AcceptBodyCheck;
import org.yanoproject.x.assets.cardano.support.settlement.ExitRelayer;
import org.yanoproject.x.assets.cardano.support.settlement.SettleBodyCheck;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.SettlementBatchV1;
import org.yanoproject.x.assets.contracts.SettlementQueries;
import org.yanoproject.x.assets.contracts.SettlementRecords.ClaimStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * The consumer's committed claim state, read through its {@link SettlementQueries} (ADR-059 D9): the executor and
 * every member read claim leaves, status and the domain status leaf from finalized state, never from a request or an
 * effect payload. A query that fails reads as absent, so callers fail closed.
 */
final class CommittedClaims implements SettleBodyCheck.Committed, AcceptBodyCheck.Deposits {
    private final BiFunction<String, byte[], byte[]> query;

    /** @param query a committed-state query, answering the payload or null */
    CommittedClaims(BiFunction<String, byte[], byte[]> query) {
        this.query = query;
    }

    @Override
    public Optional<SettlementQueries.ClaimView> claim(Hash32 claimId) {
        return payload(SettlementQueries.CLAIM, claimId.bytes())
                .flatMap(bytes -> SettlementQueries.decodeClaim(claimId, bytes));
    }

    @Override
    public Optional<DomainStatusV1> domainStatus() {
        return payload(SettlementQueries.DOMAIN_STATUS, new byte[0]).flatMap(SettlementQueries::decodeDomainStatus);
    }

    /** A deposit's committed record, the {@code Accept} co-signer's evidence (D8f, D8h §1 step 4). */
    @Override
    public Optional<DepositRecordV1> deposit(Hash32 depositId) {
        return payload(SettlementQueries.DEPOSIT, depositId.bytes())
                .flatMap(bytes -> SettlementQueries.decodeDeposit(depositId, bytes));
    }

    /**
     * Every committed claim, in one walk of the dense claim index: the exit relayer reads it once per pass for every
     * vault (Fable P3-4). Empty when the walk cannot complete, so the relayer relays nothing rather than from a partial
     * view.
     */
    Optional<Index> index() {
        Optional<Long> count = payload(SettlementQueries.CLAIM_COUNT, new byte[0]).map(SettlementQueries::decodeCount);
        if (count.isEmpty()) return Optional.empty();
        List<SettlementQueries.ClaimView> claims = new ArrayList<>();
        for (long sequence = 0; sequence < count.get(); sequence++) {
            Optional<byte[]> id = payload(SettlementQueries.CLAIM_BY_SEQUENCE,
                    SettlementQueries.encodeSequence(sequence));
            if (id.isEmpty() || id.get().length != Hash32.BYTES) return Optional.empty();
            Optional<SettlementQueries.ClaimView> view = claim(Hash32.of(id.get()));
            if (view.isEmpty()) return Optional.empty();
            claims.add(view.get());
        }
        return Optional.of(new Index(claims));
    }

    /** One complete walk of the claim index. */
    record Index(List<SettlementQueries.ClaimView> claims) {
        Index {
            claims = List.copyOf(claims);
        }

        /** Every {@code RESERVED} claim of the vault, the exit relayer's candidates (D9 step 1). */
        List<ExitRelayer.Candidate> reserved(Hash32 vaultId) {
            List<ExitRelayer.Candidate> reserved = new ArrayList<>();
            for (SettlementQueries.ClaimView view : claims) {
                if (view.leaf().vaultId().equals(vaultId) && view.status().status() == ClaimStatus.RESERVED) {
                    reserved.add(new ExitRelayer.Candidate(view.leaf(), ClaimStatus.RESERVED));
                }
            }
            return reserved;
        }

        /** The confirmed nullifier inserts of one shard, as {@link CommittedClaims#paid} reads them. */
        List<Hash32> paid(Hash32 vaultId, int shard) {
            List<Hash32> paid = new ArrayList<>();
            for (SettlementQueries.ClaimView view : claims) {
                ClaimStatus status = view.status().status();
                if (SettlementBatchV1.shardOf(view.leaf().claimId()) == shard && view.leaf().vaultId().equals(vaultId)
                        && (status == ClaimStatus.PAID || status == ClaimStatus.PAID_MISMATCH)) {
                    paid.add(view.leaf().claimId());
                }
            }
            return paid;
        }
    }

    /**
     * The confirmed nullifier inserts of one shard: every {@code PAID} or {@code PAID_MISMATCH} claim of the vault
     * routing to it (D8h §6). A mismatched payout was still thread-authenticated, so its nullifier is in the shard;
     * leaving it out would make the mirror miss the live root for good. It walks the dense claim index, so it is
     * complete or empty: a gap reads as unavailable. An insert of an id this ledger never reserved (a governed or
     * escape id, or a forged claim paid through a bug) is not in the index at all; only the L1-only shard rebuild
     * (D8g §7) recovers such a shard.
     */
    Optional<List<Hash32>> paid(Hash32 vaultId, int shard) {
        Optional<Long> count = payload(SettlementQueries.CLAIM_COUNT, new byte[0]).map(SettlementQueries::decodeCount);
        if (count.isEmpty()) return Optional.empty();
        List<Hash32> paid = new ArrayList<>();
        for (long sequence = 0; sequence < count.get(); sequence++) {
            Optional<byte[]> id = payload(SettlementQueries.CLAIM_BY_SEQUENCE, SettlementQueries.encodeSequence(sequence));
            if (id.isEmpty() || id.get().length != Hash32.BYTES) return Optional.empty();
            Hash32 claimId = Hash32.of(id.get());
            if (SettlementBatchV1.shardOf(claimId) != shard) continue;
            Optional<SettlementQueries.ClaimView> view = claim(claimId);
            if (view.isEmpty()) return Optional.empty();
            ClaimStatus status = view.get().status().status();
            if (view.get().leaf().vaultId().equals(vaultId)
                    && (status == ClaimStatus.PAID || status == ClaimStatus.PAID_MISMATCH)) {
                paid.add(claimId);
            }
        }
        return Optional.of(paid);
    }

    private Optional<byte[]> payload(String path, byte[] request) {
        try {
            return Optional.ofNullable(query.apply(path, request));
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }
}
