package dev.gruff.hardstop.cachegenie.viewer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Read-only query layer over the CacheGenie DuckDB graph.
 *
 * <p>Rather than exposing the raw {@code artifacts} / {@code dependencies}
 * tables, this service answers <em>dependency</em> questions: what an artifact
 * depends on (forward), what depends on it (reverse), the transitive closure in
 * either direction, and a focused neighbourhood graph for visualisation. Every
 * query can be constrained to a single Maven scope.</p>
 *
 * <p>The database is opened read-only and a short-lived connection is used per
 * operation, so the viewer never contends with a process that is writing to the
 * graph.</p>
 */
public class GraphQueryService {

    private static final Logger log = LoggerFactory.getLogger(GraphQueryService.class);

    /** Hard ceiling on nodes returned for a single neighbourhood graph. */
    private static final int MAX_GRAPH_NODES = 400;

    private final String dbPath;

    public GraphQueryService(String dbPath) {
        this.dbPath = dbPath;
        try {
            Class.forName("org.duckdb.DuckDBDriver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("DuckDB JDBC driver not found on the classpath", e);
        }
    }

    private Connection open() throws SQLException {
        Properties props = new Properties();
        props.setProperty("duckdb.read_only", "true");
        return DriverManager.getConnection("jdbc:duckdb:" + dbPath, props);
    }

    // ------------------------------------------------------------------ search

    /** Artifacts whose {@code gid:aid:version} contains {@code term} (case-insensitive). */
    public List<Map<String, Object>> search(String term, int limit) {
        String sql = "SELECT id, gid, aid, version, classifier FROM artifacts " +
                "WHERE lower(gid || ':' || aid || ':' || version) LIKE ? " +
                "ORDER BY gid, aid, version LIMIT ?";
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + (term == null ? "" : term.toLowerCase()) + "%");
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(artifactRow(rs, false));
                }
            }
        } catch (SQLException e) {
            log.error("search failed", e);
        }
        return out;
    }

    // ---------------------------------------------------------------- artifact

    public Map<String, Object> artifact(int id) {
        String sql = "SELECT id, gid, aid, version, classifier FROM artifacts WHERE id = ?";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return artifactRow(rs, false);
                }
            }
        } catch (SQLException e) {
            log.error("artifact lookup failed", e);
        }
        return null;
    }

    /**
     * Direct dependencies of {@code id} (the artifacts it depends on), each
     * annotated with the dependency scope.
     */
    public List<Map<String, Object>> forwardDependencies(int id, String scope) {
        return directEdges(id, scope, true);
    }

    /**
     * Direct dependents of {@code id} (the artifacts that depend on it), each
     * annotated with the dependency scope.
     */
    public List<Map<String, Object>> reverseDependencies(int id, String scope) {
        return directEdges(id, scope, false);
    }

    private List<Map<String, Object>> directEdges(int id, String scope, boolean forward) {
        // forward: follow child_id from a given parent_id; reverse: the opposite.
        String matchCol = forward ? "d.parent_id" : "d.child_id";
        String joinCol = forward ? "d.child_id" : "d.parent_id";
        boolean filter = scope != null && !scope.isBlank();
        String sql = "SELECT a.id, a.gid, a.aid, a.version, a.classifier, d.scope " +
                "FROM dependencies d JOIN artifacts a ON a.id = " + joinCol + " " +
                "WHERE " + matchCol + " = ?" + (filter ? " AND d.scope = ?" : "") + " " +
                "ORDER BY a.gid, a.aid, a.version";
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            if (filter) {
                ps.setString(2, scope);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(artifactRow(rs, true));
                }
            }
        } catch (SQLException e) {
            log.error("direct edge query failed", e);
        }
        return out;
    }

    /**
     * Transitive closure from {@code id}, in the dependency direction
     * ({@code forward == true}) or dependent direction, limited to
     * {@code maxDepth} hops and optionally a single scope. Each artifact is
     * returned once, with the shortest depth at which it was reached.
     */
    public List<Map<String, Object>> transitive(int id, boolean forward, int maxDepth, String scope) {
        boolean filter = scope != null && !scope.isBlank();
        String seedCol = forward ? "child_id" : "parent_id";       // base hop
        String fromCol = forward ? "d.parent_id" : "d.child_id";   // recursive join
        String nextCol = forward ? "d.child_id" : "d.parent_id";   // recursive step
        String scopeBase = filter ? " AND scope = ?" : "";
        String scopeRec = filter ? " AND d.scope = ?" : "";

        String sql = "WITH RECURSIVE tc(node, depth) AS (" +
                "  SELECT " + seedCol + ", 1 FROM dependencies WHERE " +
                (forward ? "parent_id" : "child_id") + " = ?" + scopeBase +
                "  UNION " +
                "  SELECT " + nextCol + ", tc.depth + 1 FROM dependencies d " +
                "    JOIN tc ON " + fromCol + " = tc.node WHERE tc.depth < ?" + scopeRec +
                ") " +
                "SELECT a.id, a.gid, a.aid, a.version, a.classifier, MIN(tc.depth) AS depth " +
                "FROM tc JOIN artifacts a ON a.id = tc.node " +
                "GROUP BY a.id, a.gid, a.aid, a.version, a.classifier " +
                "ORDER BY depth, a.gid, a.aid, a.version";

        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int p = 1;
            ps.setInt(p++, id);
            if (filter) {
                ps.setString(p++, scope);
            }
            ps.setInt(p++, maxDepth);
            if (filter) {
                ps.setString(p, scope);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = artifactRow(rs, false);
                    row.put("depth", rs.getInt("depth"));
                    out.add(row);
                }
            }
        } catch (SQLException e) {
            log.error("transitive query failed", e);
        }
        return out;
    }

    /**
     * A focused neighbourhood graph around {@code focusId}: dependents reached
     * within {@code up} hops (negative depth) and dependencies within
     * {@code down} hops (positive depth). Returns {@code {focus, nodes, edges}}
     * where each edge points from depender to dependency.
     */
    public Map<String, Object> neighbourhood(int focusId, int up, int down, String scope) {
        Map<String, Object> focus = artifact(focusId);
        if (focus == null) {
            return null;
        }

        Map<Integer, Map<String, Object>> nodes = new LinkedHashMap<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> edgeKeys = new HashSet<>();

        nodes.put(focusId, node(focus, 0));

        // Forward: dependencies (depth > 0), edges focus -> dependency.
        bfs(focusId, down, scope, true, nodes, edges, edgeKeys);
        // Reverse: dependents (depth < 0), edges dependent -> focus.
        bfs(focusId, up, scope, false, nodes, edges, edgeKeys);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("focus", focusId);
        result.put("nodes", new ArrayList<>(nodes.values()));
        result.put("edges", edges);
        return result;
    }

    private void bfs(int startId, int maxDepth, String scope, boolean forward,
                     Map<Integer, Map<String, Object>> nodes,
                     List<Map<String, Object>> edges, Set<String> edgeKeys) {
        Deque<int[]> queue = new ArrayDeque<>();
        Set<Integer> visited = new HashSet<>();
        queue.add(new int[]{startId, 0});
        visited.add(startId);

        while (!queue.isEmpty() && nodes.size() < MAX_GRAPH_NODES) {
            int[] cur = queue.poll();
            int id = cur[0];
            int depth = cur[1];
            if (depth >= maxDepth) {
                continue;
            }
            List<Map<String, Object>> neighbours = forward
                    ? forwardDependencies(id, scope)
                    : reverseDependencies(id, scope);
            for (Map<String, Object> n : neighbours) {
                int nid = ((Number) n.get("id")).intValue();
                String sc = String.valueOf(n.getOrDefault("scope", ""));
                // Edge always points depender -> dependency.
                int from = forward ? id : nid;
                int to = forward ? nid : id;
                String key = from + "->" + to + "#" + sc;
                if (edgeKeys.add(key)) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("from", from);
                    e.put("to", to);
                    e.put("scope", sc);
                    edges.add(e);
                }
                if (!nodes.containsKey(nid)) {
                    int signedDepth = forward ? depth + 1 : -(depth + 1);
                    nodes.put(nid, node(n, signedDepth));
                }
                if (visited.add(nid) && nodes.size() < MAX_GRAPH_NODES) {
                    queue.add(new int[]{nid, depth + 1});
                }
            }
        }
    }

    // -------------------------------------------------------------------- stats

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM artifacts")) {
                out.put("artifacts", rs.next() ? rs.getLong(1) : 0L);
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM dependencies")) {
                out.put("dependencies", rs.next() ? rs.getLong(1) : 0L);
            }
            List<Map<String, Object>> scopes = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT scope, COUNT(*) c FROM dependencies GROUP BY scope ORDER BY c DESC")) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    String sc = rs.getString(1);
                    row.put("scope", sc == null || sc.isBlank() ? "(none)" : sc);
                    row.put("count", rs.getLong(2));
                    scopes.add(row);
                }
            }
            out.put("scopes", scopes);
        } catch (SQLException e) {
            log.error("stats query failed", e);
            out.put("error", e.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> artifactRow(ResultSet rs, boolean withScope) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getInt("id"));
        m.put("gid", rs.getString("gid"));
        m.put("aid", rs.getString("aid"));
        m.put("version", rs.getString("version"));
        String classifier = rs.getString("classifier");
        m.put("classifier", classifier == null ? "" : classifier);
        if (withScope) {
            String scope = rs.getString("scope");
            m.put("scope", scope == null ? "" : scope);
        }
        return m;
    }

    private static Map<String, Object> node(Map<String, Object> artifact, int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", artifact.get("id"));
        m.put("gid", artifact.get("gid"));
        m.put("aid", artifact.get("aid"));
        m.put("version", artifact.get("version"));
        m.put("classifier", artifact.getOrDefault("classifier", ""));
        m.put("depth", depth);
        return m;
    }
}
