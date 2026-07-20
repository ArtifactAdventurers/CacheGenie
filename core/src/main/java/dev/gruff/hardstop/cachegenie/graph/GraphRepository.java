package dev.gruff.hardstop.cachegenie.graph;

import dev.gruff.hardstop.cachegenie.entities.MinedPom;
import dev.gruff.hardstop.resolver.Resolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GraphRepository {
    private static final Logger log = LoggerFactory.getLogger(GraphRepository.class);
    private final String dbPath;

    public GraphRepository(File cacheGenieRoot) {
        this.dbPath = Sqlite.dbPath(cacheGenieRoot);
        initSchema();
    }

    private Connection getConnection() throws SQLException {
        return Sqlite.open(dbPath);
    }

    private void initSchema() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            // id INTEGER PRIMARY KEY is a rowid alias: omit it on INSERT and SQLite
            // assigns the next id (no sequence needed).
            stmt.execute("CREATE TABLE IF NOT EXISTS artifacts (" +
                    "id INTEGER PRIMARY KEY," +
                    "gid VARCHAR," +
                    "aid VARCHAR," +
                    "version VARCHAR," +
                    "classifier VARCHAR," +
                    "UNIQUE (gid, aid, version, classifier))");

            stmt.execute("CREATE TABLE IF NOT EXISTS dependencies (" +
                    "parent_id INTEGER," +
                    "child_id INTEGER," +
                    "scope VARCHAR," +
                    "PRIMARY KEY (parent_id, child_id, scope))");

            initMiningSchema(stmt);

            // Day-one secondary indexes (see MIGRATION-SQLITE.md §4): under SQLite these
            // serve the point lookups that DuckDB table-scanned. pom_meta(artifact_id)
            // is already the PRIMARY KEY, so it needs no extra index.
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dependencies_child ON dependencies(child_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dependencies_parent ON dependencies(parent_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_pom_meta_parent ON pom_meta(parent_gid, parent_aid, parent_version)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_direct_dep_artifact ON direct_dep(artifact_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dependency_management_artifact ON dependency_management(artifact_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_pom_properties_artifact ON pom_properties(artifact_id)");

            log.info("SQLite schema initialized at {}", dbPath);
        } catch (SQLException e) {
            log.error("Failed to initialize SQLite schema", e);
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

        // Not found, insert (id omitted — rowid alias) and read back last_insert_rowid()
        // on the same connection.
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO artifacts (gid, aid, version, classifier) VALUES (?, ?, ?, ?)")) {
            pstmt.setString(1, gid);
            pstmt.setString(2, aid);
            pstmt.setString(3, version);
            pstmt.setString(4, classifier);
            pstmt.executeUpdate();
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
            if (rs.next()) {
                return rs.getInt(1);
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
     * time, which — serialised behind a single writer — caps throughput at a
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
        private final PreparedStatement lastId;
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
                    "INSERT INTO artifacts (gid, aid, version, classifier) VALUES (?, ?, ?, '')");
            lastId = conn.prepareStatement("SELECT last_insert_rowid()");
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
                insArt.executeUpdate();
                try (ResultSet rs = lastId.executeQuery()) {
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
            for (PreparedStatement ps : new PreparedStatement[]{selArt, insArt, lastId, insEdge, markMissing}) {
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
     * <p>Rows are staged via batched {@code PreparedStatement}s into constraint-free,
     * coordinate-keyed staging tables and merged set-based every {@link
     * MiningWriter#FLUSH_EVERY} POMs (artifact ids are assigned once per chunk by a
     * single bulk {@code INSERT … SELECT}, then the {@code pom_*} rows are inserted by
     * joining staging to {@code artifacts} on coordinates). This replaces the old
     * ~14-statements-per-POM path, whose per-row {@code INSERT}/{@code DELETE} cost —
     * serialized behind a single writer — capped throughput at a few tens/s regardless
     * of fetch concurrency.
     *
     * <p><b>Not thread-safe.</b> {@code graph mine} drives this from a single
     * drainer thread fed by the fetch workers, so DB writes never contend with — and
     * fully overlap — the network fetches. Use with try-with-resources; {@link #close()}
     * does a final flush.
     *
     * <p>Staging tables live in {@code graph.sqlite} but are cleared after every chunk
     * so they stay tiny.
     */
    public MiningWriter openMiningWriter() throws SQLException {
        return new MiningWriter();
    }

    /** Bulk, batched-statement writer for {@code graph mine}; see {@link #openMiningWriter()}. */
    public final class MiningWriter implements AutoCloseable {
        /** Merge to the real tables once this many POMs have been staged. */
        private static final int FLUSH_EVERY = 4000;
        /** Execute a staging statement's pending batch once it reaches this many rows. */
        private static final int BATCH_EVERY = 5000;

        private final Connection conn;
        // One batched prepared statement per coordinate-keyed staging table, plus a
        // pending-row count so each batch is executed every BATCH_EVERY rows.
        private final PreparedStatement stMeta, stDep, stDm, stProp, stDev, stLic, stMissing;
        private int pMeta, pDep, pDm, pProp, pDev, pLic, pMissing;
        private int staged = 0;

        private MiningWriter() throws SQLException {
            conn = getConnection();
            conn.setAutoCommit(false);
            createStaging();
            conn.commit();      // commit the staging DDL before staging any rows
            stMeta = conn.prepareStatement("INSERT INTO mine_s_meta VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
            stDep = conn.prepareStatement("INSERT INTO mine_s_dep VALUES (?,?,?,?,?,?,?,?,?,?,?)");
            stDm = conn.prepareStatement("INSERT INTO mine_s_dm VALUES (?,?,?,?,?,?,?,?,?,?)");
            stProp = conn.prepareStatement("INSERT INTO mine_s_prop VALUES (?,?,?,?,?)");
            stDev = conn.prepareStatement("INSERT INTO mine_s_dev VALUES (?,?,?,?,?,?,?,?,?,?,?,?)");
            stLic = conn.prepareStatement("INSERT INTO mine_s_lic VALUES (?,?,?,?,?,?,?)");
            stMissing = conn.prepareStatement("INSERT INTO mine_s_missing VALUES (?,?,?)");
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

        /** Null → "" so staging never stores a null string; NULLIF restores SQL NULL at merge for nullable columns. */
        private static String s(String v) { return v != null ? v : ""; }

        /** Stage one mined POM. Single-threaded (drainer only). Coordinates are the catalogue GAV (see {@code withCoordinates}). */
        public void appendMined(MinedPom p) {
            try {
                stMeta.setString(1, s(p.gid())); stMeta.setString(2, s(p.aid())); stMeta.setString(3, s(p.version()));
                stMeta.setString(4, s(p.packaging()));
                stMeta.setString(5, s(p.parentGid())); stMeta.setString(6, s(p.parentAid()));
                stMeta.setString(7, s(p.parentVersion())); stMeta.setString(8, s(p.parentRelPath()));
                stMeta.setString(9, s(p.name())); stMeta.setString(10, s(p.description()));
                stMeta.setString(11, s(p.url())); stMeta.setString(12, s(p.inceptionYear()));
                stMeta.setString(13, s(p.organizationName())); stMeta.setString(14, s(p.organizationUrl()));
                stMeta.setString(15, s(p.scmUrl())); stMeta.setString(16, s(p.scmConnection()));
                stMeta.setString(17, s(p.scmDevConnection())); stMeta.setString(18, s(p.scmTag()));
                stMeta.setString(19, s(p.issueSystem())); stMeta.setString(20, s(p.issueUrl()));
                stMeta.setString(21, s(p.ciSystem())); stMeta.setString(22, s(p.ciUrl()));
                stMeta.setString(23, Instant.now().toString());
                stMeta.addBatch();
                if (++pMeta >= BATCH_EVERY) { stMeta.executeBatch(); pMeta = 0; }

                int ord = 0;
                for (MinedPom.RawDep d : p.dependencies()) {
                    stDep.setString(1, s(p.gid())); stDep.setString(2, s(p.aid())); stDep.setString(3, s(p.version()));
                    stDep.setInt(4, ord++);
                    stDep.setString(5, s(d.gid())); stDep.setString(6, s(d.aid())); stDep.setString(7, s(d.version()));
                    stDep.setString(8, s(d.scope())); stDep.setString(9, s(d.type())); stDep.setString(10, s(d.classifier()));
                    stDep.setBoolean(11, d.optional());
                    stDep.addBatch();
                    if (++pDep >= BATCH_EVERY) { stDep.executeBatch(); pDep = 0; }
                }
                ord = 0;
                for (MinedPom.RawDep d : p.dependencyManagement()) {
                    stDm.setString(1, s(p.gid())); stDm.setString(2, s(p.aid())); stDm.setString(3, s(p.version()));
                    stDm.setInt(4, ord++);
                    stDm.setString(5, s(d.gid())); stDm.setString(6, s(d.aid())); stDm.setString(7, s(d.version()));
                    stDm.setString(8, s(d.scope())); stDm.setString(9, s(d.type())); stDm.setString(10, s(d.classifier()));
                    stDm.addBatch();
                    if (++pDm >= BATCH_EVERY) { stDm.executeBatch(); pDm = 0; }
                }
                for (Map.Entry<String, String> e : p.properties().entrySet()) {
                    stProp.setString(1, s(p.gid())); stProp.setString(2, s(p.aid())); stProp.setString(3, s(p.version()));
                    stProp.setString(4, s(e.getKey())); stProp.setString(5, s(e.getValue()));
                    stProp.addBatch();
                    if (++pProp >= BATCH_EVERY) { stProp.executeBatch(); pProp = 0; }
                }
                ord = 0;
                for (MinedPom.Dev d : p.developers()) {
                    stDev.setString(1, s(p.gid())); stDev.setString(2, s(p.aid())); stDev.setString(3, s(p.version()));
                    stDev.setInt(4, ord++);
                    stDev.setString(5, s(d.roleKind())); stDev.setString(6, s(d.id())); stDev.setString(7, s(d.name()));
                    stDev.setString(8, s(d.email())); stDev.setString(9, s(d.organization()));
                    stDev.setString(10, s(d.organizationUrl())); stDev.setString(11, s(d.url())); stDev.setString(12, s(d.roles()));
                    stDev.addBatch();
                    if (++pDev >= BATCH_EVERY) { stDev.executeBatch(); pDev = 0; }
                }
                ord = 0;
                for (MinedPom.License l : p.licenses()) {
                    stLic.setString(1, s(p.gid())); stLic.setString(2, s(p.aid())); stLic.setString(3, s(p.version()));
                    stLic.setInt(4, ord++);
                    stLic.setString(5, s(l.name())); stLic.setString(6, s(l.url())); stLic.setString(7, s(l.distribution()));
                    stLic.addBatch();
                    if (++pLic >= BATCH_EVERY) { stLic.executeBatch(); pLic = 0; }
                }
                maybeFlush();
            } catch (SQLException e) {
                log.error("appendMined {}:{}:{} failed", p.gid(), p.aid(), p.version(), e);
            }
        }

        /** Stage one missing-POM marker (bulk-applied to {@code meta_versions} at merge). Single-threaded. */
        public void appendMissing(String gid, String aid, String version) {
            try {
                stMissing.setString(1, s(gid)); stMissing.setString(2, s(aid)); stMissing.setString(3, s(version));
                stMissing.addBatch();
                if (++pMissing >= BATCH_EVERY) { stMissing.executeBatch(); pMissing = 0; }
                maybeFlush();
            } catch (SQLException e) {
                log.error("appendMissing {}:{}:{} failed", gid, aid, version, e);
            }
        }

        private void maybeFlush() throws SQLException {
            if (++staged >= FLUSH_EVERY) flush();
        }

        /** Execute every staging statement's pending batch so all staged rows are visible to the merge. */
        private void drainBatches() throws SQLException {
            if (pMeta > 0)    { stMeta.executeBatch();    pMeta = 0; }
            if (pDep > 0)     { stDep.executeBatch();     pDep = 0; }
            if (pDm > 0)      { stDm.executeBatch();      pDm = 0; }
            if (pProp > 0)    { stProp.executeBatch();    pProp = 0; }
            if (pDev > 0)     { stDev.executeBatch();     pDev = 0; }
            if (pLic > 0)     { stLic.executeBatch();     pLic = 0; }
            if (pMissing > 0) { stMissing.executeBatch(); pMissing = 0; }
        }

        /** Discard any un-executed batch rows (recovery path). */
        private void clearBatchesQuiet() {
            for (PreparedStatement ps : new PreparedStatement[]{stMeta, stDep, stDm, stProp, stDev, stLic, stMissing}) {
                try { ps.clearBatch(); } catch (SQLException ignore) { /* ignore */ }
            }
            pMeta = pDep = pDm = pProp = pDev = pLic = pMissing = 0;
        }

        /**
         * Flush staged rows to the real tables and clear staging. Executes the pending
         * batches (so their rows are visible), assigns artifact ids set-based, clears any
         * prior {@code pom_*} rows for the staged coordinates (idempotent re-mine), inserts
         * all child rows by joining on coordinates, applies missing markers, then
         * truncates staging. NULLIF restores SQL NULL for the
         * nullable text columns (parent/identity/scm/dep coordinates/scope/dev/licence);
         * columns the parser never leaves null ({@code packaging}, {@code dep_type},
         * {@code dep_classifier}, {@code prop_*}, {@code role_kind}, {@code mined_at})
         * are stored verbatim to preserve the original "" vs NULL distinction.
         */
        public void flush() {
            try {
                drainBatches();
                try (Statement st = conn.createStatement()) {
                    // 1. Assign ids to staged coordinates not already in `artifacts`
                    //    (id omitted — rowid alias; INSERT OR IGNORE dedups against the
                    //    UNIQUE(gid,aid,version,classifier) constraint).
                    st.execute("INSERT OR IGNORE INTO artifacts (gid, aid, version, classifier) " +
                            "SELECT DISTINCT gid, aid, version, '' FROM mine_s_meta");

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

                    // 4. Bulk-apply missing markers via correlated EXISTS.
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
            } catch (SQLException e) {
                log.error("graph-mine flush failed (chunk of {} dropped; those versions stay un-mined and resume next run)", staged, e);
                // Recover so the drainer can keep going: roll back the half-merged chunk
                // (staged rows were inserted inside this transaction, so the rollback also
                // empties staging) and discard any un-executed batch rows.
                try { conn.rollback(); } catch (SQLException ignore) { /* ignore */ }
                clearBatchesQuiet();
                staged = 0;
            }
        }

        @Override
        public void close() {
            try { flush(); } catch (Exception e) { log.error("final graph-mine flush failed", e); }
            for (PreparedStatement ps : new PreparedStatement[]{stMeta, stDep, stDm, stProp, stDev, stLic, stMissing}) {
                try { if (ps != null) ps.close(); } catch (SQLException ignore) { /* ignore */ }
            }
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
     * Bulk-load a Goblin dependency-edge CSV into {@code artifacts}/{@code dependencies}:
     * the CSV is parsed and pre-split in Java, streamed into a staging table with
     * batched inserts, then merged set-based. The CSV is the export of Goblin's
     * {@code (Release)-[:dependency]->(Artifact)} relationships, one row per edge with
     * header columns {@code source,targetArtifact,targetVersion,scope} where
     * {@code source} is {@code g:a:v}, {@code targetArtifact} is {@code g:a}, and
     * {@code targetVersion} is the (Aether-resolved) dependency version.
     *
     * <p>Goblin's edges are effective/resolved direct dependencies (same as our
     * {@code graph deps}). {@code targetVersion} is occasionally a version <em>range</em>
     * rather than a concrete version; unless {@code includeRanges} is set, those edges
     * (and their non-existent child release) are skipped. Idempotent:
     * {@code INSERT OR IGNORE} on both artifacts and edges, so it
     * can be re-run or layered on top of an existing graph.
     */
    public GoblinImportStats importGoblinEdges(File edgesCsv, boolean includeRanges) {
        // A "concrete" child version has no range/property syntax. LIKE (not regex) to keep escaping simple.
        String concrete = "(cver IS NOT NULL AND cver <> '' "
                + "AND cver NOT LIKE '%[%' AND cver NOT LIKE '%]%' AND cver NOT LIKE '%(%' "
                + "AND cver NOT LIKE '%)%' AND cver NOT LIKE '%,%' AND cver NOT LIKE '%$%')";
        String childWhere = includeRanges ? "1=1" : concrete;

        long artBefore = 0, depBefore = 0, artAfter = 0, depAfter = 0, rows = 0, kept = 0;
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS ge");
                st.execute("CREATE TEMP TABLE ge (pgid VARCHAR, paid VARCHAR, pver VARCHAR, "
                        + "cgid VARCHAR, caid VARCHAR, cver VARCHAR, scp VARCHAR)");
            }

            // Stage well-formed rows (source has g:a:v, target has g:a), split in Java.
            // Malformed rows (wrong colon count / missing columns) are dropped, matching
            // the old SQL-side filter.
            try (Reader in = new BufferedReader(new InputStreamReader(
                         Files.newInputStream(edgesCsv.toPath()), StandardCharsets.UTF_8));
                 PreparedStatement ins = conn.prepareStatement(
                         "INSERT INTO ge (pgid, paid, pver, cgid, caid, cver, scp) VALUES (?,?,?,?,?,?,?)")) {
                CsvParser csv = new CsvParser(in);
                List<String> header = csv.next();
                if (header == null) {
                    log.error("importGoblinEdges: {} is empty", edgesCsv);
                    conn.rollback();
                    return new GoblinImportStats(0, 0, 0, 0);
                }
                int iSource = header.indexOf("source");
                int iTarget = header.indexOf("targetArtifact");
                int iVersion = header.indexOf("targetVersion");
                int iScope = header.indexOf("scope");
                if (iSource < 0 || iTarget < 0 || iVersion < 0) {
                    log.error("importGoblinEdges: {} is missing required header columns "
                            + "source/targetArtifact/targetVersion (found: {})", edgesCsv, header);
                    conn.rollback();
                    return new GoblinImportStats(0, 0, 0, 0);
                }
                int pending = 0;
                List<String> rec;
                while ((rec = csv.next()) != null) {
                    String source = field(rec, iSource);
                    String target = field(rec, iTarget);
                    if (source == null || target == null) continue;       // ragged row
                    String[] sp = source.split(":", -1);
                    String[] tp = target.split(":", -1);
                    if (sp.length != 3 || tp.length != 2) continue;       // wrong colon count
                    ins.setString(1, sp[0]);
                    ins.setString(2, sp[1]);
                    ins.setString(3, sp[2]);
                    ins.setString(4, tp[0]);
                    ins.setString(5, tp[1]);
                    ins.setString(6, field(rec, iVersion));
                    ins.setString(7, iScope >= 0 ? field(rec, iScope) : null);
                    ins.addBatch();
                    rows++;
                    if (++pending >= 5000) { ins.executeBatch(); pending = 0; }
                }
                if (pending > 0) ins.executeBatch();
            }

            try (Statement st = conn.createStatement()) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ge WHERE " + childWhere)) { rs.next(); kept = rs.getLong(1); }
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM artifacts")) { rs.next(); artBefore = rs.getLong(1); }
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM dependencies")) { rs.next(); depBefore = rs.getLong(1); }

                // Distinct GAVs: all parents (always concrete) + concrete children.
                st.execute("CREATE TEMP TABLE ge_gav AS "
                        + "SELECT DISTINCT pgid AS gid, paid AS aid, pver AS version FROM ge "
                        + "UNION SELECT DISTINCT cgid, caid, cver FROM ge WHERE " + childWhere);

                // Insert only the GAVs we don't already have (classifier ''); id omitted
                // (rowid alias), OR IGNORE dedups on UNIQUE(gid,aid,version,classifier).
                st.execute("INSERT OR IGNORE INTO artifacts (gid, aid, version, classifier) "
                        + "SELECT gid, aid, version, '' FROM ge_gav g "
                        + "WHERE g.gid <> '' AND g.aid <> '' AND g.version IS NOT NULL AND g.version <> ''");

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
            }
            conn.commit();
        } catch (SQLException | IOException e) {
            log.error("importGoblinEdges failed for {}", edgesCsv, e);
            return new GoblinImportStats(0, 0, 0, 0);
        }
        return new GoblinImportStats(artAfter - artBefore, depAfter - depBefore, rows, rows - kept);
    }

    /** Field {@code i} of a parsed record, or null when the row is too short. */
    private static String field(List<String> rec, int i) {
        return i < rec.size() ? rec.get(i) : null;
    }

    /**
     * Minimal streaming CSV parser, RFC-4180-enough for the Goblin export: quoted
     * fields with embedded commas, doubled-quote escapes, and embedded newlines;
     * accepts LF, CRLF and lone-CR record terminators; skips a leading UTF-8 BOM.
     * Unquoted fields are read verbatim (a stray quote mid-field is kept literally).
     */
    private static final class CsvParser {
        private final Reader in;
        private int pushback = -2;
        private boolean first = true;

        CsvParser(Reader in) {
            this.in = in;
        }

        /** Next record's fields, or null at end of input. */
        List<String> next() throws IOException {
            int c = read();
            if (first) {
                first = false;
                if (c == 0xFEFF) c = read();   // skip UTF-8 BOM
            }
            if (c == -1) return null;
            List<String> fields = new ArrayList<>();
            StringBuilder f = new StringBuilder();
            boolean inQuotes = false;
            boolean fieldStart = true;
            while (true) {
                if (c == -1) {                        // EOF ends the last record
                    fields.add(f.toString());
                    return fields;
                }
                if (inQuotes) {
                    if (c == '"') {
                        int n = read();
                        if (n == '"') {
                            f.append('"');            // escaped quote
                        } else {
                            inQuotes = false;         // closing quote
                            unread(n);
                        }
                    } else {
                        f.append((char) c);           // includes commas and newlines
                    }
                } else if (c == '"' && fieldStart) {
                    inQuotes = true;
                    fieldStart = false;
                } else if (c == ',') {
                    fields.add(f.toString());
                    f.setLength(0);
                    fieldStart = true;
                } else if (c == '\r') {
                    int n = read();
                    if (n != '\n') unread(n);         // lone CR terminator
                    fields.add(f.toString());
                    return fields;
                } else if (c == '\n') {
                    fields.add(f.toString());
                    return fields;
                } else {
                    f.append((char) c);
                    fieldStart = false;
                }
                c = read();
            }
        }

        private int read() throws IOException {
            if (pushback != -2) {
                int c = pushback;
                pushback = -2;
                return c;
            }
            return in.read();
        }

        private void unread(int c) {
            pushback = c;
        }
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
     * The CSVs are written in Java over streamed ResultSets (RFC-4180 quoting, NULL as
     * empty); the database is opened read-only.
     */
    public Neo4jExportStats exportNeo4jCsv(File outDir, boolean withMetadata) {
        if (!outDir.exists() && !outDir.mkdirs()) {
            log.error("could not create Neo4j export dir {}", outDir);
            return new Neo4jExportStats(0, 0, 0);
        }

        String releaseSelect = withMetadata
                ? "SELECT a.gid || ':' || a.aid || ':' || a.version, a.version, a.gid, a.aid, m.name, m.url, m.scm_url "
                  + "FROM artifacts a LEFT JOIN pom_meta m ON m.artifact_id = a.id WHERE a.classifier = ''"
                : "SELECT gid || ':' || aid || ':' || version, version, gid, aid "
                  + "FROM artifacts WHERE classifier = ''";
        String[] releaseHeader = withMetadata
                ? new String[]{"id:ID(Release)", "version", "gid", "aid", "name", "url", "scmUrl"}
                : new String[]{"id:ID(Release)", "version", "gid", "aid"};

        long releases = 0, libraries = 0, edges = 0;
        try (Connection conn = Sqlite.openReadOnly(dbPath); Statement st = conn.createStatement()) {
            writeCsv(new File(outDir, "releases.csv"), releaseHeader, st, releaseSelect);

            writeCsv(new File(outDir, "libraries.csv"),
                    new String[]{"id:ID(Artifact)", "gid", "aid"}, st,
                    "SELECT DISTINCT gid || ':' || aid, gid, aid FROM artifacts WHERE classifier = ''");

            writeCsv(new File(outDir, "rel_ar.csv"),
                    new String[]{":START_ID(Artifact)", ":END_ID(Release)"}, st,
                    "SELECT DISTINCT gid || ':' || aid, gid || ':' || aid || ':' || version "
                            + "FROM artifacts WHERE classifier = ''");

            writeCsv(new File(outDir, "deps.csv"),
                    new String[]{":START_ID(Release)", ":END_ID(Artifact)", "targetVersion", "scope"}, st,
                    "SELECT p.gid || ':' || p.aid || ':' || p.version, c.gid || ':' || c.aid, c.version, "
                            + "COALESCE(d.scope, '') "
                            + "FROM dependencies d JOIN artifacts p ON p.id = d.parent_id JOIN artifacts c ON c.id = d.child_id");

            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM artifacts WHERE classifier = ''")) { rs.next(); releases = rs.getLong(1); }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(DISTINCT gid || ':' || aid) FROM artifacts WHERE classifier = ''")) { rs.next(); libraries = rs.getLong(1); }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM dependencies")) { rs.next(); edges = rs.getLong(1); }
        } catch (SQLException | IOException e) {
            log.error("exportNeo4jCsv failed", e);
            return new Neo4jExportStats(0, 0, 0);
        }
        return new Neo4jExportStats(releases, libraries, edges);
    }

    /** Stream {@code sql}'s result to {@code out} as CSV with a fixed header row. */
    private static void writeCsv(File out, String[] header, Statement st, String sql) throws SQLException, IOException {
        try (ResultSet rs = st.executeQuery(sql);
             BufferedWriter w = Files.newBufferedWriter(out.toPath(), StandardCharsets.UTF_8)) {
            int cols = header.length;
            writeCsvRow(w, header);
            String[] row = new String[cols];
            while (rs.next()) {
                for (int i = 0; i < cols; i++) row[i] = rs.getString(i + 1);
                writeCsvRow(w, row);
            }
        }
    }

    private static void writeCsvRow(BufferedWriter w, String[] fields) throws IOException {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) w.write(',');
            w.write(csvField(fields[i]));
        }
        w.write('\n');
    }

    /** RFC-4180 field encoding: NULL → empty; quote only when the value needs it. */
    private static String csvField(String v) {
        if (v == null) return "";
        if (v.indexOf(',') < 0 && v.indexOf('"') < 0 && v.indexOf('\n') < 0 && v.indexOf('\r') < 0) return v;
        return '"' + v.replace("\"", "\"\"") + '"';
    }

}
