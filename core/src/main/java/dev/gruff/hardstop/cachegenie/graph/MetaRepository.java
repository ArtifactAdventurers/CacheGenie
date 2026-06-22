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
                    "packaging VARCHAR," +
                    "file_extension VARCHAR," +
                    "file_size BIGINT," +
                    "sha1 VARCHAR," +
                    "sha256 VARCHAR," +
                    "has_sources BOOLEAN," +
                    "has_javadoc BOOLEAN," +
                    "PRIMARY KEY (ga_id, version))");
            // Backfill columns on databases created before these were added.
            for (String col : new String[]{
                    "packaging VARCHAR", "file_extension VARCHAR", "file_size BIGINT",
                    "sha1 VARCHAR", "sha256 VARCHAR", "has_sources BOOLEAN", "has_javadoc BOOLEAN"}) {
                try {
                    stmt.execute("ALTER TABLE meta_versions ADD COLUMN IF NOT EXISTS " + col);
                } catch (SQLException ignore) { /* older DuckDB without IF NOT EXISTS: column likely exists */ }
            }
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

    /**
     * Select versions that should be graphed: optionally filtered by coordinate
     * ({@code gid}/{@code aid}/{@code version}, any may be null) and/or published
     * since {@code since} (null = no time filter). Excludes versions flagged
     * {@code missing_pom} and versions already present in the {@code artifacts}
     * graph table. Returns {@code {gid, aid, version}} triples — the worklist for
     * {@code graph deps}. Requires the {@code artifacts} table to exist.
     */
    public List<String[]> selectVersionsToGraph(String gid, String aid, String version, Instant since) {
        StringBuilder sql = new StringBuilder(
                "SELECT a.gid, a.aid, v.version FROM meta_versions v " +
                "JOIN meta_artifacts a ON a.id = v.ga_id " +
                "WHERE (v.missing_pom IS NULL OR v.missing_pom = FALSE) " +
                "AND NOT EXISTS (SELECT 1 FROM artifacts ar WHERE ar.gid = a.gid AND ar.aid = a.aid AND ar.version = v.version)");
        List<String> params = new ArrayList<>();
        if (gid != null) { sql.append(" AND a.gid = ?"); params.add(gid); }
        if (aid != null) { sql.append(" AND a.aid = ?"); params.add(aid); }
        if (version != null) { sql.append(" AND v.version = ?"); params.add(version); }
        if (since != null) { sql.append(" AND v.published IS NOT NULL AND v.published >= ?"); params.add(since.toString()); }

        List<String[]> out = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setString(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)});
                }
            }
        } catch (SQLException e) {
            log.error("selectVersionsToGraph failed", e);
        }
        return out;
    }

    /**
     * Like {@link #selectVersionsToGraph}, but the worklist for {@code graph mine}:
     * versions not yet mined (no {@code pom_meta} row) rather than not yet graphed.
     * Excludes {@code missing_pom} versions. Returns {@code {gid, aid, version}}
     * triples. Requires the {@code pom_meta}/{@code artifacts} tables to exist.
     */
    public List<String[]> selectVersionsToMine(String gid, String aid, String version, Instant since, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT a.gid, a.aid, v.version FROM meta_versions v " +
                "JOIN meta_artifacts a ON a.id = v.ga_id " +
                "WHERE (v.missing_pom IS NULL OR v.missing_pom = FALSE) " +
                "AND NOT EXISTS (SELECT 1 FROM artifacts ar JOIN pom_meta pm ON pm.artifact_id = ar.id " +
                "WHERE ar.gid = a.gid AND ar.aid = a.aid AND ar.version = v.version)");
        List<String> params = new ArrayList<>();
        if (gid != null) { sql.append(" AND a.gid = ?"); params.add(gid); }
        if (aid != null) { sql.append(" AND a.aid = ?"); params.add(aid); }
        if (version != null) { sql.append(" AND v.version = ?"); params.add(version); }
        if (since != null) { sql.append(" AND v.published IS NOT NULL AND v.published >= ?"); params.add(since.toString()); }
        if (limit > 0) { sql.append(" LIMIT ").append(limit); } // bounded chunk for a polite drip

        List<String[]> out = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setString(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)});
                }
            }
        } catch (SQLException e) {
            log.error("selectVersionsToMine failed", e);
        }
        return out;
    }

    /** Count the {@link #selectVersionsToMine} worklist without materialising it (cheap {@code COUNT(*)}). */
    public long countVersionsToMine(String gid, String aid, String version, Instant since) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM meta_versions v " +
                "JOIN meta_artifacts a ON a.id = v.ga_id " +
                "WHERE (v.missing_pom IS NULL OR v.missing_pom = FALSE) " +
                "AND NOT EXISTS (SELECT 1 FROM artifacts ar JOIN pom_meta pm ON pm.artifact_id = ar.id " +
                "WHERE ar.gid = a.gid AND ar.aid = a.aid AND ar.version = v.version)");
        List<String> params = new ArrayList<>();
        if (gid != null) { sql.append(" AND a.gid = ?"); params.add(gid); }
        if (aid != null) { sql.append(" AND a.aid = ?"); params.add(aid); }
        if (version != null) { sql.append(" AND v.version = ?"); params.add(version); }
        if (since != null) { sql.append(" AND v.published IS NOT NULL AND v.published >= ?"); params.add(since.toString()); }
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setString(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            log.error("countVersionsToMine failed", e);
        }
        return -1;
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
        private final PreparedStatement updSummary;
        private final Map<String, Integer> idCache = new HashMap<>();
        private int sinceCommit = 0;

        private IndexSyncWriter() throws SQLException {
            conn = getConnection();
            conn.setAutoCommit(false);
            selArtifact = conn.prepareStatement("SELECT id FROM meta_artifacts WHERE gid = ? AND aid = ?");
            insArtifact = conn.prepareStatement(
                    "INSERT INTO meta_artifacts (id, gid, aid) VALUES (nextval('seq_meta_artifact_id'), ?, ?) RETURNING id");
            insVersion = conn.prepareStatement(
                    "INSERT INTO meta_versions (ga_id, version, published, missing_pom, packaging, file_extension, " +
                    "file_size, sha1, sha256, has_sources, has_javadoc) " +
                    "VALUES (?, ?, ?, false, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (ga_id, version) DO NOTHING");
            delVersion = conn.prepareStatement("DELETE FROM meta_versions WHERE ga_id = ? AND version = ?");
            // Refresh generated/latest/release for one artifact (latest = newest by publish date).
            updSummary = conn.prepareStatement(
                    "UPDATE meta_artifacts SET generated = ?, " +
                    "latest = (SELECT arg_max(version, published) FROM meta_versions WHERE ga_id = ?), " +
                    "release = COALESCE(" +
                    "  (SELECT arg_max(version, published) FILTER (WHERE version NOT LIKE '%-SNAPSHOT') FROM meta_versions WHERE ga_id = ?), " +
                    "  (SELECT arg_max(version, published) FROM meta_versions WHERE ga_id = ?)) " +
                    "WHERE id = ?");
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

        /** Insert a version (with main-artifact fields) if absent. @return true if a new row was added. */
        public boolean addVersion(String gid, String aid, String version, Long fileModifiedMillis,
                                 String packaging, String fileExtension, Long fileSize,
                                 String sha1, String sha256, Boolean hasSources, Boolean hasJavadoc) {
            if (gid == null || aid == null || version == null) return false;
            try {
                int gaId = artifactId(gid, aid);
                insVersion.setInt(1, gaId);
                insVersion.setString(2, version);
                insVersion.setString(3, fileModifiedMillis != null ? Instant.ofEpochMilli(fileModifiedMillis).toString() : null);
                insVersion.setString(4, packaging);
                insVersion.setString(5, fileExtension);
                if (fileSize != null) insVersion.setLong(6, fileSize); else insVersion.setNull(6, java.sql.Types.BIGINT);
                insVersion.setString(7, sha1);
                insVersion.setString(8, sha256);
                insVersion.setObject(9, hasSources);
                insVersion.setObject(10, hasJavadoc);
                int n = insVersion.executeUpdate();
                maybeCommit();
                return n > 0;
            } catch (SQLException e) {
                log.error("index-sync add {}:{}:{} failed", gid, aid, version, e);
                return false;
            }
        }

        /** Refresh one artifact's generated/latest/release after adding versions. */
        public void refreshSummary(String gid, String aid) {
            if (gid == null || aid == null) return;
            try {
                int gaId = artifactId(gid, aid);
                updSummary.setString(1, Instant.now().toString());
                updSummary.setInt(2, gaId);
                updSummary.setInt(3, gaId);
                updSummary.setInt(4, gaId);
                updSummary.setInt(5, gaId);
                updSummary.executeUpdate();
                maybeCommit();
            } catch (SQLException e) {
                log.error("index-sync summary refresh {}:{} failed", gid, aid, e);
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
            for (PreparedStatement ps : new PreparedStatement[]{selArtifact, insArtifact, insVersion, delVersion, updSummary}) {
                try { if (ps != null) ps.close(); } catch (SQLException ignore) { /* ignore */ }
            }
            try { conn.close(); } catch (SQLException e) { log.error("closing index-sync connection failed", e); }
        }
    }

    /**
     * Open a bulk loader for the FULL index bootstrap. Records (in the ~100M range,
     * mostly duplicate file-records) are appended to a constraint-free staging
     * table via DuckDB's {@code Appender}, then merged set-based: collapsed to
     * distinct versions once, new artifacts bulk-created, versions bulk-inserted.
     * This avoids the ~100M per-row {@code ON CONFLICT} index probes that made the
     * row-by-row path take a day. Use only for full pulls (incremental updates,
     * which also carry removals, use {@link IndexSyncWriter}).
     *
     * <p>Note: the staging table lives in {@code graph.db} and so grows the file
     * for the duration; run {@code db compact --rewrite} afterwards to reclaim it.
     */
    public IndexStageLoader openIndexStage() throws SQLException {
        return new IndexStageLoader();
    }

    /** Bulk staging loader; see {@link #openIndexStage()}. Not thread-safe. */
    public final class IndexStageLoader implements AutoCloseable {
        private final Connection conn;
        private final org.duckdb.DuckDBAppender appender;
        private boolean appenderClosed = false;

        private IndexStageLoader() throws SQLException {
            conn = getConnection();
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS idx_stage");
                st.execute("CREATE TABLE idx_stage (" +
                        "gid VARCHAR, aid VARCHAR, version VARCHAR, packaging VARCHAR, " +
                        "file_extension VARCHAR, file_size BIGINT, sha1 VARCHAR, sha256 VARCHAR, " +
                        "has_sources BOOLEAN, has_javadoc BOOLEAN, published VARCHAR)");
            }
            org.duckdb.DuckDBConnection duck = conn.unwrap(org.duckdb.DuckDBConnection.class);
            appender = duck.createAppender("main", "idx_stage");
        }

        /**
         * Append one (main-artifact) record. Caller should only pass records with an
         * empty classifier — the main jar/pom. Null strings are stored as ""
         * (normalised back to NULL in {@link #merge()}); a null size becomes 0.
         */
        public void append(String gid, String aid, String version, String packaging, String fileExtension,
                           Long fileSize, String sha1, String sha256, Boolean hasSources, Boolean hasJavadoc,
                           Long publishedMillis) throws SQLException {
            appender.beginRow();
            appender.append(gid);
            appender.append(aid);
            appender.append(version);
            appender.append(packaging != null ? packaging : "");
            appender.append(fileExtension != null ? fileExtension : "");
            appender.append(fileSize != null ? fileSize.longValue() : 0L);
            appender.append(sha1 != null ? sha1 : "");
            appender.append(sha256 != null ? sha256 : "");
            appender.append(hasSources != null && hasSources);
            appender.append(hasJavadoc != null && hasJavadoc);
            appender.append(publishedMillis != null ? Instant.ofEpochMilli(publishedMillis).toString() : "");
            appender.endRow();
        }

        /**
         * Flush staging and merge into the meta tables, then drop staging. For each
         * (gid,aid,version) the representative file is the largest staged record (the
         * main jar over its pom), and its fields populate the version row.
         * @return {@code [newArtifacts, newVersions]}.
         */
        public long[] merge() throws SQLException {
            if (!appenderClosed) {
                appender.close();
                appenderClosed = true;
            }
            long newArtifacts;
            long newVersions;
            try (Statement st = conn.createStatement()) {
                // Bulk-create artifacts not already present.
                newArtifacts = st.executeUpdate(
                        "INSERT INTO meta_artifacts (id, gid, aid) " +
                        "SELECT nextval('seq_meta_artifact_id'), s.gid, s.aid " +
                        "FROM (SELECT DISTINCT gid, aid FROM idx_stage) s " +
                        "WHERE NOT EXISTS (SELECT 1 FROM meta_artifacts a WHERE a.gid = s.gid AND a.aid = s.aid)");
                // Pick one representative file per version (largest = main artifact), bulk-insert new ones.
                newVersions = st.executeUpdate(
                        "INSERT INTO meta_versions (ga_id, version, published, missing_pom, packaging, " +
                        "file_extension, file_size, sha1, sha256, has_sources, has_javadoc) " +
                        "SELECT a.id, m.version, NULLIF(m.published, ''), false, NULLIF(m.packaging, ''), " +
                        "NULLIF(m.file_extension, ''), m.file_size, NULLIF(m.sha1, ''), NULLIF(m.sha256, ''), " +
                        "m.has_sources, m.has_javadoc FROM (" +
                        "  SELECT gid, aid, version, packaging, file_extension, file_size, sha1, sha256, " +
                        "  has_sources, has_javadoc, published, " +
                        "  ROW_NUMBER() OVER (PARTITION BY gid, aid, version ORDER BY file_size DESC NULLS LAST) AS rn " +
                        "  FROM idx_stage" +
                        ") m JOIN meta_artifacts a ON a.gid = m.gid AND a.aid = m.aid " +
                        "WHERE m.rn = 1 " +
                        "ON CONFLICT (ga_id, version) DO NOTHING");
                // Refresh artifact-level summary (generated/latest/release) for the
                // artifacts in this sync. latest = version with the most recent
                // publish date (Maven's "last deployed" semantics); release = same
                // excluding -SNAPSHOT. Scoped to staged coordinates.
                st.execute(
                        "UPDATE meta_artifacts AS a SET " +
                        "generated = '" + Instant.now() + "', " +
                        "latest = sub.latest, " +
                        "release = COALESCE(sub.release, sub.latest) " +
                        "FROM (" +
                        "  SELECT v.ga_id AS ga_id, " +
                        "         arg_max(v.version, v.published) AS latest, " +
                        "         arg_max(v.version, v.published) FILTER (WHERE v.version NOT LIKE '%-SNAPSHOT') AS release " +
                        "  FROM meta_versions v " +
                        "  WHERE v.ga_id IN (" +
                        "    SELECT a2.id FROM meta_artifacts a2 " +
                        "    JOIN (SELECT DISTINCT gid, aid FROM idx_stage) s ON a2.gid = s.gid AND a2.aid = s.aid" +
                        "  ) GROUP BY v.ga_id" +
                        ") AS sub WHERE a.id = sub.ga_id");
                st.execute("DROP TABLE IF EXISTS idx_stage");
                st.execute("CHECKPOINT");
            }
            return new long[]{newArtifacts, newVersions};
        }

        @Override
        public void close() {
            if (!appenderClosed) {
                try { appender.close(); } catch (Exception ignore) { /* ignore */ }
                appenderClosed = true;
            }
            try { conn.close(); } catch (SQLException e) { log.error("closing index-stage connection failed", e); }
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
