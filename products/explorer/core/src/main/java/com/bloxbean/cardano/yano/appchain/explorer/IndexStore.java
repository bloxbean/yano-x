package com.bloxbean.cardano.yano.appchain.explorer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The derived index (ADR-050 §2.1): SQLite, one transaction per block, identity pinned per chain,
 * rebuildable from a node. Never an authority: nothing here is read by consensus, proof
 * verification, or accounting.
 */
public final class IndexStore implements AutoCloseable {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_PAGE = 200;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> FIELDS = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };

    /** The follower's durable position for one chain. */
    public record Checkpoint(long height, String blockHashHex) { }

    /** One module row as stored, with its position and the level of its block. */
    public record RowRecord(long height, int index, int ordinal, String messageIdHex, String module,
                            String kind, String subject, String op, Map<String, Object> fields,
                            VerificationLevel level) { }

    /** Aggregate of one subject across its rows. */
    public record SubjectSummary(String module, String kind, String subject, long firstHeight,
                                 long lastHeight, long rowCount) { }

    /** One search hit: a block, a message, or a subject. */
    public record SearchHit(String type, String module, String subject, long height, int index,
                            String messageIdHex, String detail) { }

    /** One archived body (ADR-050 §2.3). */
    public record ContentRecord(String sha256Hex, String blake2bHex, long size, String source,
                                String status, String entryHashHex, long storedAt) { }

    private final Connection connection;
    private final boolean inMemory;

    private IndexStore(Connection connection, boolean inMemory) {
        this.connection = connection;
        this.inMemory = inMemory;
    }

    /** Opens (creating when absent, mode 0600) a file-backed index. */
    public static IndexStore open(Path file) {
        Objects.requireNonNull(file, "file");
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
                restrict(parent, "rwx------");
            }
            boolean created = !Files.exists(file);
            if (created) {
                Files.createFile(file);
                restrict(file, "rw-------");
            }
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            IndexStore store = new IndexStore(connection, false);
            store.initialize();
            return store;
        } catch (IOException | SQLException failure) {
            throw new ExplorerException(ExplorerException.Error.UNAVAILABLE,
                    "cannot open index " + file + ": " + failure.getMessage(), failure);
        }
    }

    /** A throwaway in-memory index for tests and one-shot commands. */
    public static IndexStore inMemory() {
        try {
            Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
            IndexStore store = new IndexStore(connection, true);
            store.initialize();
            return store;
        } catch (SQLException failure) {
            throw new ExplorerException(ExplorerException.Error.UNAVAILABLE,
                    "cannot open in-memory index: " + failure.getMessage(), failure);
        }
    }

    public boolean isInMemory() {
        return inMemory;
    }

    private void initialize() throws SQLException {
        connection.setAutoCommit(true);
        try (Statement statement = connection.createStatement()) {
            if (!inMemory) statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("CREATE TABLE IF NOT EXISTS schema_version(version INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS chains("
                    + "chain_id TEXT PRIMARY KEY, application_id TEXT NOT NULL, profile TEXT NOT NULL,"
                    + " state_genesis_id TEXT NOT NULL, manifest_digest TEXT NOT NULL,"
                    + " identity_digest TEXT NOT NULL, checkpoint_height INTEGER NOT NULL DEFAULT 0,"
                    + " checkpoint_block_hash TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS blocks("
                    + "chain_id TEXT NOT NULL, height INTEGER NOT NULL, block_hash TEXT NOT NULL,"
                    + " prev_hash TEXT NOT NULL, timestamp INTEGER NOT NULL, messages_root TEXT NOT NULL,"
                    + " state_root TEXT NOT NULL, proposer TEXT NOT NULL, message_count INTEGER NOT NULL,"
                    + " cert_signatures INTEGER NOT NULL, level TEXT NOT NULL, canonical_hex TEXT NOT NULL,"
                    + " members_json TEXT NOT NULL, threshold INTEGER NOT NULL, anchor_json TEXT NOT NULL,"
                    + " block_record_proof_json TEXT NOT NULL, diagnostic TEXT NOT NULL,"
                    + " PRIMARY KEY(chain_id, height))");
            statement.execute("CREATE TABLE IF NOT EXISTS messages("
                    + "chain_id TEXT NOT NULL, height INTEGER NOT NULL, idx INTEGER NOT NULL,"
                    + " message_id TEXT NOT NULL, topic TEXT NOT NULL, sender TEXT NOT NULL,"
                    + " sender_seq INTEGER NOT NULL, expires_at INTEGER NOT NULL, body_hex TEXT NOT NULL,"
                    + " auth_scheme INTEGER NOT NULL, auth_proof_hex TEXT NOT NULL, state TEXT NOT NULL,"
                    + " PRIMARY KEY(chain_id, height, idx))");
            statement.execute("CREATE INDEX IF NOT EXISTS messages_by_id ON messages(chain_id, message_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS messages_by_topic ON messages(chain_id, topic, height, idx)");
            statement.execute("CREATE INDEX IF NOT EXISTS messages_by_sender ON messages(chain_id, sender, height, idx)");
            statement.execute("CREATE TABLE IF NOT EXISTS subject_rows("
                    + "chain_id TEXT NOT NULL, height INTEGER NOT NULL, idx INTEGER NOT NULL,"
                    + " ordinal INTEGER NOT NULL, message_id TEXT NOT NULL, module TEXT NOT NULL,"
                    + " kind TEXT NOT NULL, subject TEXT NOT NULL, op TEXT NOT NULL, fields_json TEXT NOT NULL,"
                    + " PRIMARY KEY(chain_id, height, idx, ordinal))");
            statement.execute("CREATE INDEX IF NOT EXISTS rows_by_subject ON subject_rows("
                    + "chain_id, module, subject, height, idx, ordinal)");
            statement.execute("CREATE TABLE IF NOT EXISTS subjects("
                    + "chain_id TEXT NOT NULL, module TEXT NOT NULL, kind TEXT NOT NULL, subject TEXT NOT NULL,"
                    + " first_height INTEGER NOT NULL, last_height INTEGER NOT NULL, row_count INTEGER NOT NULL,"
                    + " PRIMARY KEY(chain_id, module, subject))");
            statement.execute("CREATE TABLE IF NOT EXISTS content("
                    + "sha256 TEXT PRIMARY KEY, blake2b TEXT NOT NULL, size INTEGER NOT NULL,"
                    + " source TEXT NOT NULL, status TEXT NOT NULL, entry_hash TEXT NOT NULL,"
                    + " stored_at INTEGER NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS content_by_entry ON content(entry_hash)");
            statement.execute("CREATE INDEX IF NOT EXISTS content_by_blake2b ON content(blake2b)");
            try (ResultSet rows = statement.executeQuery("SELECT version FROM schema_version")) {
                if (rows.next()) {
                    int version = rows.getInt(1);
                    if (version != SCHEMA_VERSION) {
                        throw new ExplorerException(ExplorerException.Error.IDENTITY_MISMATCH,
                                "index schema version " + version + " is not " + SCHEMA_VERSION
                                        + "; rebuild the index");
                    }
                    return;
                }
            }
            statement.execute("INSERT INTO schema_version(version) VALUES (" + SCHEMA_VERSION + ")");
        }
    }

    // --- identity and checkpoint ---

    public synchronized List<String> chains() {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT chain_id FROM chains ORDER BY chain_id");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) ids.add(rows.getString(1));
        } catch (SQLException failure) {
            throw wrap(failure);
        }
        return ids;
    }

    public synchronized Optional<IndexIdentity> identity(String chainId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT application_id, profile, state_genesis_id, manifest_digest FROM chains WHERE chain_id = ?")) {
            statement.setString(1, chainId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new IndexIdentity(chainId, rows.getString(1), rows.getString(2),
                        rows.getString(3), rows.getString(4)));
            }
        } catch (SQLException failure) {
            throw wrap(failure);
        }
    }

    /** Pins the identity on first use; refuses an index that belongs to another identity. */
    public synchronized void pinIdentity(IndexIdentity identity) {
        Optional<IndexIdentity> existing = identity(identity.chainId());
        if (existing.isPresent()) {
            if (!existing.get().digest().equals(identity.digest())) {
                throw new ExplorerException(ExplorerException.Error.IDENTITY_MISMATCH,
                        "index for " + identity.chainId() + " was built for another identity ("
                                + existing.get().applicationId() + ", genesis "
                                + existing.get().stateGenesisIdHex() + "); rebuild it");
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO chains(chain_id, application_id, profile, state_genesis_id, manifest_digest,"
                        + " identity_digest, checkpoint_height, checkpoint_block_hash, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 0, '', ?)")) {
            statement.setString(1, identity.chainId());
            statement.setString(2, identity.applicationId());
            statement.setString(3, identity.profile());
            statement.setString(4, identity.stateGenesisIdHex());
            statement.setString(5, identity.manifestDigestHex());
            statement.setString(6, identity.digest());
            statement.setLong(7, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw wrap(failure);
        }
    }

    public synchronized Checkpoint checkpoint(String chainId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT checkpoint_height, checkpoint_block_hash FROM chains WHERE chain_id = ?")) {
            statement.setString(1, chainId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return new Checkpoint(0, "");
                return new Checkpoint(rows.getLong(1), rows.getString(2));
            }
        } catch (SQLException failure) {
            throw wrap(failure);
        }
    }

    /** Drops every row of one chain, keeping its pinned identity, for a rebuild. */
    public synchronized void reset(String chainId) {
        try {
            connection.setAutoCommit(false);
            for (String table : List.of("blocks", "messages", "subject_rows", "subjects")) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE chain_id = ?")) {
                    statement.setString(1, chainId);
                    statement.executeUpdate();
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE chains SET checkpoint_height = 0, checkpoint_block_hash = '', updated_at = ?"
                            + " WHERE chain_id = ?")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, chainId);
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException failure) {
            rollback();
            throw wrap(failure);
        } finally {
            autoCommit();
        }
    }

    // --- write ---

    /**
     * Writes one block, its messages, and its module rows, and advances the checkpoint, in one
     * transaction. The block must be the checkpoint's successor.
     */
    public synchronized void writeBlock(String chainId, IndexedBlock block, List<IndexedMessage> messages,
                                        List<List<SubjectRow>> rowsPerMessage) {
        Checkpoint checkpoint = checkpoint(chainId);
        if (block.height() != checkpoint.height() + 1) {
            throw new ExplorerException(ExplorerException.Error.INVALID, "block " + block.height()
                    + " is not the successor of checkpoint " + checkpoint.height());
        }
        if (messages.size() != rowsPerMessage.size()) {
            throw new IllegalArgumentException("one row list per message is required");
        }
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO blocks(chain_id, height, block_hash, prev_hash, timestamp, messages_root,"
                            + " state_root, proposer, message_count, cert_signatures, level, canonical_hex,"
                            + " members_json, threshold, anchor_json, block_record_proof_json, diagnostic)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, chainId);
                statement.setLong(2, block.height());
                statement.setString(3, block.blockHashHex());
                statement.setString(4, block.prevHashHex());
                statement.setLong(5, block.timestamp());
                statement.setString(6, block.messagesRootHex());
                statement.setString(7, block.stateRootHex());
                statement.setString(8, block.proposerHex());
                statement.setInt(9, block.messageCount());
                statement.setInt(10, block.certSignatures());
                statement.setString(11, block.level().name());
                statement.setString(12, block.canonicalHex());
                statement.setString(13, JSON.writeValueAsString(block.memberKeysHex()));
                statement.setInt(14, block.threshold());
                statement.setString(15, block.anchorJson());
                statement.setString(16, block.blockRecordProofJson());
                statement.setString(17, block.diagnostic());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO messages(chain_id, height, idx, message_id, topic, sender, sender_seq,"
                            + " expires_at, body_hex, auth_scheme, auth_proof_hex, state)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (IndexedMessage message : messages) {
                    statement.setString(1, chainId);
                    statement.setLong(2, message.height());
                    statement.setInt(3, message.index());
                    statement.setString(4, message.messageIdHex());
                    statement.setString(5, message.topic());
                    statement.setString(6, message.senderHex());
                    statement.setLong(7, message.senderSeq());
                    statement.setLong(8, message.expiresAt());
                    statement.setString(9, message.bodyHex());
                    statement.setInt(10, message.authScheme());
                    statement.setString(11, message.authProofHex());
                    statement.setString(12, message.state().name());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO subject_rows(chain_id, height, idx, ordinal, message_id, module, kind,"
                            + " subject, op, fields_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                 PreparedStatement upsert = connection.prepareStatement(
                         "INSERT INTO subjects(chain_id, module, kind, subject, first_height, last_height, row_count)"
                                 + " VALUES (?, ?, ?, ?, ?, ?, 1) ON CONFLICT(chain_id, module, subject)"
                                 + " DO UPDATE SET last_height = excluded.last_height, row_count = row_count + 1")) {
                for (int m = 0; m < messages.size(); m++) {
                    IndexedMessage message = messages.get(m);
                    List<SubjectRow> rows = rowsPerMessage.get(m);
                    for (int ordinal = 0; ordinal < rows.size(); ordinal++) {
                        SubjectRow row = rows.get(ordinal);
                        insert.setString(1, chainId);
                        insert.setLong(2, message.height());
                        insert.setInt(3, message.index());
                        insert.setInt(4, ordinal);
                        insert.setString(5, message.messageIdHex());
                        insert.setString(6, row.module());
                        insert.setString(7, row.kind());
                        insert.setString(8, row.subject());
                        insert.setString(9, row.op());
                        insert.setString(10, JSON.writeValueAsString(row.fields()));
                        insert.addBatch();
                        upsert.setString(1, chainId);
                        upsert.setString(2, row.module());
                        upsert.setString(3, row.kind());
                        upsert.setString(4, row.subject());
                        upsert.setLong(5, message.height());
                        upsert.setLong(6, message.height());
                        upsert.addBatch();
                    }
                }
                insert.executeBatch();
                upsert.executeBatch();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE chains SET checkpoint_height = ?, checkpoint_block_hash = ?, updated_at = ?"
                            + " WHERE chain_id = ?")) {
                statement.setLong(1, block.height());
                statement.setString(2, block.blockHashHex());
                statement.setLong(3, System.currentTimeMillis());
                statement.setString(4, chainId);
                if (statement.executeUpdate() != 1) {
                    throw new ExplorerException(ExplorerException.Error.IDENTITY_MISMATCH,
                            "chain " + chainId + " has no pinned identity");
                }
            }
            connection.commit();
        } catch (SQLException | IOException failure) {
            rollback();
            throw wrap(failure);
        } catch (RuntimeException failure) {
            rollback();
            throw failure;
        } finally {
            autoCommit();
        }
    }

    // --- reads ---

    public synchronized Optional<IndexedBlock> block(String chainId, long height) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM blocks WHERE chain_id = ? AND height = ?")) {
            statement.setString(1, chainId);
            statement.setLong(2, height);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readBlock(rows)) : Optional.empty();
            }
        } catch (SQLException | IOException failure) {
            throw wrap(failure);
        }
    }

    /** Blocks ascending from {@code from}; {@code from <= 0} means the newest page. */
    public synchronized List<IndexedBlock> blocks(String chainId, long from, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_PAGE));
        long start = from;
        if (start <= 0) start = Math.max(1, checkpoint(chainId).height() - size + 1);
        List<IndexedBlock> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM blocks WHERE chain_id = ? AND height >= ? ORDER BY height LIMIT ?")) {
            statement.setString(1, chainId);
            statement.setLong(2, start);
            statement.setInt(3, size);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(readBlock(rows));
            }
        } catch (SQLException | IOException failure) {
            throw wrap(failure);
        }
        return result;
    }

    public synchronized List<IndexedMessage> messages(String chainId, long height) {
        List<IndexedMessage> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM messages WHERE chain_id = ? AND height = ? ORDER BY idx")) {
            statement.setString(1, chainId);
            statement.setLong(2, height);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(readMessage(rows));
            }
        } catch (SQLException failure) {
            throw wrap(failure);
        }
        return result;
    }

    public synchronized Optional<IndexedMessage> message(String chainId, String messageIdHex) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM messages WHERE chain_id = ? AND message_id = ? ORDER BY height, idx LIMIT 1")) {
            statement.setString(1, chainId);
            statement.setString(2, messageIdHex);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readMessage(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw wrap(failure);
        }
    }

    /** Messages on a topic or from a sender, ascending; either filter may be empty. */
    public synchronized List<IndexedMessage> messages(String chainId, String topic, String senderHex,
                                                      long fromHeight, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_PAGE));
        StringBuilder sql = new StringBuilder("SELECT * FROM messages WHERE chain_id = ? AND height >= ?");
        if (!topic.isEmpty()) sql.append(" AND topic = ?");
        if (!senderHex.isEmpty()) sql.append(" AND sender = ?");
        sql.append(" ORDER BY height, idx LIMIT ?");
        List<IndexedMessage> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int p = 1;
            statement.setString(p++, chainId);
            statement.setLong(p++, Math.max(1, fromHeight));
            if (!topic.isEmpty()) statement.setString(p++, topic);
            if (!senderHex.isEmpty()) statement.setString(p++, senderHex);
            statement.setInt(p, size);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(readMessage(rows));
            }
        } catch (SQLException failure) {
            throw wrap(failure);
        }
        return result;
    }

    public synchronized List<RowRecord> rowsOfMessage(String chainId, long height, int index) {
        return queryRows("SELECT r.*, b.level FROM subject_rows r JOIN blocks b ON b.chain_id = r.chain_id"
                + " AND b.height = r.height WHERE r.chain_id = ? AND r.height = ? AND r.idx = ?"
                + " ORDER BY r.ordinal", chainId, height, index, null, null, 0, 10_000);
    }

    /** Every row of one subject in finalization order. */
    public synchronized List<RowRecord> subjectRows(String chainId, String module, String subject, int limit) {
        return queryRows("SELECT r.*, b.level FROM subject_rows r JOIN blocks b ON b.chain_id = r.chain_id"
                + " AND b.height = r.height WHERE r.chain_id = ? AND r.module = ? AND r.subject = ?"
                + " ORDER BY r.height, r.idx, r.ordinal LIMIT ?", chainId, -1, -1, module, subject, 0,
                Math.max(1, Math.min(limit, 10_000)));
    }

    private List<RowRecord> queryRows(String sql, String chainId, long height, int index, String module,
                                      String subject, int unused, int limit) {
        List<RowRecord> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, chainId);
            if (module == null) {
                statement.setLong(2, height);
                statement.setInt(3, index);
            } else {
                statement.setString(2, module);
                statement.setString(3, subject);
                statement.setInt(4, limit);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new RowRecord(rows.getLong("height"), rows.getInt("idx"), rows.getInt("ordinal"),
                            rows.getString("message_id"), rows.getString("module"), rows.getString("kind"),
                            rows.getString("subject"), rows.getString("op"),
                            JSON.readValue(rows.getString("fields_json"), FIELDS),
                            VerificationLevel.valueOf(rows.getString("level"))));
                }
            }
        } catch (SQLException | IOException failure) {
            throw wrap(failure);
        }
        return result;
    }

    /** Subjects of one module (or all when empty) whose id starts with the prefix, by recency. */
    public synchronized List<SubjectSummary> subjects(String chainId, String module, String prefix, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_PAGE));
        StringBuilder sql = new StringBuilder("SELECT * FROM subjects WHERE chain_id = ?");
        if (!module.isEmpty()) sql.append(" AND module = ?");
        if (!prefix.isEmpty()) sql.append(" AND subject LIKE ? ESCAPE '\\'");
        sql.append(" ORDER BY last_height DESC, subject LIMIT ?");
        List<SubjectSummary> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int p = 1;
            statement.setString(p++, chainId);
            if (!module.isEmpty()) statement.setString(p++, module);
            if (!prefix.isEmpty()) statement.setString(p++, escapeLike(prefix) + "%");
            statement.setInt(p, size);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new SubjectSummary(rows.getString("module"), rows.getString("kind"),
                            rows.getString("subject"), rows.getLong("first_height"),
                            rows.getLong("last_height"), rows.getLong("row_count")));
                }
            }
        } catch (SQLException failure) {
            throw wrap(failure);
        }
        return result;
    }

    public synchronized Map<String, Long> topics(String chainId) {
        Map<String, Long> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT topic, COUNT(*) FROM messages WHERE chain_id = ? GROUP BY topic ORDER BY topic")) {
            statement.setString(1, chainId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.put(rows.getString(1), rows.getLong(2));
            }
        } catch (SQLException failure) {
            throw wrap(failure);
        }
        return result;
    }

    public synchronized Map<String, Long> levels(String chainId) {
        Map<String, Long> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT level, COUNT(*) FROM blocks WHERE chain_id = ? GROUP BY level ORDER BY level")) {
            statement.setString(1, chainId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.put(rows.getString(1), rows.getLong(2));
            }
        } catch (SQLException failure) {
            throw wrap(failure);
        }
        return result;
    }

    /**
     * Free-text search (ADR-050 §2.4): a message id, a height, a topic, a sender prefix, or a
     * subject prefix. Bounded input, bounded output.
     */
    public synchronized List<SearchHit> search(String chainId, String query, int limit) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty() || q.length() > 256) return List.of();
        int size = Math.max(1, Math.min(limit, MAX_PAGE));
        List<SearchHit> hits = new ArrayList<>();
        try {
            if (q.matches("[0-9]{1,18}")) {
                long height = Long.parseLong(q);
                block(chainId, height).ifPresent(block -> hits.add(new SearchHit("block", "", "",
                        height, -1, "", block.messageCount() + " message(s), " + block.level())));
            }
            if (q.matches("[0-9a-f]{64}")) {
                message(chainId, q).ifPresent(message -> hits.add(new SearchHit("message", "", "",
                        message.height(), message.index(), message.messageIdHex(), message.topic())));
            }
            for (SubjectSummary subject : subjects(chainId, "", q, size)) {
                hits.add(new SearchHit("subject", subject.module(), subject.subject(), subject.lastHeight(),
                        -1, "", subject.rowCount() + " row(s) since height " + subject.firstHeight()));
            }
            if (hits.size() < size) {
                for (IndexedMessage message : messages(chainId, q, "", 1, size - hits.size())) {
                    hits.add(new SearchHit("message", "", "", message.height(), message.index(),
                            message.messageIdHex(), "topic " + message.topic()));
                }
            }
            if (hits.size() < size && q.matches("[0-9a-f]{8,64}")) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT * FROM messages WHERE chain_id = ? AND sender LIKE ? ORDER BY height, idx LIMIT ?")) {
                    statement.setString(1, chainId);
                    statement.setString(2, q + "%");
                    statement.setInt(3, size - hits.size());
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            IndexedMessage message = readMessage(rows);
                            hits.add(new SearchHit("message", "", "", message.height(), message.index(),
                                    message.messageIdHex(), "sender " + message.senderHex()));
                        }
                    }
                }
            }
        } catch (SQLException failure) {
            throw wrap(failure);
        }
        return hits.size() > size ? hits.subList(0, size) : hits;
    }

    // --- content ---

    public synchronized void putContent(ContentRecord record) {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO content(sha256, blake2b, size, source, status, entry_hash, stored_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(sha256) DO UPDATE SET status = excluded.status,"
                        + " entry_hash = excluded.entry_hash, source = excluded.source, stored_at = excluded.stored_at")) {
            statement.setString(1, record.sha256Hex());
            statement.setString(2, record.blake2bHex());
            statement.setLong(3, record.size());
            statement.setString(4, record.source());
            statement.setString(5, record.status());
            statement.setString(6, record.entryHashHex());
            statement.setLong(7, record.storedAt());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw wrap(failure);
        }
    }

    public synchronized Optional<ContentRecord> content(String sha256Hex) {
        return queryContent("SELECT * FROM content WHERE sha256 = ?", sha256Hex);
    }

    /** The archived body whose SHA-256 or Blake2b-256 equals the entry hash. */
    public synchronized Optional<ContentRecord> contentForEntryHash(String entryHashHex) {
        Optional<ContentRecord> bySha = queryContent("SELECT * FROM content WHERE sha256 = ?", entryHashHex);
        if (bySha.isPresent()) return bySha;
        return queryContent("SELECT * FROM content WHERE blake2b = ?", entryHashHex);
    }

    private Optional<ContentRecord> queryContent(String sql, String value) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new ContentRecord(rows.getString("sha256"), rows.getString("blake2b"),
                        rows.getLong("size"), rows.getString("source"), rows.getString("status"),
                        rows.getString("entry_hash"), rows.getLong("stored_at")));
            }
        } catch (SQLException failure) {
            throw wrap(failure);
        }
    }

    // --- export ---

    /**
     * Deterministic export (ADR-050 §2.1): blocks, messages, and rows in finalization order as
     * JSON lines, without wall-clock columns or canonical bytes. Two indexes built from the same
     * node produce identical exports.
     */
    public synchronized void export(String chainId, Appendable out) {
        try {
            long tip = checkpoint(chainId).height();
            for (long height = 1; height <= tip; height++) {
                IndexedBlock block = block(chainId, height).orElseThrow();
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("type", "block");
                view.put("height", block.height());
                view.put("blockHash", block.blockHashHex());
                view.put("prevHash", block.prevHashHex());
                view.put("timestamp", block.timestamp());
                view.put("messagesRoot", block.messagesRootHex());
                view.put("stateRoot", block.stateRootHex());
                view.put("messageCount", block.messageCount());
                view.put("level", block.level().name());
                out.append(JSON.writeValueAsString(view)).append('\n');
                for (IndexedMessage message : messages(chainId, height)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", "message");
                    m.put("height", message.height());
                    m.put("index", message.index());
                    m.put("messageId", message.messageIdHex());
                    m.put("topic", message.topic());
                    m.put("sender", message.senderHex());
                    m.put("senderSeq", message.senderSeq());
                    m.put("bodyHex", message.bodyHex());
                    m.put("state", message.state().name());
                    out.append(JSON.writeValueAsString(m)).append('\n');
                    for (RowRecord row : rowsOfMessage(chainId, height, message.index())) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("type", "row");
                        r.put("height", row.height());
                        r.put("index", row.index());
                        r.put("ordinal", row.ordinal());
                        r.put("module", row.module());
                        r.put("kind", row.kind());
                        r.put("subject", row.subject());
                        r.put("op", row.op());
                        r.put("fields", row.fields());
                        out.append(JSON.writeValueAsString(r)).append('\n');
                    }
                }
            }
        } catch (IOException failure) {
            throw new ExplorerException(ExplorerException.Error.UNAVAILABLE,
                    "export failed: " + failure.getMessage(), failure);
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // best effort
        }
    }

    // --- helpers ---

    private IndexedBlock readBlock(ResultSet rows) throws SQLException, IOException {
        return new IndexedBlock(rows.getLong("height"), rows.getString("block_hash"), rows.getString("prev_hash"),
                rows.getLong("timestamp"), rows.getString("messages_root"), rows.getString("state_root"),
                rows.getString("proposer"), rows.getInt("message_count"), rows.getInt("cert_signatures"),
                VerificationLevel.valueOf(rows.getString("level")), rows.getString("canonical_hex"),
                JSON.readValue(rows.getString("members_json"), STRINGS), rows.getInt("threshold"),
                rows.getString("anchor_json"), rows.getString("block_record_proof_json"),
                rows.getString("diagnostic"));
    }

    private static IndexedMessage readMessage(ResultSet rows) throws SQLException {
        return new IndexedMessage(rows.getLong("height"), rows.getInt("idx"), rows.getString("message_id"),
                rows.getString("topic"), rows.getString("sender"), rows.getLong("sender_seq"),
                rows.getLong("expires_at"), rows.getString("body_hex"), rows.getInt("auth_scheme"),
                rows.getString("auth_proof_hex"), IndexedMessage.State.valueOf(rows.getString("state")));
    }

    private void rollback() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // the transaction is already gone
        }
    }

    private void autoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // connection closed
        }
    }

    private static ExplorerException wrap(Exception failure) {
        return new ExplorerException(ExplorerException.Error.UNAVAILABLE,
                "index database failure: " + failure.getMessage(), failure);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static void restrict(Path path, String permissions) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException | IOException ignored) {
            // non-POSIX file system
        }
    }
}
