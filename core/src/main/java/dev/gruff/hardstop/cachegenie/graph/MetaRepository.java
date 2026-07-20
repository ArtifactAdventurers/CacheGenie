package dev.gruff.hardstop.cachegenie.graph;

import dev.gruff.hardstop.cachegenie.MavenMetaData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.sql.Connection;
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
 * SQLite database used for the dependency graph
 * ({@code ~/.m2/cachegenie/graph.sqlite}), so meta and graph can be queried
 * together.
 *
 * <p>This replaces the per-group-artifact {@code .properties} / {@code .json}
 * files; metadata now lives solely in the database.
 *
 * <p>Two tables:
 * <ul>
 *   <li>{@code meta_artifacts} — one row per group:artifact, with surrogate id.</li>
 *   <li>{@code meta_versions}  — one row per discovered version, FK to the
 *       artifact, carrying the publish timestamp and a missing-POM flag.</li>
 * </ul>
 *
 * <p>Timestamps are stored as ISO-8601 strings (VARCHAR); they round-trip
 * through {@link Instant#toString()} / {@link Instant#parse(CharSequence)} and
 * sort correctly as text.
 *
 * <p>Threading: SQLite (in WAL mode, see {@link Sqlite}) allows concurrent
 * readers alongside one writer, but writers still serialise. Each public
 * method opens and closes its own connection, and write methods are
 * {@code synchronized} so concurrent callers (e.g. the parallel meta walk)
 * serialise their writes in-process rather than contending on the DB lock.
 */
public class MetaRepository {
    private static final Logger log = LoggerFactory.getLogger(MetaRepository.class);
    private final String dbPath;

    public MetaRepository(File cacheGenieRoot) {
        this.dbPath = Sqlite.dbPath(cacheGenieRoot);
        initSchema();
    }

    /** Read-write connection (creates the file if absent). */
    private Connection getConnection() throws SQLException {
        return Sqlite.open(dbPath);
    }

    /**
     * Read-only connection for purely-reading methods. Safe once the constructor
     * has run ({@link #initSchema()} creates the file), and under WAL it never
     * blocks or is blocked by the writer.
     */
    private Connection getReadConnection() throws SQLException {
        return Sqlite.openReadOnly(dbPath);
    }

    private void initSchema() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
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
            // (SQLite has no ADD COLUMN IF NOT EXISTS; a duplicate-column error
            // just means the column is already there.)
            for (String col : new String[]{
                    "packaging VARCHAR", "file_extension VARCHAR", "file_size BIGINT",
                    "sha1 VARCHAR", "sha256 VARCHAR", "has_sources BOOLEAN", "has_javadoc BOOLEAN"}) {
                try {
                    stmt.execute("ALTER TABLE meta_versions ADD COLUMN " + col);
                } catch (SQLException ignore) { /* duplicate column name: already present */ }
            }
            // Indexes the point-lookup paths rely on. The (gid,aid) UNIQUE table
            // constraint above already creates its index; ga_id needs an explicit one.
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_meta_versions_ga ON meta_versions(ga_id)");
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
        try (Connection conn = getReadConnection();
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
        try (Connection conn = getReadConnection();
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
        try (Connection conn = getReadConnection();
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

    /**
     * Like {@link #selectVersionsToMine}, but returns one <b>bounded page</b> of the worklist,
     * ordered by {@code (gid, aid, version)} and starting strictly <em>after</em> the given
     * keyset cursor (pass {@code after == null} for the first page). This lets {@code graph mine}
     * stream a multi-million-row backlog in constant memory instead of materialising the whole
     * worklist into one giant {@code List}.
     *
     * <p>Keyset (not OFFSET) pagination is essential here: as rows are mined they leave the
     * worklist (the {@code NOT EXISTS} on {@code pom_meta}), so OFFSET would skip rows. The
     * cursor moves strictly forward over {@code (gid, aid, version)} — already-mined rows are
     * excluded by the predicate, and rows that failed this run sit behind the cursor so the run
     * doesn't loop on them (a fresh run starts the cursor at {@code null} and retries them).
     *
     * @param after {@code {gid, aid, version}} of the last row of the previous page, or {@code null}.
     * @param pageSize maximum rows to return (must be {@code > 0}).
     */
    public List<String[]> selectVersionsToMineAfter(String gid, String aid, String version,
                                                    Instant since, String[] after, int pageSize) {
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
        if (after != null) {
            // Lexicographic keyset predicate over (gid, aid, version), spelled out so it
            // doesn't rely on row-value comparison support.
            sql.append(" AND (a.gid > ?")
               .append(" OR (a.gid = ? AND a.aid > ?)")
               .append(" OR (a.gid = ? AND a.aid = ? AND v.version > ?))");
            params.add(after[0]);
            params.add(after[0]); params.add(after[1]);
            params.add(after[0]); params.add(after[1]); params.add(after[2]);
        }
        sql.append(" ORDER BY a.gid, a.aid, v.version");
        sql.append(" LIMIT ").append(Math.max(1, pageSize)); // bounded page

        List<String[]> out = new ArrayList<>();
        try (Connection conn = getReadConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setString(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)});
                }
            }
        } catch (SQLException e) {
            log.error("selectVersionsToMineAfter failed", e);
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
        try (Connection conn = getReadConnection();
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
                    // id is an INTEGER PRIMARY KEY (rowid alias): omit it and let
                    // SQLite assign, then read it back on the same connection.
                    try (PreparedStatement ins = conn.prepareStatement(
                            "INSERT INTO meta_artifacts (gid, aid) VALUES (?, ?)")) {
                        ins.setString(1, meta.gid);
                        ins.setString(2, meta.aid);
                        ins.executeUpdate();
                    }
                    gaId = lastInsertRowId(conn);
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

    /**
     * SQL that recomputes {@code generated}/{@code latest}/{@code release} for every
     * artifact whose id is produced by {@code scopeSubquery}. {@code latest} is the
     * version with the newest publish date (Maven's "last deployed" semantics);
     * {@code release} is the same excluding {@code -SNAPSHOT} versions;
     * {@code release} falls back to {@code latest} when only snapshots exist.
     *
     * <p>Replaces DuckDB's {@code arg_max(version, published)} (+ {@code FILTER})
     * with {@code ROW_NUMBER() OVER (... ORDER BY published DESC NULLS LAST)}.
     * Like {@code arg_max}, rows with a NULL {@code published} are never chosen
     * (the {@code published IS NOT NULL} guards), so an artifact whose versions all
     * lack publish dates gets NULL {@code latest}/{@code release} — identical
     * semantics. Artifacts in scope with no {@code meta_versions} rows at all are
     * left untouched (no {@code sub} match), as before. The snapshot test uses
     * {@code GLOB} (case-sensitive, matching Maven's literal {@code -SNAPSHOT}
     * suffix and the old case-sensitive DuckDB {@code LIKE}) — SQLite's own
     * {@code LIKE} is case-insensitive for ASCII and would wrongly exclude e.g.
     * {@code 1.0-snapshot}-suffixed release versions.
     */
    private static String summaryRefreshSql(String scopeSubquery) {
        return "UPDATE meta_artifacts AS a SET " +
               "generated = '" + Instant.now() + "', " +
               "latest = sub.latest, " +
               "release = COALESCE(sub.release, sub.latest) " +
               "FROM (" +
               "  SELECT ga_id, " +
               "         MAX(CASE WHEN rn = 1 AND published IS NOT NULL THEN version END) AS latest, " +
               "         MAX(CASE WHEN rn_rel = 1 AND is_rel = 1 AND published IS NOT NULL THEN version END) AS release " +
               "  FROM (" +
               "    SELECT v.ga_id AS ga_id, v.version AS version, v.published AS published, " +
               "           CASE WHEN v.version NOT GLOB '*-SNAPSHOT' THEN 1 ELSE 0 END AS is_rel, " +
               "           ROW_NUMBER() OVER (PARTITION BY v.ga_id " +
               "                              ORDER BY v.published DESC NULLS LAST) AS rn, " +
               "           ROW_NUMBER() OVER (PARTITION BY v.ga_id, " +
               "                              CASE WHEN v.version NOT GLOB '*-SNAPSHOT' THEN 1 ELSE 0 END " +
               "                              ORDER BY v.published DESC NULLS LAST) AS rn_rel " +
               "    FROM meta_versions v " +
               "    WHERE v.ga_id IN (" + scopeSubquery + ")" +
               "  ) ranked GROUP BY ga_id" +
               ") AS sub WHERE a.id = sub.ga_id";
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
        // Distinct meta_artifacts ids touched by this sync (adds + removes). Their
        // generated/latest/release rows are recomputed in one set-based pass at the
        // end (refreshSummaries) instead of per-artifact.
        private final Set<Integer> touchedGaIds = new HashSet<>();
        private int sinceCommit = 0;

        private IndexSyncWriter() throws SQLException {
            conn = getConnection();
            conn.setAutoCommit(false);
            selArtifact = conn.prepareStatement("SELECT id FROM meta_artifacts WHERE gid = ? AND aid = ?");
            insArtifact = conn.prepareStatement("INSERT INTO meta_artifacts (gid, aid) VALUES (?, ?)");
            insVersion = conn.prepareStatement(
                    "INSERT INTO meta_versions (ga_id, version, published, missing_pom, packaging, file_extension, " +
                    "file_size, sha1, sha256, has_sources, has_javadoc) " +
                    "VALUES (?, ?, ?, false, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (ga_id, version) DO NOTHING");
            delVersion = conn.prepareStatement("DELETE FROM meta_versions WHERE ga_id = ? AND version = ?");
        }

        // SELECT-then-INSERT (cached): we only INSERT when truly absent, so no
        // ON CONFLICT is needed on the artifact row. The (gid,aid) unique index
        // serves the point lookup; the new id comes from last_insert_rowid().
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
                insArtifact.executeUpdate();
                id = lastInsertRowId(conn);
            }
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
                touchedGaIds.add(gaId);
                insVersion.setInt(1, gaId);
                insVersion.setString(2, version);
                insVersion.setString(3, fileModifiedMillis != null ? Instant.ofEpochMilli(fileModifiedMillis).toString() : null);
                insVersion.setString(4, packaging);
                insVersion.setString(5, fileExtension);
                if (fileSize != null) insVersion.setLong(6, fileSize); else insVersion.setNull(6, java.sql.Types.BIGINT);
                insVersion.setString(7, sha1);
                insVersion.setString(8, sha256);
                if (hasSources != null) insVersion.setBoolean(9, hasSources);
                else insVersion.setNull(9, java.sql.Types.BOOLEAN);
                if (hasJavadoc != null) insVersion.setBoolean(10, hasJavadoc);
                else insVersion.setNull(10, java.sql.Types.BOOLEAN);
                int n = insVersion.executeUpdate();
                maybeCommit();
                return n > 0;
            } catch (SQLException e) {
                log.error("index-sync add {}:{}:{} failed", gid, aid, version, e);
                return false;
            }
        }

        /**
         * Recompute generated/latest/release for every artifact touched by this sync
         * in one set-based pass: stage the touched ids in a temp table and run a
         * single grouped window pass over {@code meta_versions}, mirroring the
         * full-sync merge. (Per-artifact point lookups would also be fine under
         * SQLite's {@code ga_id} index; the set-based form is kept because it is
         * still fewer statements and one shared scan.)
         * Call once, after all addVersion/removeVersion calls.
         */
        public void refreshSummaries() {
            if (touchedGaIds.isEmpty()) return;
            try {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TEMP TABLE IF NOT EXISTS idx_touched (ga_id INTEGER)");
                    st.execute("DELETE FROM idx_touched");
                }
                try (PreparedStatement ins = conn.prepareStatement("INSERT INTO idx_touched VALUES (?)")) {
                    for (Integer gaId : touchedGaIds) {
                        ins.setInt(1, gaId);
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
                try (Statement st = conn.createStatement()) {
                    st.execute(summaryRefreshSql("SELECT ga_id FROM idx_touched"));
                    st.execute("DROP TABLE IF EXISTS idx_touched");
                }
                conn.commit();
            } catch (SQLException e) {
                log.error("index-sync set-based summary refresh failed ({} artifacts touched)", touchedGaIds.size(), e);
            }
        }

        /** Remove a version (incremental ARTIFACT_REMOVE record). */
        public void removeVersion(String gid, String aid, String version) {
            if (gid == null || aid == null || version == null) return;
            try {
                int gaId = artifactId(gid, aid);
                touchedGaIds.add(gaId);
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

    /**
     * Open a bulk loader for the FULL index bootstrap. Records (in the ~100M range,
     * mostly duplicate file-records) are batch-inserted into a constraint-free
     * staging table, then merged set-based: collapsed to distinct versions once,
     * new artifacts bulk-created, versions bulk-inserted. This avoids ~100M
     * per-row upsert probes on the live tables. Used for the full bootstrap and
     * for the ADD records of an incremental sync. Incremental removals
     * (ARTIFACT_REMOVE) are applied separately via {@link IndexSyncWriter}.
     *
     * <p>Note: the staging table lives in {@code graph.sqlite} and grows the file
     * for the duration; the pages are reused after the drop, but run
     * {@code db compact --rewrite} afterwards to shrink the file itself.
     */
    public IndexStageLoader openIndexStage() throws SQLException {
        return new IndexStageLoader();
    }

    /** Bulk staging loader; see {@link #openIndexStage()}. Not thread-safe. */
    public final class IndexStageLoader implements AutoCloseable {
        private static final int BATCH_EVERY = 5000;
        private final Connection conn;
        private final PreparedStatement insStage;
        private int pending = 0;

        private IndexStageLoader() throws SQLException {
            conn = getConnection();
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS idx_stage");
                st.execute("CREATE TABLE idx_stage (" +
                        "gid VARCHAR, aid VARCHAR, version VARCHAR, packaging VARCHAR, " +
                        "file_extension VARCHAR, file_size BIGINT, sha1 VARCHAR, sha256 VARCHAR, " +
                        "has_sources BOOLEAN, has_javadoc BOOLEAN, published VARCHAR)");
            }
            conn.setAutoCommit(false);
            insStage = conn.prepareStatement(
                    "INSERT INTO idx_stage (gid, aid, version, packaging, file_extension, file_size, " +
                    "sha1, sha256, has_sources, has_javadoc, published) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        }

        /**
         * Append one (main-artifact) record. Caller should only pass records with an
         * empty classifier — the main jar/pom. Null strings are stored as ""
         * (normalised back to NULL in {@link #merge()}); a null size becomes 0.
         * Rows are batched and committed every few thousand.
         */
        public void append(String gid, String aid, String version, String packaging, String fileExtension,
                           Long fileSize, String sha1, String sha256, Boolean hasSources, Boolean hasJavadoc,
                           Long publishedMillis) throws SQLException {
            insStage.setString(1, gid);
            insStage.setString(2, aid);
            insStage.setString(3, version);
            insStage.setString(4, packaging != null ? packaging : "");
            insStage.setString(5, fileExtension != null ? fileExtension : "");
            insStage.setLong(6, fileSize != null ? fileSize : 0L);
            insStage.setString(7, sha1 != null ? sha1 : "");
            insStage.setString(8, sha256 != null ? sha256 : "");
            insStage.setBoolean(9, hasSources != null && hasSources);
            insStage.setBoolean(10, hasJavadoc != null && hasJavadoc);
            insStage.setString(11, publishedMillis != null ? Instant.ofEpochMilli(publishedMillis).toString() : "");
            insStage.addBatch();
            if (++pending >= BATCH_EVERY) {
                flushStage();
            }
        }

        private void flushStage() throws SQLException {
            if (pending == 0) return;
            insStage.executeBatch();
            conn.commit();
            pending = 0;
        }

        /**
         * Flush staging and merge into the meta tables, then drop staging. For each
         * (gid,aid,version) the representative file is the largest staged record (the
         * main jar over its pom), and its fields populate the version row. An existing
         * version row is upgraded when the staged record's file is strictly larger
         * (largest-wins holds across batched merges of one pull; a jar record arriving
         * in a later batch replaces the pom record's fields from an earlier one) —
         * {@code missing_pom} is never touched, and {@code published} only fills a gap.
         * @return {@code [newArtifacts, newOrUpgradedVersions]}.
         */
        public long[] merge() throws SQLException {
            flushStage();
            long newArtifacts;
            long newVersions;
            try (Statement st = conn.createStatement()) {
                // Bulk-create artifacts not already present. id is omitted:
                // SQLite assigns the INTEGER PRIMARY KEY (rowid) per inserted row.
                newArtifacts = st.executeUpdate(
                        "INSERT INTO meta_artifacts (gid, aid) " +
                        "SELECT s.gid, s.aid " +
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
                        // Largest-file-wins across merges too: upgrade an existing row's
                        // main-artifact fields when the staged file is strictly larger
                        // (e.g. the jar record lands in a later batch than the pom's).
                        // Never touches missing_pom (owned by fetch/mine); published only
                        // fills a gap. rn = 1 guarantees one row per key per statement.
                        // (The SELECT's WHERE clause also disambiguates the upsert for
                        // SQLite's INSERT ... SELECT ... ON CONFLICT parser.)
                        "ON CONFLICT (ga_id, version) DO UPDATE SET " +
                        "packaging = excluded.packaging, " +
                        "file_extension = excluded.file_extension, " +
                        "file_size = excluded.file_size, " +
                        "sha1 = excluded.sha1, " +
                        "sha256 = excluded.sha256, " +
                        "has_sources = excluded.has_sources, " +
                        "has_javadoc = excluded.has_javadoc, " +
                        "published = COALESCE(excluded.published, published) " +
                        "WHERE COALESCE(excluded.file_size, 0) > COALESCE(file_size, 0)");
                // Refresh artifact-level summary (generated/latest/release) for the
                // artifacts in this sync, scoped to staged coordinates.
                st.execute(summaryRefreshSql(
                        "SELECT a2.id FROM meta_artifacts a2 " +
                        "JOIN (SELECT DISTINCT gid, aid FROM idx_stage) s ON a2.gid = s.gid AND a2.aid = s.aid"));
                st.execute("DROP TABLE IF EXISTS idx_stage");
            }
            conn.commit();
            // Fold the WAL back into the main file now the merge is durable.
            // Must run outside a transaction (autoCommit back on); best-effort.
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            } catch (SQLException e) {
                log.debug("post-merge wal_checkpoint failed (harmless): {}", e.getMessage());
            }
            return new long[]{newArtifacts, newVersions};
        }

        @Override
        public void close() {
            // Un-merged staged rows are deliberately discarded (rolled back here,
            // and idx_stage is dropped on the next open); merge() is the commit point.
            try { insStage.close(); } catch (SQLException ignore) { /* ignore */ }
            try { if (!conn.getAutoCommit()) conn.rollback(); } catch (SQLException ignore) { /* ignore */ }
            try { conn.close(); } catch (SQLException e) { log.error("closing index-stage connection failed", e); }
        }
    }

    /** Insert/update one record's artifact row and replace its version set. No transaction management. */
    private void writeOne(Connection conn, MavenMetaData meta) throws SQLException {
        int gaId = getOrInsertArtifact(conn, meta);
        updateArtifactFields(conn, gaId, meta);

        // Make the table mirror the in-memory version set: upsert each version,
        // then delete the keys that are no longer present. (Upsert rather than
        // DELETE-all-then-INSERT so rows that survive keep their identity and
        // the write touches only what changed.)
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
                "INSERT INTO meta_artifacts (gid, aid) VALUES (?, ?)")) {
            ins.setString(1, meta.gid);
            ins.setString(2, meta.aid);
            ins.executeUpdate();
        }
        return lastInsertRowId(conn);
    }

    /** The rowid assigned by the most recent INSERT on {@code conn}. */
    private static int lastInsertRowId(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
            if (rs.next()) return rs.getInt(1);
        }
        throw new SQLException("last_insert_rowid() returned no row");
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
        try (Connection conn = getReadConnection();
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
        try (Connection conn = getReadConnection()) {
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
        try (Connection conn = getReadConnection();
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
        try (Connection conn = getReadConnection();
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

    /** One artifact's catalogue data needed to synthesise a {@code maven-metadata.xml}. */
    public record ArtifactMetadata(String gid, String aid, String latest, String release, List<String> versions) {}

    /**
     * Stream every catalogued artifact and its versions to {@code sink}, one call per
     * {@code (groupId, artifactId)}, with {@code versions} ordered by publish date
     * ascending — a best-effort proxy for Maven Central's deployment order (the order
     * Central lists them in; it is NOT a semantic version sort, and Aether re-sorts
     * internally when resolving ranges, so the order is cosmetic for resolution).
     *
     * <p>Backed by a single ordered join over {@code meta_artifacts}/{@code meta_versions};
     * SQLite does the (disk-backed, {@code temp_store=FILE}) sort and rows are grouped on
     * the client, so heap stays bounded to one artifact's version list regardless of
     * catalogue size (no full materialisation, unlike {@link #loadAll()}).
     * {@code gidFilter}/{@code aidFilter} may be null to scope to a group, a
     * group+artifact, or the whole catalogue.
     */
    public void streamArtifactMetadata(String gidFilter, String aidFilter,
                                       java.util.function.Consumer<ArtifactMetadata> sink) {
        StringBuilder sql = new StringBuilder(
                "SELECT a.gid AS gid, a.aid AS aid, a.latest AS latest, a.release AS release, v.version AS version " +
                "FROM meta_artifacts a JOIN meta_versions v ON a.id = v.ga_id ");
        List<String> conds = new ArrayList<>();
        if (gidFilter != null) conds.add("a.gid = ?");
        if (aidFilter != null) conds.add("a.aid = ?");
        if (!conds.isEmpty()) sql.append("WHERE ").append(String.join(" AND ", conds)).append(' ');
        // NULLS LAST is deliberate: SQLite's default puts NULLs first ascending,
        // which would push undated versions to the front of the list.
        sql.append("ORDER BY a.gid, a.aid, v.published NULLS LAST, v.version");

        try (Connection conn = getReadConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            if (gidFilter != null) ps.setString(idx++, gidFilter);
            if (aidFilter != null) ps.setString(idx, aidFilter);
            try (ResultSet rs = ps.executeQuery()) {
                String curG = null, curA = null, latest = null, release = null;
                List<String> versions = new ArrayList<>();
                while (rs.next()) {
                    String g = rs.getString("gid");
                    String a = rs.getString("aid");
                    if (curG == null || !g.equals(curG) || !a.equals(curA)) {
                        if (curG != null) sink.accept(new ArtifactMetadata(curG, curA, latest, release, versions));
                        curG = g; curA = a;
                        latest = rs.getString("latest");
                        release = rs.getString("release");
                        versions = new ArrayList<>();
                    }
                    String v = rs.getString("version");
                    if (v != null) versions.add(v);
                }
                if (curG != null) sink.accept(new ArtifactMetadata(curG, curA, latest, release, versions));
            }
        } catch (SQLException e) {
            log.error("streamArtifactMetadata failed", e);
            throw new RuntimeException("streamArtifactMetadata failed: " + e.getMessage(), e);
        }
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
