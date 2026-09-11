package org.yanoproject.x.client;

import com.bloxbean.cardano.yano.api.appchain.observation.ObservationAnchorType;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationFixedPoint;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReport;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReporterMode;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationRound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationReporterJournalTest {
    @Test
    void abruptExitBeforeAndAfterSigningRetainsExclusiveChoice(@TempDir Path directory) throws Exception {
        for (String boundary : List.of("BEFORE_SIGN", "AFTER_SIGN")) {
            Path journalDirectory = directory.resolve(boundary);
            Path output = directory.resolve(boundary + ".log");
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("yano.x.test.runtime-classpath"), CrashProbe.class.getName(),
                    journalDirectory.toString(), boundary)
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try {
                assertThat(child.waitFor(30, TimeUnit.SECONDS)).isTrue();
                assertThat(child.exitValue()).as(Files.readString(output)).isEqualTo(87);
            } finally {
                if (child.isAlive()) child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
            ObservationReport original = report(500000, 1);
            try (ObservationReporterJournal journal =
                         new ObservationReporterJournal(journalDirectory, original, 10, 10000)) {
                assertThatThrownBy(() -> journal.sign(round(), report(501000, 1), 2, 2, digest -> {
                    throw new AssertionError("Conflicting candidate must never reach signer");
                })).isInstanceOf(IOException.class);
                assertThat(journal.sign(round(), original, 2, 2, digest -> new byte[64]).signingDigest())
                        .isEqualTo(original.signingDigest());
            }
        }
    }

    public static class CrashProbe {
        public static void main(String[] args) throws Exception {
            ObservationReport original = report(500000, 1);
            try (ObservationReporterJournal journal =
                         new ObservationReporterJournal(Path.of(args[0]), original, 10, 10000)) {
                journal.sign(round(), original, 2, 2, digest -> {
                    if ("BEFORE_SIGN".equals(args[1])) Runtime.getRuntime().halt(87);
                    return new byte[64];
                });
                Runtime.getRuntime().halt(87);
            }
        }
    }

    @Test
    void signerFailureAndRestartNeverPermitChangingTheChoice(@TempDir Path directory) throws Exception {
        ObservationReport original = report(500000, 1);
        AtomicInteger signs = new AtomicInteger();
        try (ObservationReporterJournal journal = new ObservationReporterJournal(directory, original, 10, 10000)) {
            assertThatThrownBy(() -> journal.sign(round(), original, 2, 2, digest -> {
                signs.incrementAndGet();
                throw new IllegalStateException("signer temporarily unavailable");
            })).isInstanceOf(IllegalStateException.class);
        }
        try (ObservationReporterJournal reopened = new ObservationReporterJournal(directory, original, 10, 10000)) {
            assertThatThrownBy(() -> reopened.sign(round(), report(501000, 1), 2, 2, digest -> {
                signs.incrementAndGet();
                return new byte[64];
            })).isInstanceOf(IOException.class);
            assertThat(signs).hasValue(1);
            ObservationReport signed = reopened.sign(round(), original, 2, 2, digest -> new byte[64]);
            assertThat(signed.signingDigest()).isEqualTo(original.signingDigest());
        }
    }

    @Test
    void ownerIdentityCapacityAndClosedRoundFailClosed(@TempDir Path directory) throws Exception {
        ObservationReport original = report(500000, 1);
        try (ObservationReporterJournal journal = new ObservationReporterJournal(directory, original, 1, 10000)) {
            assertThatThrownBy(() -> new ObservationReporterJournal(directory, original, 1, 10000))
                    .isInstanceOf(Exception.class);
            journal.sign(round(), original, 2, 2, digest -> new byte[64]);
            assertThatThrownBy(() -> journal.sign(round(), report(500000, 2), 2, 2, digest -> new byte[64]))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> journal.sign(round(), original, 5, 5, digest -> new byte[64]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        ObservationReport wrongIdentity = new ObservationReport(1, id(99), "chain", id(3), id(4), id(5),
                id(6), 0, id(7), id(8), id(9), id(1), new byte[]{1}, new byte[0], new byte[]{1}, 0, 2,
                new byte[64]);
        assertThatThrownBy(() -> new ObservationReporterJournal(directory, wrongIdentity, 10, 10000))
                .isInstanceOf(IOException.class);
    }

    @Test
    void incompleteRetainedChoiceNeverInvokesSigner(@TempDir Path directory) throws Exception {
        ObservationReport original = report(500000, 1);
        try (ObservationReporterJournal journal = new ObservationReporterJournal(directory, original, 10, 10000)) {
            journal.sign(round(), original, 2, 2, digest -> new byte[64]);
        }
        try (var files = Files.list(directory)) {
            Path choice = files.filter(file -> file.toString().endsWith(".choice")).findFirst().orElseThrow();
            Files.write(choice, new byte[]{1, 2});
        }
        try (ObservationReporterJournal journal = new ObservationReporterJournal(directory, original, 10, 10000)) {
            assertThatThrownBy(() -> journal.sign(round(), original, 2, 2, digest -> {
                throw new AssertionError("Must not sign incomplete durable state");
            })).isInstanceOf(IOException.class);
        }
    }

    static ObservationReport report(long units, int source) {
        return new ObservationReport(1, id(2), "chain", id(3), id(4), id(5), id(6), 0,
                id(7), id(8), id(9), id(source), new ObservationFixedPoint(BigInteger.valueOf(units), 6).encode(),
                new byte[0], new byte[]{1}, 0, 2, new byte[64]);
    }

    private static ObservationRound round() {
        return new ObservationRound(1, id(6), 0, ObservationAnchorType.APP_HEIGHT, 2, 2, 4, 3, 20, 0,
                id(5), id(10), 0, id(7), 1, 1, 0, ObservationReporterMode.EXTERNAL_REPORTERS,
                id(8), 5, 1, 4, id(11), id(12));
    }

    private static byte[] id(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
