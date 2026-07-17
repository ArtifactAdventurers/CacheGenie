package dev.gruff.hardstop.cachegenie.graph;

import dev.gruff.hardstop.cachegenie.utils.Progress;
import org.duckdb.DuckDBConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 * common cases — parent-chain inheritance of managed versions, import BOMs, and
 * {@code ${...}} property interpolation — but knowingly does NOT handle: version
 * ranges (used verbatim), profile-activated dependencies/properties, mirror/
 * relocation, {@code <exclusions>}, settings.xml, or active-by-default profiles. A
 * dependency whose version can't be resolved to a concrete string is left
 * unresolved (no edge) and counted, not guessed. Inherited project metadata
 * (scm/org/…) is NOT coalesced here — raw values stay in {@code pom_meta}; a
 * metadata-resolution pass over the same parent chain is a separate follow-up.
 *
 * <h2>Scaling: streamed worklist + parallel read, single funnelled writer</h2>
 * The worklist is read in bounded keyset pages ordered by {@code artifact_id}
 * (cursor-paged like {@code graph mine}), so memory is constant regardless of the
 * backlog size — the old path materialised the whole {@code todo} list (and cached
 * every node), which OOM'd on a full-Central run.
 *
 * <p>The per-node resolve is dominated by DB <em>reads</em> (loading parent/BOM
 * nodes, properties, dependencyManagement), so it is parallelised across a worker
 * pool: each worker owns a {@link DuckDBConnection#duplicate() duplicated}
 * connection sharing the one in-process database (DuckDB MVCC permits concurrent
 * readers alongside a single writer). Workers only <em>read</em> and produce a
 * {@link NodeResult} of resolved child ids / pending inserts; <b>all writes</b>
 * (edge inserts, synthetic-artifact inserts, {@code deps_resolved} updates) funnel
 * through one writer connection on the collector thread, so there is never more
 * than one writer (DuckDB's single-writer rule holds). Per-thread parent/id caches
 * and the writer's id cache are bounded LRUs.
 */
public final class PomResolver {

    private static final Logger log = LoggerFactory.getLogger(PomResolver.class);

    /** Worklist page size (rows pulled per keyset query). */
    private static final int PAGE_SIZE = 50_000;
    /** Residue nodes processed per batch in the parent-chain set-based pass (bounds peak memory:
     *  effective-property maps explode per node — each node materialises a row per ancestor
     *  property/managed entry — so too large a batch OOMs regardless of the node count). Kept
     *  conservative; raise via {@code --inherited-batch} on a big-memory box. */
    private static final int INHERITED_BATCH = 25_000;
    /** Max levels to follow import-BOM-of-import-BOM chains (transitive BOM closure). */
    private static final int BOM_DEPTH = 6;
    /** Writer flushes its edge/mark batches every this many nodes. */
    private static final int WRITE_FLUSH_NODES = 5_000;
    /** Bounded-LRU capacities (per reader, and for the writer's id cache). */
    private static final int PARENT_CACHE = 100_000;
    private static final int READER_ID_CACHE = 500_000;
    private static final int WRITER_ID_CACHE = 1_000_000;

    private final String dbPath;
    /** Optional DuckDB memory cap (e.g. {@code "8GB"}) applied DB-wide for the whole resolve;
     *  null/blank = DuckDB's own default (~80% of RAM). Capping this leaves headroom for the
     *  JVM heap so the two together don't exceed physical RAM and trip the OS OOM-killer. */
    private final String memLimit;
    /** Override for the parent-chain batch size; {@code <= 0} uses {@link #INHERITED_BATCH}. */
    private final int inheritedBatchOverride;
    /** DuckDB worker threads for the SQL passes; {@code <= 0} leaves DuckDB's default (one per core).
     *  Lowering it is a strong memory lever — DuckDB builds a hash table per thread for the big
     *  set-based joins/aggregates, so peak memory scales with this. */
    private final int dbThreads;

    public PomResolver(File cacheGenieRoot) {
        this(cacheGenieRoot, null, 0, 0);
    }

    public PomResolver(File cacheGenieRoot, String memLimit, int inheritedBatchOverride, int dbThreads) {
        this.dbPath = new File(cacheGenieRoot, "graph.db").getAbsolutePath();
        this.memLimit = memLimit;
        this.inheritedBatchOverride = inheritedBatchOverride;
        this.dbThreads = dbThreads;
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
        return resolveAll(reResolve, 1, false);
    }

    public ResolveStats resolveAll(boolean reResolve, int threads) {
        return resolveAll(reResolve, threads, false);
    }

    /**
     * Resolve mined nodes into edges. Runs the set-based passes first (literal +
     * same-POM, then parent-chain inherited), then — unless {@code setBasedOnly} — the
     * streamed per-node pass over the remaining residue using {@code threads} reader
     * workers and one writer.
     *
     * @param reResolve    re-resolve every mined node, not just {@code deps_resolved = FALSE} ones
     *                     (skips the set-based passes).
     * @param threads      worker count for the per-node read/compute phase (≥ 1).
     * @param setBasedOnly run only the set-based passes and skip the per-node pass (useful while the
     *                     per-node residue — import BOMs, embedded tokens — is still scan-bound).
     */
    public ResolveStats resolveAll(boolean reResolve, int threads, boolean setBasedOnly) {
        threads = Math.max(1, threads);
        long nodes = 0, edges = 0, unresolved = 0;

        String where = reResolve ? "artifact_id > ?" : "deps_resolved = FALSE AND artifact_id > ?";
        String pageSql = "SELECT artifact_id FROM pom_meta WHERE " + where + " ORDER BY artifact_id LIMIT ?";
        String countSql = "SELECT COUNT(*) FROM pom_meta" + (reResolve ? "" : " WHERE deps_resolved = FALSE");

        DuckDBConnection root = null;
        Connection writeConn = null;
        Connection pageConn = null;
        List<Reader> readers = new ArrayList<>();
        ExecutorService pool = null;
        Writer writer = null;
        long sbNodes = 0, sbEdges = 0;
        Progress progress = Progress.start("Graph resolve");

        try {
            // The single writer connection (also the shared in-process database root).
            writeConn = DriverManager.getConnection("jdbc:duckdb:" + dbPath);
            root = writeConn.unwrap(DuckDBConnection.class);

            // Give DuckDB an explicit spill directory and (optionally) a memory cap before
            // any pass runs. The inherited set-based pass materialises each node's full
            // ancestor property/management closure as temp tables (a row per (node, ancestor)
            // pair × every property/managed entry on the chain), which can dwarf the batch's
            // node count; without a spill target and a limit that leaves room for the JVM heap,
            // that peak trips the OS OOM-killer. memory_limit and temp_directory are DB-wide
            // (global) settings, so setting them once on the root connection also governs the
            // duplicated reader/pager connections used by the per-node pass.
            applyMemoryGuards(writeConn);

            // NB: we do NOT build per-artifact_id secondary indexes here. DuckDB does not
            // use a secondary ART index to serve a `WHERE artifact_id = ?` filter (it
            // table-scans with the filter pushed down — verified via EXPLAIN), so such an
            // index costs minutes to build on the 100M-row mining tables for no benefit.
            // The remedy is set-based resolution (below), not point-lookup indexes.

            // Set-based fast path (DuckDB's strength): resolve in a handful of hash-join
            // statements every POM whose deps are all context-free — literal versions,
            // same-POM ${property} tokens, and same-POM managed versions — and mark those
            // POMs resolved. The streamed per-node pass below then only chews the residue
            // that needs INHERITED context (parent-chain properties/management, import
            // BOMs, embedded/partial tokens). The per-row path would table-scan the
            // 100M-row mining tables once per node (DuckDB does not use the artifact_id
            // indexes for these filters), so set-based is the real scalability lever.
            if (!reResolve) {
                long[] sb = resolveSetBased(writeConn);             // A/B/C: literal + same-POM
                sbEdges += sb[0];
                sbNodes += sb[1];
                long[] ib = resolveInheritedSetBased(writeConn);    // D/E/F: parent-chain + import-BOM
                sbEdges += ib[0];
                sbNodes += ib[1];
            }

            if (!setBasedOnly) {
                writeConn.setAutoCommit(false);
                writer = new Writer(writeConn);

                // A duplicated read connection for keyset paging.
                pageConn = root.duplicate();

                // Pre-create one reader (and its duplicated connection) per worker thread,
                // handed out via a queue so each pool thread claims exactly one.
                BlockingQueue<Reader> free = new ArrayBlockingQueue<>(threads);
                for (int i = 0; i < threads; i++) {
                    Reader r = new Reader(root.duplicate());
                    readers.add(r);
                    free.add(r);
                }
                ThreadLocal<Reader> readerTL = ThreadLocal.withInitial(free::poll);
                pool = Executors.newFixedThreadPool(threads);

                try (PreparedStatement cnt = pageConn.prepareStatement(countSql);
                     ResultSet rs = cnt.executeQuery()) {
                    if (rs.next()) progress.total(rs.getLong(1));
                }
                log.info("Per-node pass over residue{} with {} worker(s)", reResolve ? " (--all)" : "", threads);

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
            }
            progress.done();
            log.info("Resolve complete: {} node(s) ({} set-based + {} per-node), {} edge(s), {} unresolved",
                    nodes + sbNodes, sbNodes, nodes, edges + sbEdges, unresolved);
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
        return new ResolveStats(nodes + sbNodes, edges + sbEdges, unresolved);
    }

    /** Regex matching a dependency version that is exactly one property token {@code ${name}} (group 1 = name). */
    private static final String PROP_TOKEN = "regexp_extract(dd.dep_version, '^\\$\\{([^}]+)\\}$', 1)";

    /**
     * Set-based resolution of the <em>context-free</em> majority, done in a handful
     * of hash-join statements (DuckDB's strength) before the per-node pass. Three
     * passes, each {synthesise missing target artifacts, insert edges}:
     * <ol>
     *   <li><b>A — literal:</b> {@code dep_version} is a concrete literal.</li>
     *   <li><b>B — same-POM property:</b> {@code dep_version} is exactly {@code ${name}}
     *       and {@code name} is defined (to a concrete value) in <em>this</em> POM's
     *       {@code pom_properties}.</li>
     *   <li><b>C — same-POM managed:</b> {@code dep_version} is null and a non-import
     *       {@code dependency_management} entry in <em>this</em> POM pins a concrete
     *       literal version for the same coordinate (matching type/classifier).</li>
     * </ol>
     * Then it marks a POM {@code deps_resolved = TRUE} iff <em>every</em> direct dep is
     * resolvable by A/B/C — the predicate is the exact complement of the three passes,
     * so a POM is only marked when all its edges have actually been written (missing
     * targets are synthesised, so "resolvable" always yields an edge). Anything needing
     * <em>inherited</em> context — a parent-chain property, an inherited or import-BOM
     * managed version, an embedded/partial {@code ${...}}, or a property that resolves
     * to another {@code ${...}} — is left for the streamed per-node pass.
     *
     * @return {@code {edgesInserted, pomsMarkedResolved}}.
     */
    private long[] resolveSetBased(Connection conn) throws SQLException {
        // Shared join to restrict every pass to not-yet-resolved POMs.
        String pm = " JOIN pom_meta pm ON pm.artifact_id = dd.artifact_id AND pm.deps_resolved = FALSE ";

        // Pass A — literal concrete versions.
        String aFrom = "FROM direct_dep dd" + pm;
        String aWhere = "WHERE dd.dep_version IS NOT NULL AND dd.dep_version NOT LIKE '%${%'";
        // Pass B — version is exactly ${name} with name defined to a concrete value in this POM.
        String bFrom = "FROM direct_dep dd" + pm
                + "JOIN pom_properties pp ON pp.artifact_id = dd.artifact_id AND pp.prop_key = " + PROP_TOKEN + " ";
        String bWhere = "WHERE " + PROP_TOKEN + " <> '' AND pp.prop_value NOT LIKE '%${%'";
        // Pass C — null version pinned by a non-import managed entry in this POM.
        String cFrom = "FROM direct_dep dd" + pm
                + "JOIN dependency_management dm ON dm.artifact_id = dd.artifact_id AND dm.dep_gid = dd.dep_gid AND dm.dep_aid = dd.dep_aid "
                + "AND lower(coalesce(dm.scope, '')) <> 'import' "
                + "AND coalesce(NULLIF(dm.dep_type, ''), 'jar') = coalesce(NULLIF(dd.dep_type, ''), 'jar') "
                + "AND coalesce(dm.dep_classifier, '') = coalesce(dd.dep_classifier, '') ";
        String cWhere = "WHERE (dd.dep_version IS NULL OR dd.dep_version = '') AND dm.dep_version IS NOT NULL AND dm.dep_version NOT LIKE '%${%'";

        boolean prev = conn.getAutoCommit();
        conn.setAutoCommit(true);
        long edges = 0, synth = 0, marked;
        try (Statement st = conn.createStatement()) {
            synth += st.executeUpdate(synthSql(aFrom, "dd.dep_version", aWhere));
            edges += st.executeUpdate(edgeSql(aFrom, "dd.dep_version", aWhere));
            synth += st.executeUpdate(synthSql(bFrom, "pp.prop_value", bWhere));
            edges += st.executeUpdate(edgeSql(bFrom, "pp.prop_value", bWhere));
            synth += st.executeUpdate(synthSql(cFrom, "dm.dep_version", cWhere));
            edges += st.executeUpdate(edgeSql(cFrom, "dm.dep_version", cWhere));
            marked = st.executeUpdate(markSql());
        } finally {
            conn.setAutoCommit(prev);
        }
        log.info("Set-based pass (literal + same-POM property/managed): {} POM(s) fully resolved, {} edge(s), {} synthetic artifact(s)",
                marked, edges, synth);
        return new long[]{edges, marked};
    }

    /** Synthesise artifact rows for a pass's resolved target coordinates that don't exist yet. */
    private static String synthSql(String from, String verExpr, String where) {
        return "INSERT INTO artifacts (id, gid, aid, version, classifier) " +
                "SELECT nextval('seq_artifact_id'), x.g, x.a, x.v, '' FROM (" +
                "SELECT DISTINCT dd.dep_gid AS g, dd.dep_aid AS a, " + verExpr + " AS v " + from + where + ") x " +
                "LEFT JOIN artifacts ar ON ar.gid = x.g AND ar.aid = x.a AND ar.version = x.v AND ar.classifier = '' " +
                "WHERE ar.id IS NULL";
    }

    /**
     * Insert resolvable edges for a pass (targets now exist after the matching synth pass).
     * {@code SELECT DISTINCT} is required: a POM may declare the same dependency more than
     * once (duplicate {@code direct_dep} rows are kept), which would otherwise emit duplicate
     * {@code (parent_id, child_id, scope)} tuples in one statement — DuckDB rejects an
     * {@code INSERT OR IGNORE}/ON CONFLICT command that contains duplicate conflict keys.
     */
    private static String edgeSql(String from, String verExpr, String where) {
        return "INSERT OR IGNORE INTO dependencies (parent_id, child_id, scope) " +
                "SELECT DISTINCT dd.artifact_id, ar.id, COALESCE(NULLIF(dd.scope, ''), 'compile') " + from +
                "JOIN artifacts ar ON ar.gid = dd.dep_gid AND ar.aid = dd.dep_aid AND ar.version = " + verExpr +
                " AND ar.classifier = '' AND ar.id <> dd.artifact_id " + where;
    }

    /**
     * Mark a POM resolved iff none of its direct deps still needs inherited context —
     * i.e. the exact complement of passes A/B/C. A dep "needs context" when its version
     * is null/empty or contains {@code ${...}}, AND it is neither (B) an exact
     * {@code ${name}} resolved by a concrete same-POM property nor (C) a null version
     * pinned by a concrete same-POM non-import managed entry.
     */
    private static String markSql() {
        return "UPDATE pom_meta SET deps_resolved = TRUE WHERE deps_resolved = FALSE AND artifact_id NOT IN (" +
                "SELECT DISTINCT dd.artifact_id FROM direct_dep dd " +
                "WHERE (dd.dep_version IS NULL OR dd.dep_version = '' OR dd.dep_version LIKE '%${%') " +
                "AND NOT EXISTS (SELECT 1 FROM pom_properties pp WHERE pp.artifact_id = dd.artifact_id " +
                "AND pp.prop_key = " + PROP_TOKEN + " AND " + PROP_TOKEN + " <> '' AND pp.prop_value NOT LIKE '%${%') " +
                "AND NOT EXISTS (SELECT 1 FROM dependency_management dm WHERE dm.artifact_id = dd.artifact_id " +
                "AND dm.dep_gid = dd.dep_gid AND dm.dep_aid = dd.dep_aid AND lower(coalesce(dm.scope, '')) <> 'import' " +
                "AND coalesce(NULLIF(dm.dep_type, ''), 'jar') = coalesce(NULLIF(dd.dep_type, ''), 'jar') " +
                "AND coalesce(dm.dep_classifier, '') = coalesce(dd.dep_classifier, '') " +
                "AND (dd.dep_version IS NULL OR dd.dep_version = '') AND dm.dep_version IS NOT NULL AND dm.dep_version NOT LIKE '%${%'))";
    }

    /**
     * Set-based resolution of the <em>parent-chain</em> residue — the dominant remaining
     * case (~95% of unresolved POMs have a parent). Builds, over the not-yet-resolved
     * POMs only:
     * <ol>
     *   <li>{@code np} — each POM's parent artifact id;</li>
     *   <li>{@code anc} — each POM plus its full ancestor chain with depth (recursive CTE,
     *       depth-capped against cycles);</li>
     *   <li>{@code eff_prop} — effective properties (nearest ancestor wins), with a few
     *       interpolation passes for exact {@code ${other}} property values;</li>
     *   <li>{@code eff_mgmt} — effective non-import managed versions (nearest wins), with
     *       managed {@code ${...}} values interpolated through {@code eff_prop}.</li>
     * </ol>
     * It also resolves <b>import BOMs, transitively</b>: each node's directly-imported BOMs
     * (from any ancestor's {@code scope=import} dm, BOM version interpolated via the node's
     * properties) are found, then the import graph is followed to a fixpoint — a BOM's own
     * imported BOMs (version interpolated via that BOM's properties) are added, up to
     * {@link #BOM_DEPTH} levels. Each reached BOM's own effective managed versions (BOM +
     * its parent chain, interpolated via the BOM's properties) are computed and attributed
     * to consumers by join — cheap because distinct BOMs are few even though millions of
     * POMs inherit them. Parent-chain managed versions take precedence over BOM-managed.
     *
     * <p>Each residue dep's version is then resolved (literal as-is, exact {@code ${name}} via
     * {@code eff_prop}, null via {@code eff_mgmt} falling back to import-BOM management);
     * concrete results are synthesised + edged, and a POM is marked resolved iff
     * <em>every</em> dep resolved to a concrete version. Embedded/partial {@code ${...}},
     * profiles, version ranges, and exclusions are still left for the per-node fallback.
     * No network.
     *
     * @return {@code {edgesInserted, pomsMarkedResolved}}.
     */
    private long[] resolveInheritedSetBased(Connection conn) throws SQLException {
        String reV = "regexp_extract(v.val, '^\\$\\{([^}]+)\\}$', 1)";       // eff_prop self-interpolation (alias v)
        String reM = "regexp_extract(em.version, '^\\$\\{([^}]+)\\}$', 1)";  // eff_mgmt version interpolation
        String reD = "regexp_extract(dd.dep_version, '^\\$\\{([^}]+)\\}$', 1)"; // direct-dep token
        String reP = "regexp_extract(dm.dep_version, '^\\$\\{([^}]+)\\}$', 1)"; // import-BOM version token
        String reBM = "regexp_extract(bm.version, '^\\$\\{([^}]+)\\}$', 1)";    // BOM managed-version token

        boolean prev = conn.getAutoCommit();
        conn.setAutoCommit(true);
        long edges = 0, marked = 0;
        try (Statement st = conn.createStatement()) {
            // Cut peak memory of the large INSERT...SELECT / sort operators.
            try { st.execute("SET preserve_insertion_order=false"); } catch (SQLException ignore) { /* older DuckDB */ }

            for (String t : new String[]{"np", "ibatch", "anc", "eff_prop", "eff_mgmt", "node_bom", "cur_boms", "bom_sub", "bom_ids", "bom_anc", "bom_prop", "bom_mgmt", "resolved_dep", "good_dep"}) {
                st.execute("DROP TABLE IF EXISTS " + t);
            }
            // Parent links for ALL POMs — built once; tiny (two ints per row).
            st.execute("CREATE TEMP TABLE np AS SELECT pm.artifact_id AS child, pa.id AS parent " +
                    "FROM pom_meta pm JOIN artifacts pa ON pa.gid = pm.parent_gid AND pa.aid = pm.parent_aid " +
                    "AND pa.version = pm.parent_version AND pa.classifier = '' WHERE pm.parent_gid IS NOT NULL");

            // Process the residue in bounded keyset batches. The per-node effective-property
            // map explodes (every descendant inherits ALL of its ancestors' properties), so
            // building it for the whole residue at once exhausts memory; a batch keeps the
            // intermediates small. Nodes are paged by artifact_id ascending and marked within
            // their batch, so each residue node is processed exactly once.
            final int batch = inheritedBatchOverride > 0 ? inheritedBatchOverride : INHERITED_BATCH;
            log.info("Inherited (parent-chain) set-based pass: batch size {}", batch);
            int cursor = -1;
            while (true) {
                st.execute("DROP TABLE IF EXISTS ibatch");
                st.execute("CREATE TEMP TABLE ibatch AS SELECT artifact_id FROM pom_meta " +
                        "WHERE deps_resolved = FALSE AND artifact_id > " + cursor +
                        " ORDER BY artifact_id LIMIT " + batch);
                int cnt;
                int maxId = cursor;
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*), COALESCE(MAX(artifact_id), " + cursor + ") FROM ibatch")) {
                    rs.next();
                    cnt = rs.getInt(1);
                    maxId = rs.getInt(2);
                }
                if (cnt == 0) break;
                cursor = maxId;

                for (String t : new String[]{"anc", "eff_prop", "eff_mgmt", "node_bom", "cur_boms", "bom_sub", "bom_ids", "bom_anc", "bom_prop", "bom_mgmt", "resolved_dep", "good_dep"}) {
                    st.execute("DROP TABLE IF EXISTS " + t);
                }
                st.execute("CREATE TEMP TABLE anc AS WITH RECURSIVE w(node, cur, depth) AS (" +
                        "SELECT artifact_id, artifact_id, 0 FROM ibatch " +
                        "UNION ALL SELECT w.node, np.parent, w.depth + 1 FROM w JOIN np ON np.child = w.cur WHERE w.depth < 30) " +
                        "SELECT * FROM w");
                st.execute("CREATE TEMP TABLE eff_prop AS SELECT node, prop_key AS key, arg_min(prop_value, depth) AS val " +
                        "FROM anc a JOIN pom_properties pp ON pp.artifact_id = a.cur GROUP BY node, prop_key");
                // Resolve ${other} property values that reference another property of the same node (a few passes).
                for (int i = 0; i < 3; i++) {
                    st.executeUpdate("UPDATE eff_prop v SET val = (SELECT e2.val FROM eff_prop e2 WHERE e2.node = v.node AND e2.key = " + reV + ") " +
                            "WHERE " + reV + " <> '' AND EXISTS (SELECT 1 FROM eff_prop e2 WHERE e2.node = v.node AND e2.key = " + reV + ")");
                }
                st.execute("CREATE TEMP TABLE eff_mgmt AS SELECT node, dep_gid AS g, dep_aid AS a, " +
                        "coalesce(NULLIF(dep_type, ''), 'jar') AS t, coalesce(dep_classifier, '') AS cl, arg_min(dep_version, depth) AS version " +
                        "FROM anc a JOIN dependency_management dm ON dm.artifact_id = a.cur AND lower(coalesce(dm.scope, '')) <> 'import' " +
                        "GROUP BY node, dep_gid, dep_aid, coalesce(NULLIF(dep_type, ''), 'jar'), coalesce(dep_classifier, '')");
                st.executeUpdate("UPDATE eff_mgmt em SET version = (SELECT ep.val FROM eff_prop ep WHERE ep.node = em.node AND ep.key = " + reM + ") " +
                        "WHERE " + reM + " <> '' AND EXISTS (SELECT 1 FROM eff_prop ep WHERE ep.node = em.node AND ep.key = " + reM + ")");

                // Import-BOM managed versions. Each batch node's imported BOMs (from any
                // ancestor's scope=import dm, BOM version interpolated via the node's props);
                // then each distinct BOM's own effective managed versions (BOM + its parent
                // chain, interpolated via the BOM's own properties). Distinct BOMs are few,
                // so this is cheap even though millions of consumers inherit them.
                st.execute("CREATE TEMP TABLE node_bom AS SELECT DISTINCT a.node AS node, bom.id AS bom_id FROM anc a " +
                        "JOIN dependency_management dm ON dm.artifact_id = a.cur AND lower(coalesce(dm.scope, '')) = 'import' " +
                        "JOIN artifacts bom ON bom.gid = dm.dep_gid AND bom.aid = dm.dep_aid AND bom.classifier = '' " +
                        "AND bom.version = CASE WHEN " + reP + " <> '' THEN (SELECT ep.val FROM eff_prop ep WHERE ep.node = a.node AND ep.key = " + reP + ") ELSE dm.dep_version END");

                // Transitive BOM-of-BOM: grow node_bom by following import BOMs declared inside
                // the BOMs already reached (BOM version interpolated via that BOM's own effective
                // properties). The import graph is small, so iterate to a fixpoint (depth-capped).
                for (int lvl = 0; lvl < BOM_DEPTH; lvl++) {
                    for (String t : new String[]{"cur_boms", "bom_anc", "bom_prop", "bom_sub"}) {
                        st.execute("DROP TABLE IF EXISTS " + t);
                    }
                    st.execute("CREATE TEMP TABLE cur_boms AS SELECT DISTINCT bom_id FROM node_bom");
                    st.execute("CREATE TEMP TABLE bom_anc AS WITH RECURSIVE w(bom, cur, depth) AS (" +
                            "SELECT bom_id, bom_id, 0 FROM cur_boms " +
                            "UNION ALL SELECT w.bom, np.parent, w.depth + 1 FROM w JOIN np ON np.child = w.cur WHERE w.depth < 30) SELECT * FROM w");
                    st.execute("CREATE TEMP TABLE bom_prop AS SELECT bom AS node, prop_key AS key, arg_min(prop_value, depth) AS val " +
                            "FROM bom_anc a JOIN pom_properties pp ON pp.artifact_id = a.cur GROUP BY bom, prop_key");
                    for (int i = 0; i < 3; i++) {
                        st.executeUpdate("UPDATE bom_prop v SET val = (SELECT e2.val FROM bom_prop e2 WHERE e2.node = v.node AND e2.key = " + reV + ") " +
                                "WHERE " + reV + " <> '' AND EXISTS (SELECT 1 FROM bom_prop e2 WHERE e2.node = v.node AND e2.key = " + reV + ")");
                    }
                    st.execute("CREATE TEMP TABLE bom_sub AS SELECT DISTINCT ba.bom AS bom, sub.id AS sub_bom FROM bom_anc ba " +
                            "JOIN dependency_management dm ON dm.artifact_id = ba.cur AND lower(coalesce(dm.scope, '')) = 'import' " +
                            "JOIN artifacts sub ON sub.gid = dm.dep_gid AND sub.aid = dm.dep_aid AND sub.classifier = '' " +
                            "AND sub.version = CASE WHEN " + reP + " <> '' THEN (SELECT bp.val FROM bom_prop bp WHERE bp.node = ba.bom AND bp.key = " + reP + ") ELSE dm.dep_version END");
                    int added = st.executeUpdate("INSERT INTO node_bom SELECT DISTINCT nb.node, bs.sub_bom FROM node_bom nb " +
                            "JOIN bom_sub bs ON bs.bom = nb.bom_id " +
                            "WHERE bs.sub_bom <> nb.node AND NOT EXISTS (SELECT 1 FROM node_bom x WHERE x.node = nb.node AND x.bom_id = bs.sub_bom)");
                    if (added == 0) break;
                }
                for (String t : new String[]{"cur_boms", "bom_anc", "bom_prop", "bom_sub"}) {
                    st.execute("DROP TABLE IF EXISTS " + t);
                }

                st.execute("CREATE TEMP TABLE bom_ids AS SELECT DISTINCT bom_id FROM node_bom");
                st.execute("CREATE TEMP TABLE bom_anc AS WITH RECURSIVE w(bom, cur, depth) AS (" +
                        "SELECT bom_id, bom_id, 0 FROM bom_ids " +
                        "UNION ALL SELECT w.bom, np.parent, w.depth + 1 FROM w JOIN np ON np.child = w.cur WHERE w.depth < 30) SELECT * FROM w");
                st.execute("CREATE TEMP TABLE bom_prop AS SELECT bom AS node, prop_key AS key, arg_min(prop_value, depth) AS val " +
                        "FROM bom_anc a JOIN pom_properties pp ON pp.artifact_id = a.cur GROUP BY bom, prop_key");
                for (int i = 0; i < 3; i++) {
                    st.executeUpdate("UPDATE bom_prop v SET val = (SELECT e2.val FROM bom_prop e2 WHERE e2.node = v.node AND e2.key = " + reV + ") " +
                            "WHERE " + reV + " <> '' AND EXISTS (SELECT 1 FROM bom_prop e2 WHERE e2.node = v.node AND e2.key = " + reV + ")");
                }
                st.execute("CREATE TEMP TABLE bom_mgmt AS SELECT bom AS bom_id, dep_gid AS g, dep_aid AS a, " +
                        "coalesce(NULLIF(dep_type, ''), 'jar') AS t, coalesce(dep_classifier, '') AS cl, arg_min(dep_version, depth) AS version " +
                        "FROM bom_anc a JOIN dependency_management dm ON dm.artifact_id = a.cur AND lower(coalesce(dm.scope, '')) <> 'import' " +
                        "GROUP BY bom, dep_gid, dep_aid, coalesce(NULLIF(dep_type, ''), 'jar'), coalesce(dep_classifier, '')");
                st.executeUpdate("UPDATE bom_mgmt bm SET version = (SELECT bp.val FROM bom_prop bp WHERE bp.node = bm.bom_id AND bp.key = " + reBM + ") " +
                        "WHERE " + reBM + " <> '' AND EXISTS (SELECT 1 FROM bom_prop bp WHERE bp.node = bm.bom_id AND bp.key = " + reBM + ")");

                // Resolve each dep: literal; exact ${name} via effective properties; null via
                // effective (parent-chain) management, falling back to import-BOM management.
                st.execute("CREATE TEMP TABLE resolved_dep AS SELECT dd.artifact_id AS parent_id, dd.dep_gid AS g, dd.dep_aid AS a, " +
                        "COALESCE(NULLIF(dd.scope, ''), 'compile') AS scope, CASE " +
                        "WHEN dd.dep_version IS NULL OR dd.dep_version = '' THEN COALESCE(" +
                        "(SELECT em.version FROM eff_mgmt em WHERE em.node = dd.artifact_id AND em.g = dd.dep_gid AND em.a = dd.dep_aid " +
                        "AND em.t = coalesce(NULLIF(dd.dep_type, ''), 'jar') AND em.cl = coalesce(dd.dep_classifier, '')), " +
                        "(SELECT bm.version FROM node_bom nb JOIN bom_mgmt bm ON bm.bom_id = nb.bom_id AND bm.g = dd.dep_gid AND bm.a = dd.dep_aid " +
                        "AND bm.t = coalesce(NULLIF(dd.dep_type, ''), 'jar') AND bm.cl = coalesce(dd.dep_classifier, '') " +
                        "WHERE nb.node = dd.artifact_id ORDER BY bm.version LIMIT 1)) " +
                        "WHEN " + reD + " <> '' THEN (SELECT ep.val FROM eff_prop ep WHERE ep.node = dd.artifact_id AND ep.key = " + reD + ") " +
                        "ELSE dd.dep_version END AS ver " +
                        "FROM direct_dep dd JOIN ibatch b ON b.artifact_id = dd.artifact_id");
                st.execute("CREATE TEMP TABLE good_dep AS SELECT DISTINCT parent_id, g, a, ver, scope FROM resolved_dep " +
                        "WHERE ver IS NOT NULL AND ver NOT LIKE '%${%'");
                st.executeUpdate("INSERT INTO artifacts (id, gid, aid, version, classifier) " +
                        "SELECT nextval('seq_artifact_id'), x.g, x.a, x.ver, '' FROM (SELECT DISTINCT g, a, ver FROM good_dep) x " +
                        "LEFT JOIN artifacts ar ON ar.gid = x.g AND ar.aid = x.a AND ar.version = x.ver AND ar.classifier = '' WHERE ar.id IS NULL");
                edges += st.executeUpdate("INSERT OR IGNORE INTO dependencies (parent_id, child_id, scope) " +
                        "SELECT DISTINCT gd.parent_id, ar.id, gd.scope FROM good_dep gd " +
                        "JOIN artifacts ar ON ar.gid = gd.g AND ar.aid = gd.a AND ar.version = gd.ver AND ar.classifier = '' AND ar.id <> gd.parent_id");
                marked += st.executeUpdate("UPDATE pom_meta SET deps_resolved = TRUE WHERE deps_resolved = FALSE " +
                        "AND artifact_id IN (SELECT artifact_id FROM ibatch) " +
                        "AND artifact_id NOT IN (SELECT rd.parent_id FROM resolved_dep rd WHERE rd.ver IS NULL OR rd.ver LIKE '%${%')");
                log.info("  inherited batch up to id {}: {} POM(s) resolved so far, {} edge(s)", cursor, marked, edges);
            }
            for (String t : new String[]{"np", "ibatch", "anc", "eff_prop", "eff_mgmt", "node_bom", "cur_boms", "bom_sub", "bom_ids", "bom_anc", "bom_prop", "bom_mgmt", "resolved_dep", "good_dep"}) {
                st.execute("DROP TABLE IF EXISTS " + t);
            }
        } finally {
            conn.setAutoCommit(prev);
        }
        log.info("Set-based inherited pass (parent-chain + import-BOM): {} POM(s) resolved, {} edge(s)", marked, edges);
        return new long[]{edges, marked};
    }

    /**
     * Point DuckDB at an explicit spill directory (next to the database) and, when a cap
     * was requested, bound its memory so large intermediates spill to disk rather than
     * exhausting RAM alongside the JVM. Both settings are global/DB-wide. Best-effort:
     * failures (e.g. an older DuckDB rejecting a key) are logged and the resolve proceeds
     * on defaults.
     */
    private void applyMemoryGuards(Connection conn) {
        String tempDir = (dbPath + ".tmp").replace("'", "''");
        try (Statement st = conn.createStatement()) {
            st.execute("SET temp_directory = '" + tempDir + "'");
            // Stream large INSERT...SELECT results instead of buffering the whole thing to
            // preserve row order. The set-based passes do multi-hundred-million-row DISTINCT
            // inserts into `artifacts`/`dependencies`; with insertion order preserved DuckDB
            // pins the entire result set (the "failed to pin block" OOM), and row order is
            // irrelevant here anyway. DB-wide, so it also covers the per-node writer.
            try { st.execute("SET preserve_insertion_order = false"); } catch (SQLException ignore) { /* older DuckDB */ }
            if (dbThreads > 0) {
                st.execute("SET threads = " + dbThreads);
            }
            if (memLimit != null && !memLimit.isBlank()) {
                st.execute("SET memory_limit = '" + memLimit.trim().replace("'", "''") + "'");
            }
            log.info("Resolve DB guards: memory_limit={}, threads={}, preserve_insertion_order=false, temp_directory={}.tmp",
                    (memLimit != null && !memLimit.isBlank()) ? memLimit.trim() : "<default ~80% RAM>",
                    dbThreads > 0 ? dbThreads : "<default: one per core>", dbPath);
        } catch (SQLException e) {
            log.warn("Could not apply DuckDB memory guards (continuing on defaults): {}", e.getMessage());
        }
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
     * Read-only resolver bound to one (duplicated) connection. Single-threaded use:
     * one {@code Reader} per worker thread. Caches parent/BOM nodes and existing
     * artifact ids in bounded LRUs so common parents are loaded once per worker.
     */
    private static final class Reader implements AutoCloseable {
        private final Connection conn;
        private final PreparedStatement selMeta, selProps, selDm, selDirect, selArt;
        private final Map<String, NodeData> parentByCoord = lru(PARENT_CACHE);
        private final Map<String, Integer> idByCoord = lru(READER_ID_CACHE);

        Reader(Connection conn) throws SQLException {
            this.conn = conn;
            conn.setAutoCommit(true);
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
            // writer never batches duplicate (parent, child, scope) tuples (DuckDB rejects
            // an ON CONFLICT command carrying duplicate conflict keys).
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
                    if (childId != id && seen.add("r " + childId + " " + scope)) {
                        resolved.add(new ResolvedEdge(childId, scope));
                    }
                } else if (seen.add("p " + g + " " + a + " " + v + " " + scope)) {
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

        private void collectDm(NodeData n, Map<String, String> props,
                               Map<String, String> out, Set<Integer> visited) throws SQLException {
            if (n == null || !visited.add(n.id())) return;
            for (Managed m : n.dm()) {
                if (!"import".equalsIgnoreCase(m.scope())) {
                    out.putIfAbsent(key(m.gid(), m.aid(), m.type(), m.classifier()), m.version());
                }
            }
            for (Managed m : n.dm()) {
                if ("import".equalsIgnoreCase(m.scope())) {
                    String bv = interpolate(m.version(), props);
                    NodeData bom = nodeByCoord(m.gid(), m.aid(), bv);
                    collectDm(bom, props, out, visited);
                }
            }
            collectDm(nodeByCoord(n.parentGid(), n.parentAid(), n.parentVersion()), props, out, visited);
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
     * database (DuckDB single-writer). Edge inserts and resolved-marks are batched and
     * flushed periodically; synthetic-artifact inserts are immediate (they need the new
     * id) and de-duplicated via an LRU id cache backed by the unique constraint.
     */
    private static final class Writer implements AutoCloseable {
        private final Connection conn;
        private final PreparedStatement selArt, insArt, insEdge, markRes;
        private final Map<String, Integer> idByCoord = lru(WRITER_ID_CACHE);
        private int sinceFlush = 0;

        Writer(Connection conn) throws SQLException {
            this.conn = conn;
            selArt = conn.prepareStatement(
                    "SELECT id FROM artifacts WHERE gid = ? AND aid = ? AND version = ? AND classifier = ''");
            insArt = conn.prepareStatement(
                    "INSERT INTO artifacts (id, gid, aid, version, classifier) VALUES (nextval('seq_artifact_id'), ?, ?, ?, '') RETURNING id");
            insEdge = conn.prepareStatement(
                    "INSERT OR IGNORE INTO dependencies (parent_id, child_id, scope) VALUES (?, ?, ?)");
            markRes = conn.prepareStatement("UPDATE pom_meta SET deps_resolved = TRUE WHERE artifact_id = ?");
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
            try (ResultSet rs = insArt.executeQuery()) {
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
            for (PreparedStatement ps : new PreparedStatement[]{selArt, insArt, insEdge, markRes}) {
                try { if (ps != null) ps.close(); } catch (SQLException ignore) { /* ignore */ }
            }
        }
    }
}
