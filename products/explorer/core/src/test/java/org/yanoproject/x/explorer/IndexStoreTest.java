package org.yanoproject.x.explorer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-050 §2.1: identity pinning, successor-only writes, search, content, and export order. */
class IndexStoreTest {
    private static final String CHAIN = "store-test";

    private static IndexedBlock block(long height, String prev) {
        return new IndexedBlock(height, "1".repeat(64).substring(0, 62) + String.format("%02d", height), prev,
                1_000L + height, "22".repeat(32), "33".repeat(32), "44".repeat(32), 1, 2,
                VerificationLevel.VERIFIED_DECLARED, "", List.of("aa".repeat(32)), 1, "", "", "");
    }

    private static IndexedMessage message(long height, String topic, int last) {
        return new IndexedMessage(height, 0, "5".repeat(62) + String.format("%02d", last), topic, "aa".repeat(32),
                height, 0, "80", 0, "", IndexedMessage.State.FULL);
    }

    private static IndexStore pinned() {
        IndexStore store = IndexStore.inMemory();
        store.pinIdentity(new IndexIdentity(CHAIN, "doc-trail", "mpf-blake2b256-v1", "ab".repeat(32), ""));
        return store;
    }

    @Test
    void pinsIdentityAndRefusesAnother() {
        try (IndexStore store = pinned()) {
            store.pinIdentity(new IndexIdentity(CHAIN, "doc-trail", "mpf-blake2b256-v1", "ab".repeat(32), ""));
            assertThatThrownBy(() -> store.pinIdentity(new IndexIdentity(CHAIN, "kv-registry", "mpf-blake2b256-v1",
                    "ab".repeat(32), "")))
                    .isInstanceOf(ExplorerException.class)
                    .hasMessageContaining("another identity");
            assertThat(store.chains()).containsExactly(CHAIN);
        }
    }

    @Test
    void writesOnlySuccessorBlocksAndKeepsRowsWithTheirMessage() {
        try (IndexStore store = pinned()) {
            SubjectRow row = new SubjectRow("doc-trail", "entity", "case-1", "APPEND", Map.of("entryHashHex", "aa"));
            store.writeBlock(CHAIN, block(1, "00".repeat(32)), List.of(message(1, "doc-trail.command.v1", 1)), List.of(List.of(row)));
            assertThatThrownBy(() -> store.writeBlock(CHAIN, block(3, ""), List.of(), List.of()))
                    .isInstanceOf(ExplorerException.class)
                    .hasMessageContaining("successor");
            assertThat(store.checkpoint(CHAIN).height()).isEqualTo(1);
            assertThat(store.rowsOfMessage(CHAIN, 1, 0)).hasSize(1);
            assertThat(store.subjectRows(CHAIN, "doc-trail", "case-1", 10)).hasSize(1);
            assertThat(store.subjects(CHAIN, "", "case", 10)).hasSize(1);
            assertThat(store.topics(CHAIN)).containsEntry("doc-trail.command.v1", 1L);
            store.writeBlock(CHAIN, block(2, block(1, "").blockHashHex()), List.of(message(2, "other.topic", 2)), List.of(List.of()));
            assertThat(store.blocks(CHAIN, 0, 10)).hasSize(2);
            assertThat(store.messages(CHAIN, "other.topic", "", 1, 10)).hasSize(1);
            assertThat(store.search(CHAIN, "2", 10)).anyMatch(hit -> hit.type().equals("block"));
            assertThat(store.search(CHAIN, "5".repeat(62) + "02", 10)).anyMatch(hit -> hit.type().equals("message"));
            assertThat(store.search(CHAIN, "aaaaaaaaaa", 10)).anyMatch(hit -> hit.detail().startsWith("sender"));
            assertThat(store.search(CHAIN, "x".repeat(300), 10)).isEmpty();
            StringBuilder export = new StringBuilder();
            store.export(CHAIN, export);
            List<String> lines = export.toString().lines().toList();
            assertThat(lines).hasSize(5);
            assertThat(lines.get(0)).contains("\"type\":\"block\"").contains("\"height\":1");
            assertThat(lines.get(2)).contains("\"type\":\"row\"");
            assertThat(export.toString()).doesNotContain("updated_at").doesNotContain("canonical");
            store.reset(CHAIN);
            assertThat(store.checkpoint(CHAIN).height()).isZero();
            assertThat(store.identity(CHAIN)).isPresent();
        }
    }

    @Test
    void contentRecordsAreFoundByEitherDigest() {
        try (IndexStore store = pinned()) {
            store.putContent(new IndexStore.ContentRecord("11".repeat(32), "22".repeat(32), 3, "test",
                    ContentArchiver.MATCHED, "22".repeat(32), 1));
            assertThat(store.content("11".repeat(32))).isPresent();
            assertThat(store.contentForEntryHash("22".repeat(32))).isPresent();
            assertThat(store.contentForEntryHash("11".repeat(32))).isPresent();
            assertThat(store.contentForEntryHash("33".repeat(32))).isEmpty();
        }
    }

    @Test
    void fileBackedIndexIsPrivateAndReopens() throws Exception {
        Path directory = Files.createTempDirectory("explorer-store-");
        Path file = directory.resolve("nested").resolve("explorer.db");
        try (IndexStore store = IndexStore.open(file)) {
            store.pinIdentity(new IndexIdentity(CHAIN, "doc-trail", "mpf-blake2b256-v1", "ab".repeat(32), ""));
            assertThat(store.isInMemory()).isFalse();
        }
        assertThat(Files.getPosixFilePermissions(file)).containsExactlyInAnyOrder(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
        try (IndexStore store = IndexStore.open(file)) {
            assertThat(store.identity(CHAIN)).isPresent();
        }
    }
}
