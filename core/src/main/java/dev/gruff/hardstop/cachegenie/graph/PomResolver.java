package dev.gruff.hardstop.cachegenie.graph;

import dev.gruff.hardstop.cachegenie.utils.Progress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * First-cut resolver that turns the <em>raw</em> mined POM tables into concrete
 * dependency edges. For each mined node not yet resolved it builds the effective
 * property map and effective {@code dependencyManagement} (walking the parent chain
 * and import-scope BOMs that were mined as their own nodes), resolves each direct
 * dependency's version, and writes the resolvable edges into {@code dependencies}.
 *
 * <p><b>This is deliberately not a full Maven model builder.</b> It handles the
 * common cases — parent-chain inheritance of managed versions, import BOMs, and
 * {@code ${...}} property interpolation — but knowingly does NOT handle: version
 * ranges (used verbatim), profile-activated dependencies/properties, mirror/
 * relocation, {@code <exclusions>}, settings.xml, or active-by-default profiles. A
 * dependency whose version can't be resolved to a concrete string is left
 * unresolved (no edge) and counted, not guessed. Inherited project metadata
 * (scm/org/…) is NOT coalesced here — raw values stay in {@code pom_meta}; a
 * metadata-resolution pass over the same parent chain is a separate follow-up.
 *
 * <p>Processing is per-node with in-memory caches of parent/BOM nodes, so common
 * parents are loaded once. Drive single-threaded (DuckDB single-writer).
 */
public final class PomResolver {

    private static final Logger log = LoggerFactory.getLogger(PomResolver.class);

    private final String dbPath;

    public PomResolver(File cacheGenieRoot) {
        this.dbPath = new File(cacheGenieRoot, "graph.db").getAbsolutePath();
        // Ensure the schema exists (also creates the mining tables if missing).
        new GraphRepository(cacheGenieRoot);
    }

    public record ResolveStats(long nodes, long edges, long unresolved) {}

    private record NodeData(int id, String gid, String aid, String version,
                            String parentGid, String parentAid, String parentVersion,
                            Map<String, String> props, List<Managed> dm) {}

    private record Managed(String gid, String aid, String version, String scope, String type, String classifier) {}

    private record RawDirect(String gid, String aid, String version, String scope, String type, String classifier) {}

