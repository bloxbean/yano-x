package org.yanoproject.x.assets.cardano.gateway;

import org.yanoproject.x.assets.cardano.support.settlement.SettleBodyCheck;
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
final class CommittedClaims implements SettleBodyCheck.Committed {
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

    /**
     * The confirmed nullifier inserts of one shard: every {@code PAID} claim of the vault routing to it (D8h §6). It
     * walks the dense claim index, so it is complete or empty: a gap reads as unavailable.
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
            if (view.get().leaf().vaultId().equals(vaultId) && view.get().status().status() == ClaimStatus.PAID) {
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
