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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persists CacheGenie discovery metadata ({@link MavenMetaData}) into the same
 * DuckDB database used for the dependency graph ({@code ~/.m2/cachegenie/graph.db}),
 * so meta and graph can be queried together.
 *
 * <p>This replaces the per-group-artifact {@code .properties} / {@code .json}
 * files; metadata now lives solely in DuckDB.
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
     * Load a {@code "gid:aid" -> generated} map of when each artifact's metadata
     * was last written/checked. Used by the scan freshness gate to skip artifacts
     * re-checked within a staleness window. One query; read-only afterwards.
     */
    public Map<String, Instant> loadLastChecked() {
        Map<String, Instant> out = new HashMap<>();
        try (Connection conn = getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT gid, aid, generated FROM meta_artifacts")) {
            while (rs.next()) {
                Instant g = parseInstant(rs.getString("generated"));
                if (g != null) out.put(rs.getString("gid") + ":" + rs.getString("aid"), g);
            }
        } catch (SQLException e) {
            log.error("Failed to load last-checked timestamps", e);
        }
        return out;
    }

    /** Result of {@link #mergeDiscovered}: whether the group:artifact was newly seen, and how many versions were added. */
    public record MergeStats(boolean newArtifact, int newVersions) {}

    /**
     * Merge a freshly-discovered metadata record (from a scan) into the DB:
     * insert the group:artifact if new, refresh its top-level fields, and add ONLY
     * versions not already stored. Unlike {@link #save}, this never deletes
     * versions and never overwrites an existing version's {@code published} or
     * {@code missing_pom} — so flags set by {@code fetch} survive a re-scan.
     *
     * @return how many versions were added and whether the artifact was new.
     */
    public synchronized MergeStats mergeDiscovered(MavenMetaData meta) {
        if (meta == null || meta.gid == null || meta.aid == null) {
            return new MergeStats(false, 0);
        }
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                boolean newArtifact;
                int gaId;
                try (PreparedStatement sel = conn.prepareStatement(
                        "SELECT id FROM meta_artifacts WHERE gid = ? AND aid = ?")) {
                    sel.setString(1, meta.gid);
                    sel.setString(2, meta.aid);
                    try (ResultSet rs = sel.executeQuery()) {
                        if (rs.next()) { gaId = rs.getInt(1); newArtifact = false; }
                        else { gaId = -1; newArtifact = true; }
                    }
                }
                if (newArtifact) {
                    try (PreparedStatement ins = conn.prepareStatement(
                            "INSERT INTO meta_artifacts (id, gid, aid) VALUES (nextval('seq_meta_artifact_id'), ?, ?) RETURNING id")) {
                        ins.setString(1, meta.gid);
                        ins.setString(2, meta.aid);
                        try (ResultSet rs = ins.executeQuery()) { rs.next(); gaId = rs.getInt(1); }
                    }
                }

                updateArtifactFields(conn, gaId, meta);

                // Which versions are already stored? (none if the artifact is new)
                Set<String> existing = new HashSet<>();
                if (!newArtifact) {
                    try (PreparedStatement vp = conn.prepareStatement(
                            "SELECT version FROM meta_versions WHERE ga_id = ?")) {
                        vp.setInt(1, gaId);
                        try (ResultSet rs = vp.executeQuery()) {
                            while (rs.next()) existing.add(rs.getString(1));
                        }
                    }
                }

                int added = 0;
                try (PreparedStatement insv = conn.prepareStatement(
                        "INSERT INTO meta_versions (ga_id, version, published, missing_pom) VALUES (?, ?, ?, ?)")) {
                    for (MavenMetaData.Version v : meta.versions.values()) {
                        if (existing.contains(v.value())) continue;   // leave existing rows untouched
                        insv.setInt(1, gaId);
                        insv.setString(2, v.value());
                        insv.setString(3, v.date() != null ? v.date().toString() : null);
                        insv.setBoolean(4, meta.isMissingPom(v.value()));
                        insv.addBatch();
                        added++;
                    }
                    if (added > 0) insv.executeBatch();
                }

                conn.commit();
                return new MergeStats(newArtifact, added);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.error("Failed to merge meta for {}:{}", meta.gid, meta.aid, e);
            return new MergeStats(false, 0);
        }
    }

    /**
     * Open a streaming writer for bulk index-sync ingestion (Maven Central's
     * published index): records arrive one GAV at a time, in the millions. Reuses
     * one connection, caches artifact ids in memory, inserts versions with
     * ON CONFLICT DO NOTHING, and commits in batches. Use in try-with-resources.
     */
    public IndexSyncWriter openIndexSync() throws SQLException {
        return new IndexSyncWriter();
    }

    /** Streaming writer for index-sync. Not thread-safe; drive from one thread. */
    public final class IndexSyncWriter implements AutoCloseable {
        private static final int COMMIT_EVERY = 5000;
        private final Connection conn;
        private final PreparedStatement selArtifact;
        private final PreparedStatement insArtifact;
        private final PreparedStatement insVersion;
        private final PreparedStatement delVersion;
        private final Map<String, Integer> idCache = new HashMap<>();
        private int sinceCommit = 0;

        private IndexSyncWriter() throws SQLException {
            conn = getConnection();
            conn.setAutoCommit(false);
            selArtifact = conn.prepareStatement("SELECT id FROM meta_artifacts WHERE gid = ? AND aid = ?");
            insArtifact = conn.prepareStatement(
                    "INSERT INTO meta_artifacts (id, gid, aid) VALUES (nextval('seq_meta_artifact_id'), ?, ?) RETURNING id");
            insVersion = conn.prepareStatement(
                    "INSERT INTO meta_versions (ga_id, version, published, missing_pom) VALUES (?, ?, ?, false) " +
                    "ON CONFLICT (ga_id, version) DO NOTHING");
            delVersion = conn.prepareStatement("DELETE FROM meta_versions WHERE ga_id = ? AND version = ?");
        }

        // SELECT-then-INSERT (cached): we only INSERT when truly absent, so no
        // ON CONFLICT is needed on the artifact row.
        private int artifactId(String gid, String aid) throws SQLException {
            String key = gid + ":" + aid;
            Integer id = idCache.get(key);
            if (id != null) return id;
            selArtifact.setString(1, gid);
            selArtifact.setString(2, aid);
            try (ResultSet rs = selArtifact.executeQuery()) {
                if (rs.next()) id = rs.getInt(1);
            }
            if (id == null) {
                insArtifact.setString(1, gid);
                insArtifact.setString(2, aid);
                try (ResultSet rs = insArtifact.executeQuery()) {
                    if (rs.next()) id = rs.getInt(1);
                }
            }
            if (id == null) throw new SQLException("could not resolve meta artifact id for " + key);
            idCache.put(key, id);
            return id;
        }

        /** Insert a version if absent. @return true if a new version row was added. */
        public boolean addVersion(String gid, String aid, String version, Long fileModifiedMillis) {
            if (gid == null || aid == null || version == null) return false;
            try {
                int gaId = artifactId(gid, aid);
                insVersion.setInt(1, gaId);
                insVersion.setString(2, version);
                insVersion.setString(3, fileModifiedMillis != null ? Instant.ofEpochMilli(fileModifiedMillis).toString() : null);
                int n = insVersion.executeUpdate();
                maybeCommit();
                return n > 0;
            } catch (SQLException e) {
                log.error("index-sync add {}:{}:{} failed", gid, aid, version, e);
                return false;
            }
        }

        /** Remove a version (incremental ARTIFACT_REMOVE record). */
        public void removeVersion(String gid, String aid, String version) {
            if (gid == null || aid == null || version == null) return;
            try {
                int gaId = artifactId(gid, aid);
                delVersion.setInt(1, gaId);
                delVersion.setString(2, version);
                delVersion.executeUpdate();
                maybeCommit();
            } catch (SQLException e) {
                log.error("index-sync remove {}:{}:{} failed", gid, aid, version, e);
            }
        }

        private void maybeCommit() throws SQLException {
            if (++sinceCommit >= COMMIT_EVERY) {
                conn.commit();
                sinceCommit = 0;
            }
        }

        @Override
        public void close() {
            try { conn.commit(); } catch (SQLException e) { log.error("final index-sync commit failed", e); }
            for (PreparedStatement ps : new PreparedStatement[]{selArtifact, insArtifact, insVersion, delVersion}) {
                try { if (ps != null) ps.close(); } catch (SQLException ignore) { /* ignore */ }
            }
            try { conn.close(); } catch (SQLException e) { log.error("closing index-sync connection failed", e); }
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
