package dev.gruff.hardstop.cachegenie.graph;

import dev.gruff.hardstop.resolver.DependencySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.*;
import java.util.HashMap;
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
        String classifier = node.type != null ? node.type : "";
        
        // Try to select first
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT id FROM artifacts WHERE gid = ? AND aid = ? AND version = ? AND classifier = ?")) {
            pstmt.setString(1, node.gid);
            pstmt.setString(2, node.aid);
            pstmt.setString(3, node.ver);
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
            pstmt.setString(1, node.gid);
            pstmt.setString(2, node.aid);
            pstmt.setString(3, node.ver);
            pstmt.setString(4, classifier);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        throw new SQLException("Failed to insert artifact and retrieve ID");
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
