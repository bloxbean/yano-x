package org.yanoproject.x.client;

import org.yanoproject.api.appchain.observation.ObservationHashes;
import org.yanoproject.api.appchain.observation.ObservationReport;
import org.yanoproject.api.appchain.observation.ObservationRound;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/**
 * Single-owner, fail-closed external Ed25519 reporter choice journal.
 * Public claim bytes are forced before the signer is invoked; keys are never stored.
 * Use a private retained local directory on a filesystem supporting file and directory fsync.
 * Never delete or copy a retained journal to bypass a conflict.
 */
public final class ObservationReporterJournal implements AutoCloseable {
    private final Path directory;
    private final byte[] identity;
    private final int maxEntries;
    private final long maxBytes;
    private final FileChannel owner;
    private final FileLock ownerLock;
    private long bytes;
    private long entries;
    private boolean closed;

    public ObservationReporterJournal(Path directory, ObservationReport identityTemplate,
                                      int maxEntries, long maxBytes) throws IOException {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        if (maxEntries < 1 || maxBytes < 1) throw new IllegalArgumentException("Positive journal bounds required");
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        identity = identity(identityTemplate);
        Files.createDirectories(this.directory);
        owner = FileChannel.open(this.directory.resolve("owner.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            acquired = owner.tryLock();
            if (acquired == null) throw new IOException("Observation journal already has an owner");
            Path identityPath = this.directory.resolve("identity");
            if (!Files.exists(identityPath)) writeNewForced(identityPath, identity);
            if (Files.size(identityPath) != identity.length
                    || !Arrays.equals(Files.readAllBytes(identityPath), identity)) {
                throw new IOException("Observation journal belongs to another chain, profile or reporter");
            }
            // Existing complete bytes can survive a failed force. Reestablish
            // durability before accepting this identity after a restart.
            try (FileChannel retained = FileChannel.open(identityPath, StandardOpenOption.WRITE);
                 FileChannel parent = FileChannel.open(this.directory, StandardOpenOption.READ)) {
                retained.force(true);
                parent.force(true);
            }
            try (var files = Files.list(this.directory)) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".choice"))
                        .limit((long) maxEntries + 1).toList()) {
                    entries++;
                    bytes = Math.addExact(bytes, Files.size(file));
                }
            }
            if (entries > maxEntries || bytes > maxBytes) throw new IOException("Retained journal exceeds bounds");
            ownerLock = acquired;
        } catch (IOException | RuntimeException failure) {
            try {
                if (acquired != null) acquired.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            try {
                owner.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /**
     * Caller must authenticate the round/profile and supply a current verified logical anchor/height.
     * Repeated Ed25519 signatures of the same durable preimage are safe; supersession is forbidden.
     */
    public synchronized ObservationReport sign(ObservationRound round, ObservationReport unsigned,
                                                long committedHeight, long logicalAnchor,
                                                Function<byte[], byte[]> signer) throws IOException {
        if (closed) throw new IOException("Observation journal is closed");
        if (!Arrays.equals(identity, identity(unsigned)) || !Arrays.equals(unsigned.signature(), new byte[64])
                || !Arrays.equals(unsigned.subscriptionId(), round.subscriptionId())
                || unsigned.roundNumber() != round.roundNumber()
                || !Arrays.equals(unsigned.definitionDigest(), round.definitionDigest())
                || !Arrays.equals(unsigned.membershipDigest(), round.membershipDigest())
                || !Arrays.equals(unsigned.reporterSetDigest(), round.reporterSetDigest())
                || unsigned.freshnessAnchorType() != round.anchorType().code()
                || unsigned.freshnessAnchor() < round.dueAnchor()
                || unsigned.freshnessAnchor() > logicalAnchor || logicalAnchor > round.reportDeadlineAnchor()
                || committedHeight < round.openingHeight() || committedHeight > round.absoluteMaxRoundHeight()
                || round.resultExpiryHeight() != 0) {
            throw new IllegalArgumentException("Signing candidate differs from the authenticated live round");
        }
        byte[] encoded = unsigned.encode();
        ByteBuffer key = ByteBuffer.allocate(40 + unsigned.sourceId().length)
                .put(unsigned.subscriptionId()).putLong(unsigned.roundNumber()).put(unsigned.sourceId());
        Path record = directory.resolve(Hex.encode(ObservationHashes.digest(key.array())) + ".choice");
        if (Files.exists(record)) {
            if (Files.size(record) != encoded.length || !Arrays.equals(Files.readAllBytes(record), encoded)) {
                throw new IOException("Conflicting or incomplete retained observation choice; signing refused");
            }
        } else {
            if (entries >= maxEntries || encoded.length > maxBytes - bytes) {
                throw new IOException("Observation journal capacity exhausted");
            }
            // Failure leaves the path in place. A partial record fails closed on every retry.
            try {
                writeNewForced(record, encoded);
            } catch (IOException failure) {
                // Reopen must recount and revalidate any partially created path.
                try { close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            entries++;
            bytes += encoded.length;
        }
        // A previous process may have stopped after writing complete bytes but
        // before force returned. Equality alone is not proof of durability.
        try (FileChannel retained = FileChannel.open(record, StandardOpenOption.WRITE);
             FileChannel parent = FileChannel.open(directory, StandardOpenOption.READ)) {
            retained.force(true);
            parent.force(true);
        }
        byte[] signature = signer.apply(unsigned.signingDigest());
        return new ObservationReport(unsigned.version(), unsigned.chainGenesisId(), unsigned.chainId(),
                unsigned.consensusProfileDigest(), unsigned.observationProfileDigest(), unsigned.definitionDigest(),
                unsigned.subscriptionId(), unsigned.roundNumber(), unsigned.membershipDigest(),
                unsigned.reporterSetDigest(), unsigned.reporterPublicKey(), unsigned.sourceId(), unsigned.value(),
                unsigned.evidence(), unsigned.sourceVersion(), unsigned.freshnessAnchorType(),
                unsigned.freshnessAnchor(), signature);
    }

    private void writeNewForced(Path path, byte[] value) throws IOException {
        try (FileChannel output = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = ByteBuffer.wrap(value);
            while (bytes.hasRemaining()) output.write(bytes);
            output.force(true);
        }
        try (FileChannel parent = FileChannel.open(directory, StandardOpenOption.READ)) {
            parent.force(true);
        }
    }

    private static byte[] identity(ObservationReport report) {
        byte[] chain = report.chainId().getBytes(StandardCharsets.UTF_8);
        ByteBuffer identity = ByteBuffer.allocate(4 + chain.length + 128).putInt(chain.length).put(chain)
                .put(report.chainGenesisId()).put(report.consensusProfileDigest())
                .put(report.observationProfileDigest()).put(report.reporterPublicKey());
        return ObservationHashes.digest(identity.array());
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { ownerLock.close(); } finally { owner.close(); }
    }
}
