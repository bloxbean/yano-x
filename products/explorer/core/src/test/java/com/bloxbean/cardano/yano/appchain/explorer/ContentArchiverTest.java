package com.bloxbean.cardano.yano.appchain.explorer;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-050 §2.3: bodies are verified against the entry hash before they are stored. */
class ContentArchiverTest {
    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] BODY = "invoice 2026-09".getBytes(StandardCharsets.UTF_8);

    private static ContentArchiver archiver(IndexStore store) throws Exception {
        return new ContentArchiver(store, Files.createTempDirectory("explorer-content-").resolve("content"));
    }

    @Test
    void matchesSha256OrBlake2bAndStoresUnderSha256() throws Exception {
        try (IndexStore store = IndexStore.inMemory()) {
            ContentArchiver archiver = archiver(store);
            IndexStore.ContentRecord sha = archiver.add(BODY, "file:a", HEX.formatHex(ContentArchiver.sha256(BODY)));
            assertThat(sha.status()).isEqualTo(ContentArchiver.MATCHED);
            assertThat(archiver.read(sha.sha256Hex())).contains(BODY);
            assertThat(Files.getPosixFilePermissions(archiver.directory().resolve(sha.sha256Hex() + ".bin")))
                    .containsExactlyInAnyOrder(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
            IndexStore.ContentRecord blake = archiver.add(BODY, "file:b", HEX.formatHex(Blake2bUtil.blake2bHash256(BODY)));
            assertThat(blake.status()).isEqualTo(ContentArchiver.MATCHED);
            assertThat(archiver.forEntryHash(HEX.formatHex(Blake2bUtil.blake2bHash256(BODY)))).isPresent();
            IndexStore.ContentRecord unbound = archiver.add("other".getBytes(StandardCharsets.UTF_8), "file:c", null);
            assertThat(unbound.status()).isEqualTo(ContentArchiver.UNBOUND);
        }
    }

    @Test
    void mismatchIsRecordedAndNotStored() throws Exception {
        try (IndexStore store = IndexStore.inMemory()) {
            ContentArchiver archiver = archiver(store);
            IndexStore.ContentRecord record = archiver.add(BODY, "file:a", "ab".repeat(32));
            assertThat(record.status()).isEqualTo(ContentArchiver.MISMATCH);
            assertThat(archiver.read(record.sha256Hex())).isEmpty();
            assertThat(store.content(record.sha256Hex())).isPresent();
            assertThatThrownBy(() -> archiver.add(new byte[0], "file:e", null))
                    .isInstanceOf(ExplorerException.class);
        }
    }

    @Test
    void fetchRequiresAnAllowListedHttpReference() throws Exception {
        try (IndexStore store = IndexStore.inMemory()) {
            ContentArchiver archiver = archiver(store);
            assertThatThrownBy(() -> archiver.fetch("https://docs.example/a", List.of("https://other.example/"), null))
                    .isInstanceOf(ExplorerException.class)
                    .hasMessageContaining("allow-listed");
            assertThatThrownBy(() -> archiver.fetch("file:///etc/passwd", List.of("file://"), null))
                    .isInstanceOf(ExplorerException.class)
                    .hasMessageContaining("only http and https");
            Path missing = Path.of("/nonexistent");
            assertThat(missing).doesNotExist();
        }
    }
}