    /** Resolve all not-yet-resolved nodes (or every node when {@code reResolve}). */
    public ResolveStats resolveAll(boolean reResolve) {
        long nodes = 0, edges = 0, unresolved = 0;
        String sel = "SELECT artifact_id FROM pom_meta" + (reResolve ? "" : " WHERE deps_resolved = FALSE");

        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:" + dbPath)) {
            conn.setAutoCommit(false);
            Ctx ctx = new Ctx(conn);

            List<Integer> todo = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sel);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) todo.add(rs.getInt(1));
            }

            log.info("Resolving {} mined node(s){}", todo.size(), reResolve ? " (--all)" : "");
            Progress progress = Progress.start("Graph resolve");
            progress.total(todo.size());

            int sinceCommit = 0;
            for (int id : todo) {
                NodeData node = ctx.loadNode(id);
                if (node == null) { progress.tick(); continue; }
                progress.tick(node.gid() + ":" + node.aid() + ":" + node.version());
                long[] c = resolveNode(ctx, node);
                edges += c[0];
                unresolved += c[1];
                ctx.markResolved(id);
                nodes++;
                if (++sinceCommit >= 200) { conn.commit(); sinceCommit = 0; }
            }
            conn.commit();
            ctx.close();
            progress.done();
            log.info("Resolve complete: {} node(s), {} edge(s), {} unresolved", nodes, edges, unresolved);
        } catch (SQLException e) {
            log.error("resolveAll failed", e);
        }
        return new ResolveStats(nodes, edges, unresolved);
    }

    /** @return {edgesWritten, unresolvedDeps} for one node. */
    private long[] resolveNode(Ctx ctx, NodeData node) throws SQLException {
        Map<String, String> props = effectiveProps(ctx, node);
        Map<String, String> dm = effectiveDependencyManagement(ctx, node, props);
        List<RawDirect> deps = ctx.loadDirect(node.id());

        long edges = 0, unresolved = 0;
        for (RawDirect d : deps) {
            String g = interpolate(d.gid(), props);
            String a = interpolate(d.aid(), props);
            String v = (d.version() != null && !d.version().isBlank())
                    ? interpolate(d.version(), props)
                    : dm.get(key(d.gid(), d.aid(), d.type(), d.classifier()));
            if (v != null) v = interpolate(v, props);

            if (!concrete(g) || !concrete(a) || !concrete(v)) { unresolved++; continue; }
            int childId = ctx.getOrInsert(g, a, v);
            if (childId == node.id()) continue;
            ctx.insertEdge(node.id(), childId, d.scope() != null ? d.scope() : "compile");
            edges++;
        }
        return new long[]{edges, unresolved};
    }

    /** Node properties merged up the parent chain (nearest wins), plus project.* built-ins. */
    private Map<String, String> effectiveProps(Ctx ctx, NodeData node) throws SQLException {
        Map<String, String> out = new HashMap<>();
        Set<Integer> visited = new HashSet<>();
        NodeData cur = node;
        while (cur != null && visited.add(cur.id())) {
            for (Map.Entry<String, String> e : cur.props().entrySet()) out.putIfAbsent(e.getKey(), e.getValue());
            cur = ctx.nodeByCoord(cur.parentGid(), cur.parentAid(), cur.parentVersion());
        }
        // Built-ins refer to the node being resolved.
        for (String k : new String[]{"project.version", "pom.version", "version"}) out.putIfAbsent(k, node.version());
        for (String k : new String[]{"project.groupId", "pom.groupId", "groupId"}) out.putIfAbsent(k, node.gid());
        for (String k : new String[]{"project.artifactId", "pom.artifactId", "artifactId"}) out.putIfAbsent(k, node.aid());
        return out;
    }

    /** Managed versions from this node, its parents, and import-scope BOMs (nearest wins). */
    private Map<String, String> effectiveDependencyManagement(Ctx ctx, NodeData node, Map<String, String> props) throws SQLException {
        Map<String, String> out = new HashMap<>();
        collectDm(ctx, node, props, out, new HashSet<>());
        return out;
    }

    private void collectDm(Ctx ctx, NodeData n, Map<String, String> props,
                           Map<String, String> out, Set<Integer> visited) throws SQLException {
        if (n == null || !visited.add(n.id())) return;
        // Direct managed entries at this level first (nearest wins via putIfAbsent).
        for (Managed m : n.dm()) {
            if (!"import".equalsIgnoreCase(m.scope())) {
                out.putIfAbsent(key(m.gid(), m.aid(), m.type(), m.classifier()), m.version());
            }
        }
        // Then import-scope BOMs declared at this level.
        for (Managed m : n.dm()) {
            if ("import".equalsIgnoreCase(m.scope())) {
                String bv = interpolate(m.version(), props);
                NodeData bom = ctx.nodeByCoord(m.gid(), m.aid(), bv);
                collectDm(ctx, bom, props, out, visited);
            }
        }
        // Then walk up to the parent.
        collectDm(ctx, ctx.nodeByCoord(n.parentGid(), n.parentAid(), n.parentVersion()), props, out, visited);
    }

    private static String key(String g, String a, String type, String classifier) {
        return g + ":" + a + ":" + (type == null || type.isBlank() ? "jar" : type) + ":" + (classifier == null ? "" : classifier);
    }

    /** A string is concrete if non-null, non-blank, and free of unresolved {@code ${...}}. */
    private static boolean concrete(String s) {
        return s != null && !s.isBlank() && !s.contains("${");
    }

    /** Replace {@code ${k}} tokens from {@code props}, a few passes for nesting; leaves unknowns intact. */
    private static String interpolate(String s, Map<String, String> props) {
        if (s == null || s.indexOf('$') < 0) return s;
        String cur = s;
        for (int pass = 0; pass < 5 && cur.contains("${"); pass++) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            boolean changed = false;
            while (i < cur.length()) {
                int open = cur.indexOf("${", i);
                if (open < 0) { sb.append(cur, i, cur.length()); break; }
                int close = cur.indexOf('}', open + 2);
                if (close < 0) { sb.append(cur, i, cur.length()); break; }
                sb.append(cur, i, open);
                String name = cur.substring(open + 2, close);
                String val = props.get(name);
                if (val != null) { sb.append(val); changed = true; }
                else { sb.append(cur, open, close + 1); }
                i = close + 1;
            }
            cur = sb.toString();
            if (!changed) break;
        }
        return cur;
    }

    /** Per-run connection context: caches + prepared statements + node loaders. */
    private static final class Ctx {
        private final Connection conn;
        private final PreparedStatement selMeta, selProps, selDm, selDirect, selArt, insArt, insEdge, markRes;
        private final Map<Integer, NodeData> byId = new HashMap<>();
        private final Map<String, Integer> idByCoord = new HashMap<>();

        Ctx(Connection conn) throws SQLException {
            this.conn = conn;
            selMeta = conn.prepareStatement(
                    "SELECT m.parent_gid, m.parent_aid, m.parent_version, a.gid, a.aid, a.version " +
                    "FROM pom_meta m JOIN artifacts a ON a.id = m.artifact_id WHERE m.artifact_id = ?");
            selProps = conn.prepareStatement("SELECT prop_key, prop_value FROM pom_properties WHERE artifact_id = ?");
            selDm = conn.prepareStatement(
                    "SELECT dep_gid, dep_aid, dep_version, scope, dep_type, dep_classifier FROM dependency_management WHERE artifact_id = ? ORDER BY ord");
            selDirect = conn.prepareStatement(
                    "SELECT dep_gid, dep_aid, dep_version, scope, dep_type, dep_classifier FROM direct_dep WHERE artifact_id = ? ORDER BY ord");
            selArt = conn.prepareStatement(
                    "SELECT id FROM artifacts WHERE gid = ? AND aid = ? AND version = ? AND classifier = ''");
            insArt = conn.prepareStatement(
                    "INSERT INTO artifacts (id, gid, aid, version, classifier) VALUES (nextval('seq_artifact_id'), ?, ?, ?, '') RETURNING id");
            insEdge = conn.prepareStatement(
                    "INSERT OR IGNORE INTO dependencies (parent_id, child_id, scope) VALUES (?, ?, ?)");
            markRes = conn.prepareStatement("UPDATE pom_meta SET deps_resolved = TRUE WHERE artifact_id = ?");
        }

        NodeData loadNode(int id) throws SQLException {
            if (byId.containsKey(id)) return byId.get(id);
            NodeData nd = null;
            selMeta.setInt(1, id);
            try (ResultSet rs = selMeta.executeQuery()) {
                if (rs.next()) {
                    Map<String, String> props = new HashMap<>();
                    selProps.setInt(1, id);
                    try (ResultSet pr = selProps.executeQuery()) {
                        while (pr.next()) props.put(pr.getString(1), pr.getString(2));
                    }
                    List<Managed> dm = new ArrayList<>();
                    selDm.setInt(1, id);
                    try (ResultSet dr = selDm.executeQuery()) {
                        while (dr.next()) dm.add(new Managed(dr.getString(1), dr.getString(2), dr.getString(3),
                                dr.getString(4), dr.getString(5), dr.getString(6)));
                    }
                    nd = new NodeData(id, rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getString(1), rs.getString(2), rs.getString(3), props, dm);
                }
            }
            byId.put(id, nd);
            if (nd != null) idByCoord.put(coord(nd.gid(), nd.aid(), nd.version()), id);
            return nd;
        }

        NodeData nodeByCoord(String g, String a, String v) throws SQLException {
            if (g == null || a == null || v == null || v.contains("${")) return null;
            String ck = coord(g, a, v);
            Integer id = idByCoord.get(ck);
            if (id == null && !idByCoord.containsKey(ck)) {
                id = findArtifactId(g, a, v);
                idByCoord.put(ck, id); // cache misses too (null)
            }
            return id == null ? null : loadNode(id);
        }

        private Integer findArtifactId(String g, String a, String v) throws SQLException {
            selArt.setString(1, g);
            selArt.setString(2, a);
            selArt.setString(3, v);
            try (ResultSet rs = selArt.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
            return null;
        }

        List<RawDirect> loadDirect(int id) throws SQLException {
            List<RawDirect> out = new ArrayList<>();
            selDirect.setInt(1, id);
            try (ResultSet rs = selDirect.executeQuery()) {
                while (rs.next()) out.add(new RawDirect(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)));
            }
            return out;
        }

        int getOrInsert(String g, String a, String v) throws SQLException {
            String ck = coord(g, a, v);
            Integer id = idByCoord.get(ck);
            if (id != null) return id;
            id = findArtifactId(g, a, v);
            if (id == null) {
                insArt.setString(1, g);
                insArt.setString(2, a);
                insArt.setString(3, v);
                try (ResultSet rs = insArt.executeQuery()) {
                    if (rs.next()) id = rs.getInt(1);
                }
            }
            if (id == null) throw new SQLException("could not resolve artifact id for " + ck);
            idByCoord.put(ck, id);
            return id;
        }

        void insertEdge(int parentId, int childId, String scope) throws SQLException {
            insEdge.setInt(1, parentId);
            insEdge.setInt(2, childId);
            insEdge.setString(3, scope);
            insEdge.executeUpdate();
        }

        void markResolved(int id) throws SQLException {
            markRes.setInt(1, id);
            markRes.executeUpdate();
        }

        private static String coord(String g, String a, String v) { return g + ":" + a + ":" + v; }

        void close() {
            for (PreparedStatement ps : new PreparedStatement[]{
                    selMeta, selProps, selDm, selDirect, selArt, insArt, insEdge, markRes}) {
                try { if (ps != null) ps.close(); } catch (SQLException ignore) { /* ignore */ }
            }
        }
    }
}
