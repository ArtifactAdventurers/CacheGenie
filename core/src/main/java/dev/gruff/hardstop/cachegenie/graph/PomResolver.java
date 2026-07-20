package dev.gruff.hardstop.cachegenie.graph;

import dev.gruff.hardstop.cachegenie.utils.Progress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * First-cut resolver that turns the <em>raw</em> mined POM tables into concrete
 * dependency edges. For each mined node not yet resolved it builds the effective
 * property map and effective {@code dependencyManagement} (walking the parent chain
 * and import-scope BOMs that were mined as their own nodes), resolves each direct
 * dependency's version, and writes the resolvable edges into {@code dependencies}.
 *
 * <p><b>This is deliberately not a full Maven model builder.</b> It handles the
 * common cases — parent-chain inheritance of managed versions, import BOMs
 * (transitively: a BOM's own imports are followed, each interpolated in that BOM's
 * own property context), and {@code ${...}} property interpolation — but knowingly
 * does NOT handle: version ranges (used verbatim), profile-activated
 * dependencies/properties, mirror/relocation, {@code <exclusions>}, settings.xml, or
 * active-by-default profiles. A dependency whose version can't be resolved to a
 * concrete string is left unresolved (no edge) and counted, not guessed. Inherited
 * project metadata (scm/org/…) is NOT coalesced here — raw values stay in
 * {@code pom_meta}; a metadata-resolution pass over the same parent chain is a
 * separate follow-up.
 *
 * <h2>Scaling: streamed worklist + parallel readers, single funnelled writer</h2>
 * Under SQLite (see MIGRATION-SQLITE.md §5) the per-node path <b>is</b> the fast
 * path: every lookup here ({@code pom_meta}/{@code direct_dep}/
 * {@code dependency_management}/{@code pom_properties} by {@code artifact_id},
 * {@code artifacts} by coordinate) is an indexed B-tree point lookup, so a node
 * resolves in a handful of index probes. The DuckDB-era set-based SQL passes —
 * which existed only because DuckDB table-scanned these filters — are gone.
 *
 * <p>The worklist is read in bounded keyset pages ordered by {@code artifact_id}
 * (cursor-paged like {@code graph mine}), so memory is constant regardless of the
 * backlog size. The per-node resolve is dominated by DB <em>reads</em>, so it is
 * parallelised across a worker pool: each worker owns its own
 * {@link Sqlite#openReadOnly(String) read-only connection} (WAL permits concurrent
 * readers alongside the single writer), only <em>reads</em>, and produces a
 * {@link NodeResult} of resolved child ids / pending inserts. <b>All writes</b>
 * (edge inserts, synthetic-artifact inserts, {@code deps_resolved} marks) funnel
 * through one {@link Sqlite#open(String) read-write connection} on the collector
 * thread — the single writer, batched and committed per page. Per-thread parent/id
 * caches and the writer's id cache are bounded LRUs.
 */
public final class PomResolver {

    private static final Logger log = LoggerFactory.getLogger(PomResolver.class);

    /** Worklist page size (rows pulled per keyset query). */
    private static final int PAGE_SIZE = 50_000;
    /** Writer flushes its edge/mark batches every this many nodes. */
    private static final int WRITE_FLUSH_NODES = 5_000;
    /** Bounded-LRU capacities (per reader, and for the writer's id cache). */
    private static final int PARENT_CACHE = 100_000;
    private static final int READER_ID_CACHE = 500_000;
    private static final int WRITER_ID_CACHE = 1_000_000;

    private final String dbPath;

    public PomResolver(File cacheGenieRoot) {
        this.dbPath = Sqlite.dbPath(cacheGenieRoot);
        // Ensure the schema exists (also creates the mining tables if missing).
        new GraphRepository(cacheGenieRoot);
    }

    public record ResolveStats(long nodes, long edges, long unresolved) {}

    private record NodeData(int id, String gid, String aid, String version,
                            String parentGid, String parentAid, String parentVersion,
                            Map<String, String> props, List<Managed> dm) {}

    private record Managed(String gid, String aid, String version, String scope, String type, String classifier) {}

    private record RawDirect(String gid, String aid, String version, String scope, String type, String classifier) {}

    /** An edge to an artifact that already exists (child id known by a reader). */
    private record ResolvedEdge(int childId, String scope) {}

    /** An edge whose child coordinate is not yet an artifact row — the writer inserts it. */
    private record PendingEdge(String gid, String aid, String version, String scope) {}

    /** A worker's read-only result for one node: resolved edges, pending inserts, unresolved count. */
    private record NodeResult(int nodeId, List<ResolvedEdge> resolved, List<PendingEdge> pending, int unresolved) {}

    /** Sentinel for "this coordinate is known absent" (LRU maps cannot store null). */
    private static final NodeData ABSENT_NODE = new NodeData(-1, null, null, null, null, null, null, Map.of(), List.of());
    private static final Integer ABSENT_ID = Integer.MIN_VALUE;

    /** Resolve all not-yet-resolved nodes (or every node when {@code reResolve}), single-threaded. */
    public ResolveStats resolveAll(boolean reResolve) {
        return resolveAll(reResolve, 1);
    }

    /**
     * Resolve mined nodes into edges: a streamed per-node pass over the worklist
     * using {@code threads} reader workers and one writer.
     *
     * @param reResolve re-resolve every mined node, not just {@code deps_resolved = 0} ones.
     * @param threads   worker count for the per-node read/compute phase (≥ 1).
     */
    public ResolveStats resolveAll(boolean reResolve, int threads) {
        threads = Math.max(1, threads);
        long nodes = 0, edges = 0, unresolved = 0;

        String where = reResolve ? "artifact_id > ?" : "deps_resolved = 0 AND artifact_id > ?";
        String pageSql = "SELECT artifact_id FROM pom_meta WHERE " + where + " ORDER BY artifact_id LIMIT ?";
        String countSql = "SELECT COUNT(*) FROM pom_meta" + (reResolve ? "" : " WHERE deps_resolved = 0");

        Connection writeConn = null;
        Connection pageConn = null;
        List<Reader> readers = new ArrayList<>();
        ExecutorService pool = null;
        Writer writer = null;
        Progress progress = Progress.start("Graph resolve");

        try {
            // The single writer connection. Opened FIRST: a read-write open sets
            // journal_mode=WAL (a persistent database property), which is what lets the
            // read-only pager/reader connections below run alongside the writer.
            writeConn = Sqlite.open(dbPath);
            writeConn.setAutoCommit(false);
            writer = new Writer(writeConn);

            // A read-only connection for keyset paging.
            pageConn = Sqlite.openReadOnly(dbPath);

            // Pre-create one reader (and its own read-only connection) per worker thread,
            // handed out via a queue so each pool thread claims exactly one.
            BlockingQueue<Reader> free = new ArrayBlockingQueue<>(threads);
            for (int i = 0; i < threads; i++) {
                Reader r = new Reader(Sqlite.openReadOnly(dbPath));
                readers.add(r);
                free.add(r);
            }
            ThreadLocal<Reader> readerTL = ThreadLocal.withInitial(free::poll);
            pool = Executors.newFixedThreadPool(threads);

            try (PreparedStatement cnt = pageConn.prepareStatement(countSql);
                 ResultSet rs = cnt.executeQuery()) {
                if (rs.next()) progress.total(rs.getLong(1));
            }
            log.info("Per-node resolve{} with {} reader worker(s), one writer", reResolve ? " (--all)" : "", threads);

            int cursor = -1;
            try (PreparedStatement pagePs = pageConn.prepareStatement(pageSql)) {
                while (true) {
                    pagePs.setInt(1, cursor);
                    pagePs.setInt(2, PAGE_SIZE);
                    List<Integer> page = new ArrayList<>(PAGE_SIZE);
                    try (ResultSet rs = pagePs.executeQuery()) {
                        while (rs.next()) page.add(rs.getInt(1));
                    }
                    if (page.isEmpty()) break;
                    cursor = page.get(page.size() - 1);

                    List<Future<NodeResult>> futures = new ArrayList<>(page.size());
                    for (int id : page) {
                        futures.add(pool.submit(() -> readerTL.get().resolve(id)));
                    }
                    for (Future<NodeResult> f : futures) {
                        NodeResult r;
                        try {
                            r = f.get();
                        } catch (ExecutionException ee) {
                            log.warn("resolve worker failed: {}", ee.getCause() == null ? ee.getMessage() : ee.getCause().getMessage());
                            continue;
                        }
                        if (r == null) { progress.tick(); continue; }
                        edges += writer.write(r);
                        unresolved += r.unresolved();
                        nodes++;
                        progress.tick();
                    }
                    writer.commit();
                }
            }
            writer.commit();
            progress.done();
            log.info("Resolve complete: {} node(s), {} edge(s), {} unresolved", nodes, edges, unresolved);
        } catch (SQLException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("resolveAll failed", e);
        } finally {
            if (pool != null) {
                pool.shutdown();
                try {
                    if (!pool.awaitTermination(60, TimeUnit.SECONDS)) pool.shutdownNow();
                } catch (InterruptedException ie) {
                    pool.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            if (writer != null) writer.close();
            for (Reader r : readers) r.close();
            closeQuietly(pageConn);
            closeQuietly(writeConn);
        }
        return new ResolveStats(nodes, edges, unresolved);
    }

    private static void closeQuietly(Connection c) {
        try { if (c != null) c.close(); } catch (SQLException ignore) { /* ignore */ }
    }

    // ----------------------------------------------------------- resolution math

    private static String key(String g, String a, String type, String classifier) {
        return g + ":" + a + ":" + (type == null || type.isBlank() ? "jar" : type) + ":" + (classifier == null ? "" : classifier);
    }

    private static String coord(String g, String a, String v) {
        return g + ":" + a + ":" + v;
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

    private static <K, V> Map<K, V> lru(int cap) {
        return new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > cap;
            }
        };
    }

    // ------------------------------------------------------------------ Reader

    /**
     * Read-only resolver bound to one connection. Single-threaded use: one
     * {@code Reader} per worker thread. Caches parent/BOM nodes and existing
     * artifact ids in bounded LRUs so common parents are loaded once per worker.
     */
    private static final class Reader implements AutoCloseable {
        private final Connection conn;
        private final PreparedStatement selMeta, selProps, selDm, selDirect, selArt;
        private final Map<String, NodeData> parentByCoord = lru(PARENT_CACHE);
        private final Map<String, Integer> idByCoord = lru(READER_ID_CACHE);

        Reader(Connection conn) throws SQLException {
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
        }

        /** Resolve one node into edges/pending/unresolved without writing anything. */
        NodeResult resolve(int id) throws SQLException {
            NodeData node = loadById(id);
            if (node == null) return null;

            Map<String, String> props = effectiveProps(node);
            Map<String, String> dm = effectiveDependencyManagement(node, props);
            List<RawDirect> deps = loadDirect(id);

            List<ResolvedEdge> resolved = new ArrayList<>();
            List<PendingEdge> pending = new ArrayList<>();
            // A POM may declare the same dependency twice; de-dup edges per node so the
            // writer never queues duplicate (parent, child, scope) tuples.
            Set<String> seen = new HashSet<>();
            int unresolved = 0;
            for (RawDirect d : deps) {
                String g = interpolate(d.gid(), props);
                String a = interpolate(d.aid(), props);
                String v = (d.version() != null && !d.version().isBlank())
                        ? interpolate(d.version(), props)
                        : dm.get(key(d.gid(), d.aid(), d.type(), d.classifier()));
                if (v != null) v = interpolate(v, props);

                if (!concrete(g) || !concrete(a) || !concrete(v)) { unresolved++; continue; }
                String scope = d.scope() != null ? d.scope() : "compile";
                Integer childId = findExistingId(g, a, v);
                if (childId != null) {
                    if (childId != id && seen.add("r " + childId + " " + scope)) {
                        resolved.add(new ResolvedEdge(childId, scope));
                    }
                } else if (seen.add("p " + g + " " + a + " " + v + " " + scope)) {
                    pending.add(new PendingEdge(g, a, v, scope));
                }
            }
            return new NodeResult(id, resolved, pending, unresolved);
        }

        /** Load a node by id directly (no caching — used for the primary node and parents). */
        private NodeData loadById(int id) throws SQLException {
            selMeta.setInt(1, id);
            try (ResultSet rs = selMeta.executeQuery()) {
                if (!rs.next()) return null;
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
                return new NodeData(id, rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(1), rs.getString(2), rs.getString(3), props, dm);
            }
        }

        private List<RawDirect> loadDirect(int id) throws SQLException {
            List<RawDirect> out = new ArrayList<>();
            selDirect.setInt(1, id);
            try (ResultSet rs = selDirect.executeQuery()) {
                while (rs.next()) out.add(new RawDirect(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)));
            }
            return out;
        }

        /** Parent/BOM node by coordinate (cached, incl. known-absent). */
        private NodeData nodeByCoord(String g, String a, String v) throws SQLException {
            if (g == null || a == null || v == null || v.contains("${")) return null;
            String ck = coord(g, a, v);
            NodeData cached = parentByCoord.get(ck);
            if (cached != null) return cached == ABSENT_NODE ? null : cached;
            Integer id = findExistingId(g, a, v);
            NodeData nd = (id == null) ? null : loadById(id);
            parentByCoord.put(ck, nd == null ? ABSENT_NODE : nd);
            return nd;
        }

        /** Existing artifact id for a coordinate (cached, incl. known-absent). */
        private Integer findExistingId(String g, String a, String v) throws SQLException {
            String ck = coord(g, a, v);
            Integer c = idByCoord.get(ck);
            if (c != null) return c.equals(ABSENT_ID) ? null : c;
            Integer id = null;
            selArt.setString(1, g);
            selArt.setString(2, a);
            selArt.setString(3, v);
            try (ResultSet rs = selArt.executeQuery()) {
                if (rs.next()) id = rs.getInt(1);
            }
            idByCoord.put(ck, id == null ? ABSENT_ID : id);
            return id;
        }

        private Map<String, String> effectiveProps(NodeData node) throws SQLException {
            Map<String, String> out = new HashMap<>();
            Set<Integer> visited = new HashSet<>();
            NodeData cur = node;
            while (cur != null && visited.add(cur.id())) {
                for (Map.Entry<String, String> e : cur.props().entrySet()) out.putIfAbsent(e.getKey(), e.getValue());
                cur = nodeByCoord(cur.parentGid(), cur.parentAid(), cur.parentVersion());
            }
            for (String k : new String[]{"project.version", "pom.version", "version"}) out.putIfAbsent(k, node.version());
            for (String k : new String[]{"project.groupId", "pom.groupId", "groupId"}) out.putIfAbsent(k, node.gid());
            for (String k : new String[]{"project.artifactId", "pom.artifactId", "artifactId"}) out.putIfAbsent(k, node.aid());
            return out;
        }

        private Map<String, String> effectiveDependencyManagement(NodeData node, Map<String, String> props) throws SQLException {
            Map<String, String> out = new HashMap<>();
            collectDm(node, props, out, new HashSet<>());
            return out;
        }

        /**
         * Collect effective managed versions from {@code n}, its parent chain, and its
         * import-scope BOMs (recursively — BOM-of-BOM is followed, bounded by the shared
         * {@code visited} set). {@code contextProps} is the property environment in which
         * <em>this</em> node's values are interpolated: the original node's effective
         * properties for the node itself and its parent chain, but a <b>BOM's own</b>
         * effective properties for an imported BOM's entries — a BOM like
         * {@code spring-boot-dependencies} manages versions via properties defined in the
         * BOM (or its parents), which are invisible from the consumer's chain.
         */
        private void collectDm(NodeData n, Map<String, String> contextProps,
                               Map<String, String> out, Set<Integer> visited) throws SQLException {
            if (n == null || !visited.add(n.id())) return;
            for (Managed m : n.dm()) {
                if (!"import".equalsIgnoreCase(m.scope())) {
                    out.putIfAbsent(key(m.gid(), m.aid(), m.type(), m.classifier()), interpolate(m.version(), contextProps));
                }
            }
            for (Managed m : n.dm()) {
                if ("import".equalsIgnoreCase(m.scope())) {
                    String bv = interpolate(m.version(), contextProps);
                    NodeData bom = nodeByCoord(m.gid(), m.aid(), bv);
                    if (bom != null) collectDm(bom, effectiveProps(bom), out, visited);
                }
            }
            collectDm(nodeByCoord(n.parentGid(), n.parentAid(), n.parentVersion()), contextProps, out, visited);
        }

        @Override
        public void close() {
            for (PreparedStatement ps : new PreparedStatement[]{selMeta, selProps, selDm, selDirect, selArt}) {
                try { if (ps != null) ps.close(); } catch (SQLException ignore) { /* ignore */ }
            }
            closeQuietly(conn);
        }
    }

    // ------------------------------------------------------------------ Writer

    /**
     * The single writer: inserts edges and synthetic child artifacts and marks nodes
     * resolved. Used only on the collector thread, so it is the sole writer to the
     * database. Edge inserts and resolved-marks are batched and flushed periodically
     * (committed per worklist page by the caller); synthetic-artifact inserts are
     * immediate (they need the new id, fetched via {@code last_insert_rowid()} —
     * {@code artifacts.id} is an {@code INTEGER PRIMARY KEY} rowid alias, so the id
     * is assigned by omitting it) and de-duplicated via an LRU id cache backed by
     * the unique constraint.
     */
    private static final class Writer implements AutoCloseable {
        private final Connection conn;
        private final PreparedStatement selArt, insArt, lastId, insEdge, markRes;
        private final Map<String, Integer> idByCoord = lru(WRITER_ID_CACHE);
        private int sinceFlush = 0;

        Writer(Connection conn) throws SQLException {
            this.conn = conn;
            selArt = conn.prepareStatement(
                    "SELECT id FROM artifacts WHERE gid = ? AND aid = ? AND version = ? AND classifier = ''");
            insArt = conn.prepareStatement(
                    "INSERT INTO artifacts (gid, aid, version, classifier) VALUES (?, ?, ?, '')");
            lastId = conn.prepareStatement("SELECT last_insert_rowid()");
            insEdge = conn.prepareStatement(
                    "INSERT OR IGNORE INTO dependencies (parent_id, child_id, scope) VALUES (?, ?, ?)");
            markRes = conn.prepareStatement("UPDATE pom_meta SET deps_resolved = 1 WHERE artifact_id = ?");
        }

        /** Apply one node's result; returns the number of edges queued. */
        long write(NodeResult r) throws SQLException {
            long e = 0;
            for (ResolvedEdge ed : r.resolved()) {
                addEdge(r.nodeId(), ed.childId(), ed.scope());
                e++;
            }
            for (PendingEdge p : r.pending()) {
                int childId = getOrInsert(p.gid(), p.aid(), p.version());
                if (childId != r.nodeId()) {
                    addEdge(r.nodeId(), childId, p.scope());
                    e++;
                }
            }
            markRes.setInt(1, r.nodeId());
            markRes.addBatch();
            if (++sinceFlush >= WRITE_FLUSH_NODES) flush();
            return e;
        }

        private void addEdge(int parentId, int childId, String scope) throws SQLException {
            insEdge.setInt(1, parentId);
            insEdge.setInt(2, childId);
            insEdge.setString(3, scope);
            insEdge.addBatch();
        }

        private int getOrInsert(String g, String a, String v) throws SQLException {
            String ck = coord(g, a, v);
            Integer id = idByCoord.get(ck);
            if (id != null && !id.equals(ABSENT_ID)) return id;
            // Look up (sees this writer's own uncommitted inserts on this connection).
            selArt.setString(1, g);
            selArt.setString(2, a);
            selArt.setString(3, v);
            try (ResultSet rs = selArt.executeQuery()) {
                if (rs.next()) { id = rs.getInt(1); idByCoord.put(ck, id); return id; }
            }
            insArt.setString(1, g);
            insArt.setString(2, a);
            insArt.setString(3, v);
            insArt.executeUpdate();
            try (ResultSet rs = lastId.executeQuery()) {
                if (rs.next()) id = rs.getInt(1);
            }
            if (id == null) throw new SQLException("could not resolve artifact id for " + ck);
            idByCoord.put(ck, id);
            return id;
        }

        private void flush() throws SQLException {
            insEdge.executeBatch();
            markRes.executeBatch();
            sinceFlush = 0;
        }

        void commit() throws SQLException {
            flush();
            conn.commit();
        }

        @Override
        public void close() {
            try { flush(); } catch (SQLException ignore) { /* best effort */ }
            try { conn.commit(); } catch (SQLException ignore) { /* best effort */ }
            for (PreparedStatement ps : new PreparedStatement[]{selArt, insArt, lastId, insEdge, markRes}) {
                try { if (ps != null) ps.close(); } catch (SQLException ignore) { /* ignore */ }
            }
        }
    }
}
