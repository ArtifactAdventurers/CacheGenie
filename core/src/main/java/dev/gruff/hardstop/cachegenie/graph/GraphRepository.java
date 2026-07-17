package dev.gruff.hardstop.cachegenie.graph;

import dev.gruff.hardstop.cachegenie.entities.MinedPom;
import dev.gruff.hardstop.resolver.Resolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GraphRepository {
    private static final Logger log = LoggerFactory.getLogger(GraphRepository.class);
    private final String dbPath;

    public GraphRepository(File cacheGenieRoot) {
        this.dbPath = new File(cacheGenieRoot, "graph.db").getAbsolutePath();
        initSchema();
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:duckdb:" + dbPath);
    }

    /**
     * Point DuckDB at an explicit spill directory (next to the database), stream large
     * {@code INSERT ... SELECT} results instead of buffering them to preserve row order,
     * and optionally cap DuckDB's memory/threads — so set-based work spills to disk
     * rather than exhausting RAM alongside the JVM. Mirrors
     * {@code MetaRepository.applyMemoryGuards} / {@code PomResolver.applyMemoryGuards};
     * settings are DB-wide while the database is open in this process. Best-effort:
     * failures are logged and work proceeds on defaults.
     */
    private void applyMemoryGuards(Connection conn, String memLimit, int dbThreads) {
        String tempDir = (dbPath + ".tmp").replace("'", "''");
        try (Statement st = conn.createStatement()) {
            st.execute("SET temp_directory = '" + tempDir + "'");
            try { st.execute("SET preserve_insertion_order = false"); } catch (SQLException ignore) { /* older DuckDB */ }
            if (dbThreads > 0) {
                st.execute("SET threads = " + dbThreads);
            }
            if (memLimit != null && !memLimit.isBlank()) {
                st.execute("SET memory_limit = '" + memLimit.trim().replace("'", "''") + "'");
            }
            log.info("Mining DB guards: memory_limit={}, threads={}, preserve_insertion_order=false, temp_directory={}.tmp",
                    (memLimit != null && !memLimit.isBlank()) ? memLimit.trim() : "<default ~80% RAM>",
                    dbThreads > 0 ? dbThreads : "<default: one per core>", dbPath);
        } catch (SQLException e) {
            log.warn("Could not apply DuckDB memory guards (continuing on defaults): {}", e.getMessage());
        }
    }

    private void initSchema() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS artifacts (" +
                    "id INTEGER PRIMARY KEY," +
                    "gid VARCHAR," +
                    "aid VARCHAR," +
                    "version VARCHAR," +
                    "classifier VARCHAR," +
                    "UNIQUE (gid, aid, version, classifier))");
            
            stmt.execute("CREATE SEQUENCE IF NOT EXISTS seq_artifact_id");
            
            stmt.execute("CREATE TABLE IF NOT EXISTS dependencies (" +
                    "parent_id INTEGER," +
                    "child_id INTEGER," +
                    "scope VARCHAR," +
                    "PRIMARY KEY (parent_id, child_id, scope))");

            initMiningSchema(stmt);

            log.info("DuckDB schema initialized at {}", dbPath);
        } catch (SQLException e) {
            log.error("Failed to initialize DuckDB schema", e);
        }
    }

    /**
     * Schema for the raw POM-mining pipeline (one descriptor-free fetch+parse per
     * artifact; the transitive/effective view is computed later in SQL).
     *
     * <p><b>Everything here is stored RAW — exactly as declared in <em>this</em>
     * pom.xml</b>: no parent inheritance, no {@code ${...}} interpolation, no
     * managed-version resolution. That is deliberate and is what lets a child be
     * mined without ever downloading its parent: the {@code parent_*} columns hold
     * the parent's <em>coordinates</em>, not a resolved id. Each parent/BOM is
     * itself an ordinary catalogue node that gets mined exactly once on its own
     * turn, so it is never refetched per-child. A later resolution pass walks
     * {@code parent_gid/aid/version} up the chain (and across import-scope BOMs in
     * {@code dependency_management}) to fill in inherited fields, interpolate
     * properties, and project resolvable {@link #direct_dep}-style edges into the
     * concrete {@code dependencies} table.</p>
     *
     * <p>No FK constraints (matching the existing tables) — referenced parent/dep
     * nodes legitimately may not exist yet at mine time. All tables key off
     * {@code artifacts.id} (the declaring POM's node).</p>
     */
    private void initMiningSchema(Statement stmt) throws SQLException {
        // One row per parsed POM. Identity/SCM/org fields are frequently INHERITED,
        // so they are often null on the child and only become known after resolution.
        stmt.execute("CREATE TABLE IF NOT EXISTS pom_meta (" +
                "artifact_id INTEGER PRIMARY KEY," +   // -> artifacts.id (this POM)
                "packaging VARCHAR," +
                // raw <parent> coordinates; null = no parent. Resolved to a node later.
                "parent_gid VARCHAR," +
                "parent_aid VARCHAR," +
                "parent_version VARCHAR," +
                "parent_relpath VARCHAR," +
                // project identity / provenance
                "name VARCHAR," +
                "description VARCHAR," +
                "url VARCHAR," +
                "inception_year VARCHAR," +
                "organization_name VARCHAR," +
                "organization_url VARCHAR," +
                // SCM
                "scm_url VARCHAR," +
                "scm_connection VARCHAR," +
                "scm_dev_connection VARCHAR," +
                "scm_tag VARCHAR," +
                // issue / CI management
                "issue_system VARCHAR," +
                "issue_url VARCHAR," +
                "ci_system VARCHAR," +
                "ci_url VARCHAR," +
                // bookkeeping (ISO-8601 via Instant.toString(), like the meta tables)
                "mined_at VARCHAR," +
                "deps_resolved BOOLEAN DEFAULT FALSE," +  // direct_dep -> dependencies done?
                "meta_resolved BOOLEAN DEFAULT FALSE)");  // inherited fields coalesced?

        // Declared direct dependencies, RAW. dep_version may be null (managed),
        // a property token (${...}) or a range — so these cannot yet be mapped to a
        // concrete child artifacts.id. Resolution projects resolvable rows into
        // `dependencies`. PK is (artifact_id, ord) so EVERY declared row is kept
        // verbatim — a POM that declares the same coordinate twice is preserved, not
        // collapsed; the resolver dedups if it wants to.
        stmt.execute("CREATE TABLE IF NOT EXISTS direct_dep (" +
                "artifact_id INTEGER," +               // -> artifacts.id (declaring POM)
                "ord INTEGER," +                       // declaration order within the POM
                "dep_gid VARCHAR," +
                "dep_aid VARCHAR," +
                "dep_version VARCHAR," +               // raw token; null when managed
                "scope VARCHAR," +                     // raw; null -> 'compile' at resolution
                "dep_type VARCHAR DEFAULT 'jar'," +
                "dep_classifier VARCHAR DEFAULT ''," +
                "optional BOOLEAN DEFAULT FALSE," +
                "PRIMARY KEY (artifact_id, ord))");

        // <dependencyManagement> entries, incl. import-scope BOMs (scope='import',
        // type='pom'). How a child's managed versions get resolved: look up the
        // parent/BOM node's rows here. PK (artifact_id, ord) — verbatim, as above.
        stmt.execute("CREATE TABLE IF NOT EXISTS dependency_management (" +
                "artifact_id INTEGER," +               // -> artifacts.id (declaring POM)
                "ord INTEGER," +                       // declaration order within the POM
                "dep_gid VARCHAR," +
                "dep_aid VARCHAR," +
                "dep_version VARCHAR," +               // may be a property token
                "scope VARCHAR," +                     // 'import' for BOMs
                "dep_type VARCHAR DEFAULT 'jar'," +    // 'pom' for BOMs
                "dep_classifier VARCHAR DEFAULT ''," +
                "PRIMARY KEY (artifact_id, ord))");

        // <properties>, for interpolating ${...} tokens during resolution.
        // Properties may also be inherited, so resolution walks parent_* here too.
        stmt.execute("CREATE TABLE IF NOT EXISTS pom_properties (" +
                "artifact_id INTEGER," +
                "prop_key VARCHAR," +                  // 'key'/'value' avoided (reserved-word risk)
                "prop_value VARCHAR," +
                "PRIMARY KEY (artifact_id, prop_key))");

        // <developers> and <contributors>.
        stmt.execute("CREATE TABLE IF NOT EXISTS pom_developers (" +
                "artifact_id INTEGER," +
                "ord INTEGER," +
                "role_kind VARCHAR," +                 // 'developer' | 'contributor'
                "dev_id VARCHAR," +
                "name VARCHAR," +
                "email VARCHAR," +
                "organization VARCHAR," +
                "organization_url VARCHAR," +
                "url VARCHAR," +
                "roles VARCHAR," +                     // comma-joined <roles>
                "PRIMARY KEY (artifact_id, ord))");

        // <licenses>.
        stmt.execute("CREATE TABLE IF NOT EXISTS pom_licenses (" +
                "artifact_id INTEGER," +
                "ord INTEGER," +
                "name VARCHAR," +
                "url VARCHAR," +
                "distribution VARCHAR," +
                "PRIMARY KEY (artifact_id, ord))");
    }

    private int getOrInsertArtifact(Connection conn, String gid, String aid, String version, String classifier) throws SQLException {
        if (classifier == null) classifier = "";
        // Try to select first
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT id FROM artifacts WHERE gid = ? AND aid = ? AND version = ? AND classifier = ?")) {
            pstmt.setString(1, gid);
            pstmt.setString(2, aid);
            pstmt.setString(3, version);
            pstmt.setString(4, classifier);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }

        // Not found, insert
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO artifacts (id, gid, aid, version, classifier) VALUES (nextval('seq_artifact_id'), ?, ?, ?, ?) RETURNING id")) {
            pstmt.setString(1, gid);
            pstmt.setString(2, aid);
            pstmt.setString(3, version);
            pstmt.setString(4, classifier);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        throw new SQLException("Failed to insert artifact and retrieve ID");
    }

    /**
     * Persist the <em>direct</em> dependency edges of one artifact (the direct-edge
     * graph model). Inserts the parent and each child into {@code artifacts} and the
     * edges into {@code dependencies}. Transitive trees are computed at query time
     * with recursive CTEs. {@code INSERT OR IGNORE} makes this idempotent.
     */
    /** Load already-graphed {@code "gid:aid:version"} keys for a group (or one artifact), for incremental skipping. */
    public Set<String> loadPresentKeys(String gid, String aid) {
        Set<String> keys = new HashSet<>();
        String sql = "SELECT gid, aid, version FROM artifacts WHERE gid = ?" + (aid != null ? " AND aid = ?" : "");
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, gid);
            if (aid != null) ps.setString(2, aid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getString(1) + ":" + rs.getString(2) + ":" + rs.getString(3));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load present keys for {}:{}", gid, aid, e);
        }
        return keys;
    }

    /**
     * Open a writer that reuses ONE connection for many direct-edge writes. The
     * per-call {@link #persistDirect} opens/commits/closes a fresh connection each
     * time, which — serialised behind a single-writer lock — caps throughput at a
     * few per second on a large DB. This writer holds the connection open, caches
     * artifact ids, reuses prepared statements, and commits in batches. Drive it
     * from a single thread (e.g. all calls under one lock); use try-with-resources.
     */
    public DirectWriter openDirectWriter() throws SQLException {
        return new DirectWriter();
    }

    /** Reused-connection writer for {@code graph deps}; see {@link #openDirectWriter()}. */
    public final class DirectWriter implements AutoCloseable {
        private static final int COMMIT_EVERY = 200;
        private final Connection conn;
        private final PreparedStatement selArt;
        private final PreparedStatement insArt;
        private final PreparedStatement insEdge;
        private final PreparedStatement markMissing;
        private final Map<String, Integer> idCache = new HashMap<>();
        private int sinceCommit = 0;

        private DirectWriter() throws SQLException {
            conn = getConnection();
            conn.setAutoCommit(false);
            selArt = conn.prepareStatement(
                    "SELECT id FROM artifacts WHERE gid = ? AND aid = ? AND version = ? AND classifier = ''");
            insArt = conn.prepareStatement(
                    "INSERT INTO artifacts (id, gid, aid, version, classifier) VALUES (nextval('seq_artifact_id'), ?, ?, ?, '') RETURNING id");
            insEdge = conn.prepareStatement(
                    "INSERT OR IGNORE INTO dependencies (parent_id, child_id, scope) VALUES (?, ?, ?)");
            markMissing = conn.prepareStatement(
                    "UPDATE meta_versions SET missing_pom = TRUE WHERE version = ? " +
                    "AND ga_id = (SELECT id FROM meta_artifacts WHERE gid = ? AND aid = ?)");
        }

        private int artId(String gid, String aid, String version) throws SQLException {
            String key = gid + ":" + aid + ":" + version;
            Integer id = idCache.get(key);
            if (id != null) return id;
            selArt.setString(1, gid);
            selArt.setString(2, aid);
            selArt.setString(3, version);
            try (ResultSet rs = selArt.executeQuery()) {
                if (rs.next()) id = rs.getInt(1);
            }
            if (id == null) {
                insArt.setString(1, gid);
                insArt.setString(2, aid);
                insArt.setString(3, version);
                try (ResultSet rs = insArt.executeQuery()) {
                    if (rs.next()) id = rs.getInt(1);
                }
            }
            if (id == null) throw new SQLException("could not resolve artifact id for " + key);
            idCache.put(key, id);
            return id;
        }

        /** Persist one artifact's direct edges using the held connection. */
        public void persistDirect(String gid, String aid, String version, List<Resolver.DirectDep> deps) {
            try {
                int parentId = artId(gid, aid, version);
                for (Resolver.DirectDep d : deps) {
                    if (d.gid() == null || d.aid() == null || d.version() == null) continue;
                    int childId = artId(d.gid(), d.aid(), d.version());
                    if (childId == parentId) continue;
                    insEdge.setInt(1, parentId);
                    insEdge.setInt(2, childId);
                    insEdge.setString(3, d.scope() != null ? d.scope() : "");
                    insEdge.addBatch();
                }
                insEdge.executeBatch();
                maybeCommit();
            } catch (SQLException e) {
                log.error("persistDirect {}:{}:{} failed", gid, aid, version, e);
                rollbackQuiet();
            }
        }

        /** Mark a meta version's POM missing using the held connection. */
        public void markMissing(String gid, String aid, String version) {
            try {
                markMissing.setString(1, version);
                markMissing.setString(2, gid);
                markMissing.setString(3, aid);
                markMissing.executeUpdate();
                maybeCommit();
            } catch (SQLException e) {
                log.error("markMissing {}:{}:{} failed", gid, aid, version, e);
                rollbackQuiet();
            }
        }

        private void maybeCommit() throws SQLException {
            if (++sinceCommit >= COMMIT_EVERY) {
                conn.commit();
                sinceCommit = 0;
            }
        }

        private void rollbackQuiet() {
            try { conn.rollback(); } catch (SQLException ignore) { /* ignore */ }
            sinceCommit = 0;
        }

        @Override
        public void close() {
            try { conn.commit(); } catch (SQLException e) { log.error("final graph-deps commit failed", e); }
            for (PreparedStatement ps : new PreparedStatement[]{selArt, insArt, insEdge, markMissing}) {
                try { if (ps != null) ps.close(); } catch (SQLException ignore) { /* ignore */ }
            }
            try { conn.close(); } catch (SQLException e) { log.error("closing graph-deps connection failed", e); }
        }
    }

    /**
     * Open a writer that persists raw mined POMs in <b>bulk</b> (one {@link MinedPom}
     * → rows across {@code pom_meta}, {@code direct_dep}, {@code dependency_management},
     * {@code pom_properties}, {@code pom_developers}, {@code pom_licenses}).
     *
     * <p>Rows are staged via DuckDB's {@code Appender} into constraint-free,
     * coordinate-keyed staging tables and merged set-based every {@link
     * MiningWriter#FLUSH_EVERY} POMs (artifact ids are assigned once per chunk by a
     * single bulk {@code INSERT … SELECT}, then the {@code pom_*} rows are inserted by
     * joining staging to {@code artifacts} on coordinates). This replaces the old
     * ~14-statements-per-POM path, whose per-row {@code INSERT}/{@code DELETE} cost on
     * an OLAP engine — serialized behind a single writer — capped throughput at a few
     * tens/s regardless of fetch concurrency.
     *
     * <p><b>Not thread-safe.</b> {@code graph mine} now drives this from a single
     * drainer thread fed by the fetch workers, so DB writes never contend with — and
     * fully overlap — the network fetches. Use with try-with-resources; {@link #close()}
     * does a final flush.
     *
     * <p>Staging tables live in {@code graph.db} but are cleared after every chunk so
     * they stay tiny; a long run still grows the file, so run {@code db compact
     * --rewrite} afterwards as with {@code index-sync}.
     */
    public MiningWriter openMiningWriter() throws SQLException {
        return openMiningWriter(null, 0);
    }

    /**
     * As {@link #openMiningWriter()}, with explicit DuckDB caps: {@code memLimit}
     * (e.g. "2GB"; null/blank = DuckDB default ~80% RAM) and {@code dbThreads}
     * (0 = default). On small-RAM machines the flush merges join against the large
     * pom tables, so cap DuckDB below RAM minus the JVM's footprint.
     */
    public MiningWriter openMiningWriter(String memLimit, int dbThreads) throws SQLException {
        return new MiningWriter(memLimit, dbThreads);
    }

    /** Bulk, Appender-backed writer for {@code graph mine}; see {@link #openMiningWriter()}. */
    public final class MiningWriter implements AutoCloseable {
        /** Merge to the real tables once this many POMs have been staged. */
        private static final int FLUSH_EVERY = 4000;

        private final Connection conn;
        private final org.duckdb.DuckDBConnection duck;
        // One appender per coordinate-keyed staging table.
        private org.duckdb.DuckDBAppender apMeta, apDep, apDm, apProp, apDev, apLic, apMissing;
        private boolean appendersOpen = false;
        private int staged = 0;

        private MiningWriter(String memLimit, int dbThreads) throws SQLException {
            conn = getConnection();
            // The every-FLUSH_EVERY merge joins staging against the (large) pom tables;
            // without a spill directory and a memory cap DuckDB defaults to ~80% of
            // physical RAM *on top of* the JVM — the memory-pressure regime in which
            // the mine writer segfaulted natively on an 8GB box.
            applyMemoryGuards(conn, memLimit, dbThreads);
            conn.setAutoCommit(false);
            duck = conn.unwrap(org.duckdb.DuckDBConnection.class);
            createStaging();
            conn.commit();      // commit the staging DDL before any appender attaches
            openAppenders();
        }

        /** (Re)create the empty staging tables. Coordinates stand in for artifact ids; nulls are staged as "". */
        private void createStaging() throws SQLException {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS mine_s_meta");
                st.execute("DROP TABLE IF EXISTS mine_s_dep");
                st.execute("DROP TABLE IF EXISTS mine_s_dm");
                st.execute("DROP TABLE IF EXISTS mine_s_prop");
                st.execute("DROP TABLE IF EXISTS mine_s_dev");
                st.execute("DROP TABLE IF EXISTS mine_s_lic");
                st.execute("DROP TABLE IF EXISTS mine_s_missing");
                st.execute("CREATE TABLE mine_s_meta (gid VARCHAR, aid VARCHAR, version VARCHAR, " +
                        "packaging VARCHAR, parent_gid VARCHAR, parent_aid VARCHAR, parent_version VARCHAR, parent_relpath VARCHAR, " +
                        "name VARCHAR, description VARCHAR, url VARCHAR, inception_year VARCHAR, " +
                        "organization_name VARCHAR, organization_url VARCHAR, " +
                        "scm_url VARCHAR, scm_connection VARCHAR, scm_dev_connection VARCHAR, scm_tag VARCHAR, " +
                        "issue_system VARCHAR, issue_url VARCHAR, ci_system VARCHAR, ci_url VARCHAR, mined_at VARCHAR)");
                st.execute("CREATE TABLE mine_s_dep (gid VARCHAR, aid VARCHAR, version VARCHAR, ord INTEGER, " +
                        "dep_gid VARCHAR, dep_aid VARCHAR, dep_version VARCHAR, scope VARCHAR, dep_type VARCHAR, dep_classifier VARCHAR, optional BOOLEAN)");
                st.execute("CREATE TABLE mine_s_dm (gid VARCHAR, aid VARCHAR, version VARCHAR, ord INTEGER, " +
                        "dep_gid VARCHAR, dep_aid VARCHAR, dep_version VARCHAR, scope VARCHAR, dep_type VARCHAR, dep_classifier VARCHAR)");
                st.execute("CREATE TABLE mine_s_prop (gid VARCHAR, aid VARCHAR, version VARCHAR, prop_key VARCHAR, prop_value VARCHAR)");
                st.execute("CREATE TABLE mine_s_dev (gid VARCHAR, aid VARCHAR, version VARCHAR, ord INTEGER, " +
                        "role_kind VARCHAR, dev_id VARCHAR, name VARCHAR, email VARCHAR, organization VARCHAR, organization_url VARCHAR, url VARCHAR, roles VARCHAR)");
                st.execute("CREATE TABLE mine_s_lic (gid VARCHAR, aid VARCHAR, version VARCHAR, ord INTEGER, " +
                        "name VARCHAR, url VARCHAR, distribution VARCHAR)");
                st.execute("CREATE TABLE mine_s_missing (gid VARCHAR, aid VARCHAR, version VARCHAR)");
            }
        }

        private void openAppenders() throws SQLException {
            apMeta    = duck.createAppender("main", "mine_s_meta");
            apDep     = duck.createAppender("main", "mine_s_dep");
            apDm      = duck.createAppender("main", "mine_s_dm");
            apProp    = duck.createAppender("main", "mine_s_prop");
            apDev     = duck.createAppender("main", "mine_s_dev");
            apLic     = duck.createAppender("main", "mine_s_lic");
            apMissing = duck.createAppender("main", "mine_s_missing");
            appendersOpen = true;
        }

        private void closeAppenders() throws SQLException {
            if (!appendersOpen) return;
            for (org.duckdb.DuckDBAppender a : new org.duckdb.DuckDBAppender[]{apMeta, apDep, apDm, apProp, apDev, apLic, apMissing}) {
                if (a != null) a.close();
            }
            appendersOpen = false;
        }

        /** Null → "" so the Appender never sees a null string; NULLIF restores SQL NULL at merge for nullable columns. */
        private static String s(String v) { return v != null ? v : ""; }

        /** Stage one mined POM. Single-threaded (drainer only). Coordinates are the catalogue GAV (see {@code withCoordinates}). */
        public void appendMined(MinedPom p) {
            try {
                apMeta.beginRow();
                apMeta.append(s(p.gid())); apMeta.append(s(p.aid())); apMeta.append(s(p.version()));
                apMeta.append(s(p.packaging()));
                apMeta.append(s(p.parentGid())); apMeta.append(s(p.parentAid())); apMeta.append(s(p.parentVersion())); apMeta.append(s(p.parentRelPath()));
                apMeta.append(s(p.name())); apMeta.append(s(p.description())); apMeta.append(s(p.url())); apMeta.append(s(p.inceptionYear()));
                apMeta.append(s(p.organizationName())); apMeta.append(s(p.organizationUrl()));
                apMeta.append(s(p.scmUrl())); apMeta.append(s(p.scmConnection())); apMeta.append(s(p.scmDevConnection())); apMeta.append(s(p.scmTag()));
                apMeta.append(s(p.issueSystem())); apMeta.append(s(p.issueUrl())); apMeta.append(s(p.ciSystem())); apMeta.append(s(p.ciUrl()));
                apMeta.append(Instant.now().toString());
                apMeta.endRow();

                int ord = 0;
                for (MinedPom.RawDep d : p.dependencies()) {
                    apDep.beginRow();
                    apDep.append(s(p.gid())); apDep.append(s(p.aid())); apDep.append(s(p.version())); apDep.append(ord++);
                    apDep.append(s(d.gid())); apDep.append(s(d.aid())); apDep.append(s(d.version()));
                    apDep.append(s(d.scope())); apDep.append(s(d.type())); apDep.append(s(d.classifier())); apDep.append(d.optional());
                    apDep.endRow();
                }
                ord = 0;
                for (MinedPom.RawDep d : p.dependencyManagement()) {
                    apDm.beginRow();
                    apDm.append(s(p.gid())); apDm.append(s(p.aid())); apDm.append(s(p.version())); apDm.append(ord++);
                    apDm.append(s(d.gid())); apDm.append(s(d.aid())); apDm.append(s(d.version()));
                    apDm.append(s(d.scope())); apDm.append(s(d.type())); apDm.append(s(d.classifier()));
                    apDm.endRow();
                }
                for (Map.Entry<String, String> e : p.properties().entrySet()) {
                    apProp.beginRow();
                    apProp.append(s(p.gid())); apProp.append(s(p.aid())); apProp.append(s(p.version()));
                    apProp.append(s(e.getKey())); apProp.append(s(e.getValue()));
                    apProp.endRow();
                }
                ord = 0;
                for (MinedPom.Dev d : p.developers()) {
                    apDev.beginRow();
                    apDev.append(s(p.gid())); apDev.append(s(p.aid())); apDev.append(s(p.version())); apDev.append(ord++);
                    apDev.append(s(d.roleKind())); apDev.append(s(d.id())); apDev.append(s(d.name())); apDev.append(s(d.email()));
                    apDev.append(s(d.organization())); apDev.append(s(d.organizationUrl())); apDev.append(s(d.url())); apDev.append(s(d.roles()));
                    apDev.endRow();
                }
                ord = 0;
                for (MinedPom.License l : p.licenses()) {
                    apLic.beginRow();
                    apLic.append(s(p.gid())); apLic.append(s(p.aid())); apLic.append(s(p.version())); apLic.append(ord++);
                    apLic.append(s(l.name())); apLic.append(s(l.url())); apLic.append(s(l.distribution()));
                    apLic.endRow();
                }
                maybeFlush();
            } catch (SQLException e) {
                log.error("appendMined {}:{}:{} failed", p.gid(), p.aid(), p.version(), e);
            }
        }

        /** Stage one missing-POM marker (bulk-applied to {@code meta_versions} at merge). Single-threaded. */
        public void appendMissing(String gid, String aid, String version) {
            try {
                apMissing.beginRow();
                apMissing.append(s(gid)); apMissing.append(s(aid)); apMissing.append(s(version));
                apMissing.endRow();
                maybeFlush();
            } catch (SQLException e) {
                log.error("appendMissing {}:{}:{} failed", gid, aid, version, e);
            }
        }

        private void maybeFlush() throws SQLException {
            if (++staged >= FLUSH_EVERY) flush();
        }

        /**
         * Flush staged rows to the real tables and clear staging. Closes the appenders
         * (so their rows are visible), assigns artifact ids set-based, clears any prior
         * {@code pom_*} rows for the staged coordinates (idempotent re-mine), inserts
         * all child rows by joining on coordinates, applies missing markers, then
         * truncates staging and reopens the appenders. NULLIF restores SQL NULL for the
         * nullable text columns (parent/identity/scm/dep coordinates/scope/dev/licence);
         * columns the parser never leaves null ({@code packaging}, {@code dep_type},
         * {@code dep_classifier}, {@code prop_*}, {@code role_kind}, {@code mined_at})
         * are stored verbatim to preserve the original "" vs NULL distinction.
         */
        public void flush() {
            try {
                closeAppenders();
                try (Statement st = conn.createStatement()) {
                    // 1. Assign ids to staged coordinates not already in `artifacts`.
                    st.execute("INSERT INTO artifacts (id, gid, aid, version, classifier) " +
                            "SELECT nextval('seq_artifact_id'), s.gid, s.aid, s.version, '' " +
                            "FROM (SELECT DISTINCT gid, aid, version FROM mine_s_meta) s " +
                            "WHERE NOT EXISTS (SELECT 1 FROM artifacts a " +
                            "  WHERE a.gid = s.gid AND a.aid = s.aid AND a.version = s.version AND a.classifier = '')");

                    // 2. Idempotent clear: drop any prior pom_* rows for the staged artifacts
                    //    (one set-based delete per table per chunk, vs 6 per POM before).
                    String stagedIds =
                            "SELECT a.id FROM artifacts a JOIN (SELECT DISTINCT gid, aid, version FROM mine_s_meta) s " +
                            "ON a.gid = s.gid AND a.aid = s.aid AND a.version = s.version AND a.classifier = ''";
                    st.execute("DELETE FROM pom_meta WHERE artifact_id IN (" + stagedIds + ")");
                    st.execute("DELETE FROM direct_dep WHERE artifact_id IN (" + stagedIds + ")");
                    st.execute("DELETE FROM dependency_management WHERE artifact_id IN (" + stagedIds + ")");
                    st.execute("DELETE FROM pom_properties WHERE artifact_id IN (" + stagedIds + ")");
                    st.execute("DELETE FROM pom_developers WHERE artifact_id IN (" + stagedIds + ")");
                    st.execute("DELETE FROM pom_licenses WHERE artifact_id IN (" + stagedIds + ")");

                    // 3. Insert pom_* rows, mapping coordinates -> artifacts.id via join.
                    st.execute("INSERT INTO pom_meta (artifact_id, packaging, parent_gid, parent_aid, parent_version, parent_relpath, " +
                            "name, description, url, inception_year, organization_name, organization_url, " +
                            "scm_url, scm_connection, scm_dev_connection, scm_tag, issue_system, issue_url, ci_system, ci_url, mined_at) " +
                            "SELECT a.id, NULLIF(s.packaging,''), NULLIF(s.parent_gid,''), NULLIF(s.parent_aid,''), NULLIF(s.parent_version,''), NULLIF(s.parent_relpath,''), " +
                            "NULLIF(s.name,''), NULLIF(s.description,''), NULLIF(s.url,''), NULLIF(s.inception_year,''), NULLIF(s.organization_name,''), NULLIF(s.organization_url,''), " +
                            "NULLIF(s.scm_url,''), NULLIF(s.scm_connection,''), NULLIF(s.scm_dev_connection,''), NULLIF(s.scm_tag,''), " +
                            "NULLIF(s.issue_system,''), NULLIF(s.issue_url,''), NULLIF(s.ci_system,''), NULLIF(s.ci_url,''), s.mined_at " +
                            "FROM mine_s_meta s JOIN artifacts a ON a.gid = s.gid AND a.aid = s.aid AND a.version = s.version AND a.classifier = ''");

                    st.execute("INSERT INTO direct_dep (artifact_id, ord, dep_gid, dep_aid, dep_version, scope, dep_type, dep_classifier, optional) " +
                            "SELECT a.id, s.ord, NULLIF(s.dep_gid,''), NULLIF(s.dep_aid,''), NULLIF(s.dep_version,''), NULLIF(s.scope,''), s.dep_type, s.dep_classifier, s.optional " +
                            "FROM mine_s_dep s JOIN artifacts a ON a.gid = s.gid AND a.aid = s.aid AND a.version = s.version AND a.classifier = ''");

                    st.execute("INSERT INTO dependency_management (artifact_id, ord, dep_gid, dep_aid, dep_version, scope, dep_type, dep_classifier) " +
                            "SELECT a.id, s.ord, NULLIF(s.dep_gid,''), NULLIF(s.dep_aid,''), NULLIF(s.dep_version,''), NULLIF(s.scope,''), s.dep_type, s.dep_classifier " +
                            "FROM mine_s_dm s JOIN artifacts a ON a.gid = s.gid AND a.aid = s.aid AND a.version = s.version AND a.classifier = ''");

                    st.execute("INSERT INTO pom_properties (artifact_id, prop_key, prop_value) " +
                            "SELECT a.id, s.prop_key, s.prop_value " +
                            "FROM mine_s_prop s JOIN artifacts a ON a.gid = s.gid AND a.aid = s.aid AND a.version = s.version AND a.classifier = ''");

                    st.execute("INSERT INTO pom_developers (artifact_id, ord, role_kind, dev_id, name, email, organization, organization_url, url, roles) " +
                            "SELECT a.id, s.ord, s.role_kind, NULLIF(s.dev_id,''), NULLIF(s.name,''), NULLIF(s.email,''), NULLIF(s.organization,''), NULLIF(s.organization_url,''), NULLIF(s.url,''), NULLIF(s.roles,'') " +
                            "FROM mine_s_dev s JOIN artifacts a ON a.gid = s.gid AND a.aid = s.aid AND a.version = s.version AND a.classifier = ''");

                    st.execute("INSERT INTO pom_licenses (artifact_id, ord, name, url, distribution) " +
                            "SELECT a.id, s.ord, NULLIF(s.name,''), NULLIF(s.url,''), NULLIF(s.distribution,'') " +
                            "FROM mine_s_lic s JOIN artifacts a ON a.gid = s.gid AND a.aid = s.aid AND a.version = s.version AND a.classifier = ''");

                    // 4. Bulk-apply missing markers via correlated EXISTS (DuckDB doesn't
                    //    accept the row-value "(ga_id, version) IN (SELECT two cols)" form).
                    st.execute("UPDATE meta_versions AS mv SET missing_pom = TRUE " +
                            "WHERE EXISTS (" +
                            "  SELECT 1 FROM mine_s_missing s " +
                            "  JOIN meta_artifacts ma ON ma.gid = s.gid AND ma.aid = s.aid " +
                            "  WHERE ma.id = mv.ga_id AND s.version = mv.version)");

                    // 5. Clear staging for the next chunk.
                    st.execute("DELETE FROM mine_s_meta");
                    st.execute("DELETE FROM mine_s_dep");
                    st.execute("DELETE FROM mine_s_dm");
                    st.execute("DELETE FROM mine_s_prop");
                    st.execute("DELETE FROM mine_s_dev");
                    st.execute("DELETE FROM mine_s_lic");
                    st.execute("DELETE FROM mine_s_missing");
                }
                conn.commit();
                staged = 0;
                openAppenders();
            } catch (SQLException e) {
                log.error("graph-mine flush failed (chunk of {} dropped; those versions stay un-mined and resume next run)", staged, e);
                // Recover so the drainer can keep going: roll back the half-merged chunk and start a clean staging buffer.
                try { conn.rollback(); } catch (SQLException ignore) { /* ignore */ }
                staged = 0;
                try { if (!appendersOpen) { createStaging(); openAppenders(); conn.commit(); } }
                catch (SQLException re) { log.error("graph-mine flush recovery failed", re); }
            }
        }

        @Override
        public void close() {
            try { flush(); } catch (Exception e) { log.error("final graph-mine flush failed", e); }
            try { closeAppenders(); } catch (SQLException ignore) { /* ignore */ }
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS mine_s_meta");
                st.execute("DROP TABLE IF EXISTS mine_s_dep");
                st.execute("DROP TABLE IF EXISTS mine_s_dm");
                st.execute("DROP TABLE IF EXISTS mine_s_prop");
                st.execute("DROP TABLE IF EXISTS mine_s_dev");
                st.execute("DROP TABLE IF EXISTS mine_s_lic");
                st.execute("DROP TABLE IF EXISTS mine_s_missing");
                conn.commit();
            } catch (SQLException e) { log.error("dropping graph-mine staging failed", e); }
            try { conn.close(); } catch (SQLException e) { log.error("closing graph-mine connection failed", e); }
        }
    }

    public synchronized void persistDirect(String gid, String aid, String version, List<Resolver.DirectDep> deps) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                int parentId = getOrInsertArtifact(conn, gid, aid, version, "");
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR IGNORE INTO dependencies (parent_id, child_id, scope) VALUES (?, ?, ?)")) {
                    for (Resolver.DirectDep d : deps) {
                        if (d.gid() == null || d.aid() == null || d.version() == null) continue;
                        int childId = getOrInsertArtifact(conn, d.gid(), d.aid(), d.version(), "");
                        if (childId == parentId) continue;
                        ps.setInt(1, parentId);
                        ps.setInt(2, childId);
                        ps.setString(3, d.scope() != null ? d.scope() : "");
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.error("Failed to persist direct deps for {}:{}:{}", gid, aid, version, e);
        }
    }

    /** Stats from {@link #importGoblinEdges}. */
    public record GoblinImportStats(long artifactsAdded, long edgesAdded, long rowsRead, long rowsSkipped) {}

    /**
     * Bulk-load a Goblin dependency-edge CSV into {@code artifacts}/{@code dependencies},
     * set-based via DuckDB (no row-by-row JDBC). The CSV is the export of Goblin's
     * {@code (Release)-[:dependency]->(Artifact)} relationships, one row per edge with
     * header columns {@code source,targetArtifact,targetVersion,scope} where
     * {@code source} is {@code g:a:v}, {@code targetArtifact} is {@code g:a}, and
     * {@code targetVersion} is the (Aether-resolved) dependency version.
     *
     * <p>Goblin's edges are effective/resolved direct dependencies (same as our
     * {@code graph deps}). {@code targetVersion} is occasionally a version <em>range</em>
     * rather than a concrete version; unless {@code includeRanges} is set, those edges
     * (and their non-existent child release) are skipped. Idempotent:
     * {@code INSERT OR IGNORE} on edges and existence-checked artifact inserts, so it
     * can be re-run or layered on top of an existing graph.
     */
    public GoblinImportStats importGoblinEdges(File edgesCsv, boolean includeRanges) {
        String path = edgesCsv.getAbsolutePath().replace("'", "''");
        // A "concrete" child version has no range/property syntax. LIKE (not regex) to keep escaping simple.
        String concrete = "(cver IS NOT NULL AND cver <> '' "
                + "AND cver NOT LIKE '%[%' AND cver NOT LIKE '%]%' AND cver NOT LIKE '%(%' "
                + "AND cver NOT LIKE '%)%' AND cver NOT LIKE '%,%' AND cver NOT LIKE '%$%')";
        String childWhere = includeRanges ? "TRUE" : concrete;

        long artBefore = 0, depBefore = 0, artAfter = 0, depAfter = 0, rows = 0, kept = 0;
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            conn.setAutoCommit(false);

            // Stage well-formed rows (source has g:a:v, target has g:a), pre-split.
            st.execute("CREATE TEMP TABLE ge AS SELECT "
                    + "split_part(\"source\", ':', 1) AS pgid, split_part(\"source\", ':', 2) AS paid, split_part(\"source\", ':', 3) AS pver, "
                    + "split_part(\"targetArtifact\", ':', 1) AS cgid, split_part(\"targetArtifact\", ':', 2) AS caid, "
                    + "\"targetVersion\" AS cver, \"scope\" AS scp "
                    + "FROM read_csv_auto('" + path + "', header=true) "
                    + "WHERE (length(\"source\") - length(replace(\"source\", ':', ''))) = 2 "
                    + "AND (length(\"targetArtifact\") - length(replace(\"targetArtifact\", ':', ''))) = 1");

            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ge")) { rs.next(); rows = rs.getLong(1); }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ge WHERE " + childWhere)) { rs.next(); kept = rs.getLong(1); }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM artifacts")) { rs.next(); artBefore = rs.getLong(1); }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM dependencies")) { rs.next(); depBefore = rs.getLong(1); }

            // Distinct GAVs: all parents (always concrete) + concrete children.
            st.execute("CREATE TEMP TABLE ge_gav AS "
                    + "SELECT DISTINCT pgid AS gid, paid AS aid, pver AS version FROM ge "
                    + "UNION SELECT DISTINCT cgid, caid, cver FROM ge WHERE " + childWhere);

            // Insert only the GAVs we don't already have (classifier '').
            st.execute("INSERT INTO artifacts (id, gid, aid, version, classifier) "
                    + "SELECT nextval('seq_artifact_id'), gid, aid, version, '' FROM ge_gav g "
                    + "WHERE g.gid <> '' AND g.aid <> '' AND g.version IS NOT NULL AND g.version <> '' "
                    + "AND NOT EXISTS (SELECT 1 FROM artifacts a WHERE a.gid = g.gid AND a.aid = g.aid AND a.version = g.version AND a.classifier = '')");

            // Insert edges, mapping coordinates to ids. INSERT OR IGNORE => idempotent.
            st.execute("INSERT OR IGNORE INTO dependencies (parent_id, child_id, scope) "
                    + "SELECT p.id, c.id, COALESCE(e.scp, '') FROM ge e "
                    + "JOIN artifacts p ON p.gid = e.pgid AND p.aid = e.paid AND p.version = e.pver AND p.classifier = '' "
                    + "JOIN artifacts c ON c.gid = e.cgid AND c.aid = e.caid AND c.version = e.cver AND c.classifier = '' "
                    + "WHERE " + childWhere + " AND p.id <> c.id");

            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM artifacts")) { rs.next(); artAfter = rs.getLong(1); }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM dependencies")) { rs.next(); depAfter = rs.getLong(1); }

            st.execute("DROP TABLE IF EXISTS ge_gav");
            st.execute("DROP TABLE IF EXISTS ge");
            conn.commit();
        } catch (SQLException e) {
            log.error("importGoblinEdges failed for {}", edgesCsv, e);
            return new GoblinImportStats(0, 0, 0, 0);
        }
        return new GoblinImportStats(artAfter - artBefore, depAfter - depBefore, rows, rows - kept);
    }

    /** Stats from {@link #exportNeo4jCsv}. */
    public record Neo4jExportStats(long releases, long libraries, long edges) {}

    /**
     * Export the graph as {@code neo4j-admin import} CSVs in <b>Goblin's schema</b>, so
     * the result is Weaver-compatible and conceptually mergeable with a loaded Goblin
     * dump. Writes four files into {@code outDir}:
     * <ul>
     *   <li>{@code releases.csv} — {@code :Release} nodes ({@code id:ID(Release)} = g:a:v, version, gid, aid;
     *       plus name/url/scmUrl when {@code withMetadata}).</li>
     *   <li>{@code libraries.csv} — {@code :Artifact} nodes ({@code id:ID(Artifact)} = g:a).</li>
     *   <li>{@code rel_ar.csv} — {@code (Artifact)-[:relationship_AR]->(Release)} versioning edges.</li>
     *   <li>{@code deps.csv} — {@code (Release)-[:dependency {targetVersion, scope}]->(Artifact)} edges
     *       (target is the dependency's library; the concrete version rides on the edge, as in Goblin).</li>
     * </ul>
     * DuckDB writes the CSVs directly ({@code COPY}); this is read-only on the DB.
     */
    public Neo4jExportStats exportNeo4jCsv(File outDir, boolean withMetadata) {
        if (!outDir.exists() && !outDir.mkdirs()) {
            log.error("could not create Neo4j export dir {}", outDir);
            return new Neo4jExportStats(0, 0, 0);
        }
        String dir = outDir.getAbsolutePath();

        String releaseSelect = withMetadata
                ? "SELECT a.gid || ':' || a.aid || ':' || a.version AS \"id:ID(Release)\", a.version AS \"version\", "
                  + "a.gid AS \"gid\", a.aid AS \"aid\", m.name AS \"name\", m.url AS \"url\", m.scm_url AS \"scmUrl\" "
                  + "FROM artifacts a LEFT JOIN pom_meta m ON m.artifact_id = a.id WHERE a.classifier = ''"
                : "SELECT gid || ':' || aid || ':' || version AS \"id:ID(Release)\", version AS \"version\", "
                  + "gid AS \"gid\", aid AS \"aid\" FROM artifacts WHERE classifier = ''";

        long releases = 0, libraries = 0, edges = 0;
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            st.execute("COPY (" + releaseSelect + ") TO '" + csvPath(dir, "releases.csv") + "' (FORMAT CSV, HEADER)");

            st.execute("COPY (SELECT DISTINCT gid || ':' || aid AS \"id:ID(Artifact)\", gid AS \"gid\", aid AS \"aid\" "
                    + "FROM artifacts WHERE classifier = '') TO '" + csvPath(dir, "libraries.csv") + "' (FORMAT CSV, HEADER)");

            st.execute("COPY (SELECT DISTINCT gid || ':' || aid AS \":START_ID(Artifact)\", "
                    + "gid || ':' || aid || ':' || version AS \":END_ID(Release)\" "
                    + "FROM artifacts WHERE classifier = '') TO '" + csvPath(dir, "rel_ar.csv") + "' (FORMAT CSV, HEADER)");

            st.execute("COPY (SELECT p.gid || ':' || p.aid || ':' || p.version AS \":START_ID(Release)\", "
                    + "c.gid || ':' || c.aid AS \":END_ID(Artifact)\", c.version AS \"targetVersion\", "
                    + "COALESCE(d.scope, '') AS \"scope\" "
                    + "FROM dependencies d JOIN artifacts p ON p.id = d.parent_id JOIN artifacts c ON c.id = d.child_id) "
                    + "TO '" + csvPath(dir, "deps.csv") + "' (FORMAT CSV, HEADER)");

            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM artifacts WHERE classifier = ''")) { rs.next(); releases = rs.getLong(1); }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(DISTINCT gid || ':' || aid) FROM artifacts WHERE classifier = ''")) { rs.next(); libraries = rs.getLong(1); }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM dependencies")) { rs.next(); edges = rs.getLong(1); }
        } catch (SQLException e) {
            log.error("exportNeo4jCsv failed", e);
            return new Neo4jExportStats(0, 0, 0);
        }
        return new Neo4jExportStats(releases, libraries, edges);
    }

    /** Build a single-quote-escaped CSV file path for embedding in a DuckDB COPY statement. */
    private static String csvPath(String dir, String name) {
        return (dir + "/" + name).replace("'", "''");
    }

}
