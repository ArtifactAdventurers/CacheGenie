package dev.gruff.hardstop.cachegenie.graph;

import dev.gruff.hardstop.resolver.DependencySet;
import dev.gruff.hardstop.resolver.Resolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.*;
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
            
            log.info("DuckDB schema initialized at {}", dbPath);
        } catch (SQLException e) {
            log.error("Failed to initialize DuckDB schema", e);
        }
    }

    public void persist(DependencySet graph) {
        log.info("Persisting graph with {} nodes", graph.getNodes().size());
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                Map<DependencySet.Node, Integer> nodeIds = new HashMap<>();
                
                // 1. Ensure all nodes are in 'artifacts' table and get their IDs
                for (DependencySet.Node node : graph.getNodes()) {
                    int id = getOrInsertArtifact(conn, node);
                    nodeIds.put(node, id);
                }

                // 2. Insert dependencies
                int batchSize = 0;
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "INSERT OR IGNORE INTO dependencies (parent_id, child_id, scope) VALUES (?, ?, ?)")) {
                    Map<DependencySet.Node, Set<DependencySet.Node>> links = graph.getLinks();
                    for (Map.Entry<DependencySet.Node, Set<DependencySet.Node>> entry : links.entrySet()) {
                        DependencySet.Node parent = entry.getKey();
                        Integer parentId = nodeIds.get(parent);
                        if (parentId == null) {
                            // Should not happen if all nodes were processed above
                            int id = getOrInsertArtifact(conn, parent);
                            nodeIds.put(parent, id);
                            parentId = id;
                        }
                        Set<DependencySet.Node> children = entry.getValue();
                        if (children != null) {
                            for (DependencySet.Node child : children) {
                                Integer childId = nodeIds.get(child);
                                if (childId == null) {
                                    int id = getOrInsertArtifact(conn, child);
                                    nodeIds.put(child, id);
                                    childId = id;
                                }
                                if (parentId.equals(childId)) {
                                    log.debug("Skipping self-dependency for artifact {}", parent);
                                    continue;
                                }
                                // Note: scope is stored on the Node itself in this model, 
                                // but for links we might want to use the child's scope as the link scope.
                                pstmt.setInt(1, parentId);
                                pstmt.setInt(2, childId);
                                pstmt.setString(3, child.scope != null ? child.scope : "");
                                pstmt.addBatch();
                                batchSize++;
                            }
                        }
                    }
                    if (batchSize > 0) {
                        pstmt.executeBatch();
                    }
                }

                conn.commit();
                log.info("Successfully persisted graph with {} nodes and {} links to DuckDB", graph.getNodes().size(), batchSize);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.error("Failed to persist graph to DuckDB", e);
        }
    }

    private int getOrInsertArtifact(Connection conn, DependencySet.Node node) throws SQLException {
        return getOrInsertArtifact(conn, node.gid, node.aid, node.ver, node.type != null ? node.type : "");
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

    public boolean isArtifactPresent(String gid, String aid, String version) {
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(
                     "SELECT 1 FROM artifacts WHERE gid = ? AND aid = ? AND version = ?")) {
            pstmt.setString(1, gid);
            pstmt.setString(2, aid);
            pstmt.setString(3, version);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("Failed to check artifact existence", e);
            return false;
        }
    }
}
