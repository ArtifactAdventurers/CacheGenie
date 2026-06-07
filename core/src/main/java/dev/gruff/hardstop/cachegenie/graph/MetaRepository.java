package dev.gruff.hardstop.cachegenie.graph;

import dev.gruff.hardstop.cachegenie.MavenMetaData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persists CacheGenie discovery metadata ({@link MavenMetaData}) into the same
 * DuckDB database used for the dependency graph ({@code ~/.m2/cachegenie/graph.db}),
 * so meta and graph can be queried together.
 *
 * <p>This replaces the per-group-artifact {@code .properties} / {@code .json}
 * files. The on-disk files remain only as an import source for
 * {@code migrate-meta}.
 *
 * <p>Two tables:
 * <ul>
 *   <li>{@code meta_artifacts} — one row per group:artifact, with surrogate id.</li>
 *   <li>{@code meta_versions}  — one row per discovered version, FK to the
 *       artifact, carrying the publish timestamp and a missing-POM flag.</li>
 * </ul>
 *
 * <p>Timestamps are stored as ISO-8601 strings (VARCHAR) to avoid DuckDB
 * timestamp/Instant timezone conversion surprises; they round-trip through
 * {@link Instant#toString()} / {@link Instant#parse(CharSequence)}.
 *
 * <p>Threading: DuckDB allows only a single writer per file from a process.
 * Each public method opens and closes its own connection, and write methods
 * are {@code synchronized} so concurrent callers (e.g. the parallel meta walk)
 * serialise their writes.
 */
public class MetaRepository {
    private static final Logger log = LoggerFactory.getLogger(MetaRepository.class);
    private final String dbPath;

    public MetaRepository(File cacheGenieRoot) {
        this.dbPath = new File(cacheGenieRoot, "graph.db").getAbsolutePath();
        initSchema();
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:duckdb:" + dbPath);
    }

    private void initSchema() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SEQUENCE IF NOT EXISTS seq_meta_artifact_id");
            stmt.execute("CREATE TABLE IF NOT EXISTS meta_artifacts (" +
                    "id INTEGER PRIMARY KEY," +
                    "gid VARCHAR," +
                    "aid VARCHAR," +
                    "uri VARCHAR," +
                    "latest VARCHAR," +
                    "release VARCHAR," +
                    "updated VARCHAR," +
                    "generated VARCHAR," +
                    "status VARCHAR," +
                    "UNIQUE (gid, aid))");
            stmt.execute("CREATE TABLE IF NOT EXISTS meta_versions (" +
                    "ga_id INTEGER," +
                    "version VARCHAR," +
                    "published VARCHAR," +
                    "missing_pom BOOLEAN," +
                    "PRIMARY KEY (ga_id, version))");
            log.debug("Meta schema initialised at {}", dbPath);
        } catch (SQLException e) {
            log.error("Failed to initialise meta schema", e);
        }
    }

    /**
     * Load the set of coordinate keys ({@code "gid:aid"}) already imported, so a
     * migration re-run can skip them instead of redoing the work. A coordinate is
     * present here only once its (batched) write has committed, so skipping it is
     * safe after an interrupted run.
     */
    public Set<String> loadCoordinateKeys() {
        Set<String> keys = new HashSet<>();
        try (Connection conn = getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT gid, aid FROM meta_artifacts")) {
            while (rs.next()) {
                keys.add(rs.getString(1) + ":" + rs.getString(2));
            }
        } catch (SQLException e) {
            log.error("Failed to load existing meta coordinates", e);
        }
        return keys;
    }

    /** Remove all imported meta rows, for a clean re-import ({@code migrate-meta --fresh}). */
    public synchronized void truncateMeta() {
        try (Connection conn = getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM meta_versions");
            st.execute("DELETE FROM meta_artifacts");
            log.info("Cleared meta_artifacts and meta_versions");
        } catch (SQLException e) {
            log.error("Failed to clear meta tables", e);
        }
    }

    /** Insert or update a group:artifact and replace its full version set. */
    public synchronized void save(MavenMetaData meta) {
        if (meta == null || meta.gid == null || meta.aid == null) {
            log.warn("Refusing to save meta with null coordinates");
            return;
        }
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                writeOne(conn, meta);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.error("Failed to save meta for {}:{}", meta.gid, meta.aid, e);
        }
    }

    /**
     * Bulk insert/update many records reusing a single connection — far faster
     * than calling {@link #save} per record, which opens a fresh DuckDB
     * connection (and re-attaches the database file) each time. Commits in
     * batches. {@code onEach} (nullable) is invoked after each successful write,
     * e.g. to tick progress.
     *
     * @return the number of records written.
     */
    public synchronized int saveAll(java.util.Collection<MavenMetaData> metas,
                                    java.util.function.Consumer<MavenMetaData> onEach) {
        if (metas == null || metas.isEmpty()) return 0;
        int written = 0;
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            int sinceCommit = 0;
            for (MavenMetaData meta : metas) {
                if (meta == null || meta.gid == null || meta.aid == null) continue;
                try {
                    writeOne(conn, meta);
                    written++;
                    sinceCommit++;
                    if (sinceCommit >= 200) {
                        conn.commit();
                        sinceCommit = 0;
                    }
                    if (onEach != null) onEach.accept(meta);
                } catch (SQLException e) {
                    log.error("Failed to import meta for {}:{} — skipping", meta.gid, meta.aid, e);
                    conn.rollback();
                    sinceCommit = 0;
                }
            }
            conn.commit();
        } catch (SQLException e) {
            log.error("Bulk meta import failed", e);
        }
        return written;
    }

    /**
     * Open a streaming writer that reuses a single connection and commits in
     * batches. Unlike {@link #saveAll}, the caller feeds records one at a time,
     * so the source (e.g. hundreds of thousands of on-disk meta files) never has
     * to be materialised in memory at once. Always use in try-with-resources so
     * the final commit runs.
     */
    public BatchWriter openBatch() throws SQLException {
        return new BatchWriter();
    }

    /**
     * Streaming bulk writer over one connection. Not thread-safe: drive it from a
     * single thread.
     *
     * <p>{@link #write} upserts each record (so writing a coordinate twice — e.g.
     * the {@code .properties} form then the {@code .json} form — leaves the later
     * write's data in place, "JSON wins"). {@link #writeNew} is a much faster path
     * for bulk import of coordinates the caller has already de-duplicated: a single
     * full-row INSERT plus a plain version batch, with no per-record SELECT, UPDATE,
     * DELETE or ON CONFLICT. (The old path's per-record UPDATE was the main reason
     * throughput decayed as the table grew, since a DuckDB UPDATE rewrites rows.)
     * The INSERT statements are prepared once and reused.
     */
    public final class BatchWriter implements AutoCloseable {
        private static final int COMMIT_EVERY = 2000;
        private final Connection conn;
        private final PreparedStatement insArtifact;
        private final PreparedStatement insVersion;
        private int sinceCommit = 0;
        private int written = 0;
        private int skipped = 0;

        private BatchWriter() throws SQLException {
            this.conn = getConnection();
            this.conn.setAutoCommit(false);
            this.insArtifact = conn.prepareStatement(
                    "INSERT INTO meta_artifacts (id, gid, aid, uri, latest, release, updated, generated, status) " +
                    "VALUES (nextval('seq_meta_artifact_id'), ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id");
            this.insVersion = conn.prepareStatement(
                    "INSERT INTO meta_versions (ga_id, version, published, missing_pom) VALUES (?, ?, ?, ?)");
        }

        /** Upsert path (safe for existing coordinates). @return true if written. */
        public boolean write(MavenMetaData meta) {
            if (meta == null || meta.gid == null || meta.aid == null) {
                skipped++;
                return false;
            }
            try {
                writeOne(conn, meta);
                return committed();
            } catch (SQLException e) {
                return failed(meta, e);
            }
        }

        /**
         * Fast insert path for bulk import. Assumes the coordinate is NEW — the
         * caller (migration) de-duplicates against what is already in the DB, so a
         * plain INSERT is safe and far cheaper than the find-or-update upsert.
         * @return true if written.
         */
        public boolean writeNew(MavenMetaData meta) {
            if (meta == null || meta.gid == null || meta.aid == null) {
                skipped++;
                return false;
            }
            try {
                int gaId;
                insArtifact.setString(1, meta.gid);
                insArtifact.setString(2, meta.aid);
                insArtifact.setString(3, meta.uri != null ? meta.uri.toASCIIString() : null);
                insArtifact.setString(4, meta.latest);
                insArtifact.setString(5, meta.release);
                insArtifact.setString(6, meta.updated() != null ? meta.updated().toString() : null);
                insArtifact.setString(7, Instant.now().toString());
                insArtifact.setString(8, meta.status != null ? meta.status.name() : null);
                try (ResultSet rs = insArtifact.executeQuery()) {
                    rs.next();
                    gaId = rs.getInt(1);
                }
                for (MavenMetaData.Version v : meta.versions.values()) {
                    insVersion.setInt(1, gaId);
                    insVersion.setString(2, v.value());
                    insVersion.setString(3, v.date() != null ? v.date().toString() : null);
                    insVersion.setBoolean(4, meta.isMissingPom(v.value()));
                    insVersion.addBatch();
                }
                insVersion.executeBatch();
                return committed();
            } catch (SQLException e) {
                return failed(meta, e);
            }
        }

        private boolean committed() throws SQLException {
            written++;
            if (++sinceCommit >= COMMIT_EVERY) {
                conn.commit();
                sinceCommit = 0;
            }
            return true;
        }

        private boolean failed(MavenMetaData meta, SQLException e) {
            log.error("Failed to import meta for {}:{} — skipping", meta.gid, meta.aid, e);
            try { conn.rollback(); } catch (SQLException re) { log.error("rollback failed", re); }
            sinceCommit = 0;
            skipped++;
            return false;
        }

        public int written() { return written; }
        public int skipped() { return skipped; }

        @Override
        public void close() {
            try { conn.commit(); } catch (SQLException e) { log.error("final meta commit failed", e); }
            try { insArtifact.close(); } catch (SQLException e) { log.error("closing statement failed", e); }
            try { insVersion.close(); } catch (SQLException e) { log.error("closing statement failed", e); }
            try { conn.close(); } catch (SQLException e) { log.error("closing meta connection failed", e); }
        }
    }

    /** Insert/update one record's artifact row and replace its version set. No transaction management. */
    private void writeOne(Connection conn, MavenMetaData meta) throws SQLException {
        int gaId = getOrInsertArtifact(conn, meta);
        updateArtifactFields(conn, gaId, meta);

        // Make the table mirror the in-memory version set.
        //
        // Upsert each version rather than DELETE-all-then-INSERT: DuckDB's ART
        // primary-key index does not reflect a row deleted earlier in the *same*
        // transaction, so re-inserting a key we just deleted raises a spurious
        // duplicate-key error (the documented index limitation). ON CONFLICT only
        // updates the non-key columns, so it never re-inserts an existing key.
        try (PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO meta_versions (ga_id, version, published, missing_pom) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (ga_id, version) DO UPDATE SET published = excluded.published, missing_pom = excluded.missing_pom")) {
            for (MavenMetaData.Version v : meta.versions.values()) {
                ins.setInt(1, gaId);
                ins.setString(2, v.value());
                ins.setString(3, v.date() != null ? v.date().toString() : null);
                ins.setBoolean(4, meta.isMissingPom(v.value()));
                ins.addBatch();
            }
            ins.executeBatch();
        }

        // Drop any versions no longer present. This only deletes keys that are NOT
        // being re-inserted, so it avoids the same-transaction delete+insert clash.
        if (meta.versions.isEmpty()) {
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM meta_versions WHERE ga_id = ?")) {
                del.setInt(1, gaId);
                del.executeUpdate();
            }
        } else {
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < meta.versions.size(); i++) {
                placeholders.append(i == 0 ? "?" : ",?");
            }
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM meta_versions WHERE ga_id = ? AND version NOT IN (" + placeholders + ")")) {
                del.setInt(1, gaId);
                int idx = 2;
                for (MavenMetaData.Version v : meta.versions.values()) {
                    del.setString(idx++, v.value());
                }
                del.executeUpdate();
            }
        }
    }

    private int getOrInsertArtifact(Connection conn, MavenMetaData meta) throws SQLException {
        try (PreparedStatement sel = conn.prepareStatement(
                "SELECT id FROM meta_artifacts WHERE gid = ? AND aid = ?")) {
            sel.setString(1, meta.gid);
            sel.setString(2, meta.aid);
            try (ResultSet rs = sel.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        try (PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO meta_artifacts (id, gid, aid) VALUES (nextval('seq_meta_artifact_id'), ?, ?) RETURNING id")) {
            ins.setString(1, meta.gid);
            ins.setString(2, meta.aid);
            try (ResultSet rs = ins.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        throw new SQLException("Failed to insert meta artifact and retrieve id");
    }

    private void updateArtifactFields(Connection conn, int gaId, MavenMetaData meta) throws SQLException {
        try (PreparedStatement upd = conn.prepareStatement(
                "UPDATE meta_artifacts SET uri = ?, latest = ?, release = ?, updated = ?, generated = ?, status = ? WHERE id = ?")) {
            upd.setString(1, meta.uri != null ? meta.uri.toASCIIString() : null);
            upd.setString(2, meta.latest);
            upd.setString(3, meta.release);
            upd.setString(4, meta.updated() != null ? meta.updated().toString() : null);
            upd.setString(5, Instant.now().toString());
            upd.setString(6, meta.status != null ? meta.status.name() : null);
            upd.setInt(7, gaId);
            upd.executeUpdate();
        }
    }

    public boolean exists(String gid, String aid) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM meta_artifacts WHERE gid = ? AND aid = ?")) {
            ps.setString(1, gid);
            ps.setString(2, aid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("Failed to check meta existence for {}:{}", gid, aid, e);
            return false;
        }
    }

    public MavenMetaData load(String gid, String aid) {
        try (Connection conn = getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, uri, latest, release, updated, generated, status " +
                    "FROM meta_artifacts WHERE gid = ? AND aid = ?")) {
                ps.setString(1, gid);
                ps.setString(2, aid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return readMeta(conn, rs, gid, aid);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load meta for {}:{}", gid, aid, e);
            return null;
        }
    }

    /** Load all artifacts whose gid matches, and (if non-null) aid matches. */
    public List<MavenMetaData> loadByPattern(String gid, String aid) {
        String sql = "SELECT id, gid, aid, uri, latest, release, updated, generated, status FROM meta_artifacts WHERE gid = ?"
                + (aid != null ? " AND aid = ?" : "");
        List<MavenMetaData> out = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, gid);
            if (aid != null) ps.setString(2, aid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readMeta(conn, rs, rs.getString("gid"), rs.getString("aid")));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load meta by pattern {}:{}", gid, aid, e);
        }
        return out;
    }

    public List<MavenMetaData> loadAll() {
        List<MavenMetaData> out = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, gid, aid, uri, latest, release, updated, generated, status FROM meta_artifacts")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readMeta(conn, rs, rs.getString("gid"), rs.getString("aid")));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load all meta", e);
        }
        return out;
    }

    private MavenMetaData readMeta(Connection conn, ResultSet rs, String gid, String aid) throws SQLException {
        int gaId = rs.getInt("id");
        URI uri = null;
        String uriStr = rs.getString("uri");
        if (uriStr != null && !uriStr.isEmpty()) {
            try { uri = URI.create(uriStr); } catch (Exception ignore) { /* leave null */ }
        }
        MavenMetaData meta = new MavenMetaData(uri);
        meta.gid = gid;
        meta.aid = aid;
        meta.latest = rs.getString("latest");
        meta.release = rs.getString("release");
        meta.setUpdatedInstant(parseInstant(rs.getString("updated")));
        meta.setGenerated(parseInstant(rs.getString("generated")));
        String status = rs.getString("status");
        if (status != null) {
            try { meta.status = MavenMetaData.Status.valueOf(status); }
            catch (IllegalArgumentException ignore) { /* leave default */ }
        }

        try (PreparedStatement vp = conn.prepareStatement(
                "SELECT version, published, missing_pom FROM meta_versions WHERE ga_id = ? ORDER BY version")) {
            vp.setInt(1, gaId);
            try (ResultSet vr = vp.executeQuery()) {
                while (vr.next()) {
                    String version = vr.getString("version");
                    meta.setUpdated(version, parseInstant(vr.getString("published")));
                    if (vr.getBoolean("missing_pom")) meta.markPomAsMissing(version);
                }
            }
        }
        return meta;
    }

    public synchronized void markPomMissing(String gid, String aid, String version) {
        setMissing(gid, aid, version, true);
    }

    public synchronized void markPomFound(String gid, String aid, String version) {
        setMissing(gid, aid, version, false);
    }

    private void setMissing(String gid, String aid, String version, boolean missing) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE meta_versions SET missing_pom = ? " +
                     "WHERE version = ? AND ga_id = (SELECT id FROM meta_artifacts WHERE gid = ? AND aid = ?)")) {
            ps.setBoolean(1, missing);
            ps.setString(2, version);
            ps.setString(3, gid);
            ps.setString(4, aid);
            int n = ps.executeUpdate();
            if (n == 0) log.debug("No meta_versions row to update for {}:{}:{}", gid, aid, version);
        } catch (SQLException e) {
            log.error("Failed to update missing flag for {}:{}:{}", gid, aid, version, e);
        }
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isEmpty() || "null".equals(s)) return null;
        try { return Instant.parse(s); } catch (Exception e) { return null; }
    }
}
