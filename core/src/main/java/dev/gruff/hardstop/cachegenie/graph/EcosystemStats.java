package dev.gruff.hardstop.cachegenie.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Properties;

/**
 * Read-only ecosystem analytics over the CacheGenie DuckDB database
 * ({@code ~/.m2/cachegenie/graph.db}). Where {@link GraphRepository} /
 * {@link MetaRepository} populate the database and {@code graph stats} prints a
 * quick snapshot, this class answers the "how does software evolve and get
 * abandoned" questions: arrival rate over time, versions per artifact, lifespan,
 * update frequency, abandonment, and how often a POM bumps the version of a
 * dependency it already declares.
 *
 * <p>Each public method returns one or more {@link Report}s (a titled grid). The
 * command layer ({@code InsightsCmd}) formats them as a table, CSV, or JSON; the
 * SQL lives here so it can be unit-tested directly against a synthetic database.
 *
 * <p><b>Read-only by default.</b> Like {@code graph stats} and {@code graph
 * query}, the production path opens the file read-only so analysis never takes a
 * write lock (and so it can run while the DB is idle). It therefore cannot run
 * schema migrations: queries tolerate missing tables/columns by surfacing a note
 * rather than failing the whole run, so a partially-populated DB (e.g. only
 * {@code index-sync} has run, no mining yet) still produces what it can.
 *
 * <p><b>Timestamps.</b> {@code meta_versions.published} is an ISO-8601 string;
 * every time-based query normalises it with
 * {@code TRY_CAST(replace(published,'Z','') AS TIMESTAMP)}, so rows that fail to
 * parse or were discovered without a publish date become NULL and drop out of
 * time-based metrics. The {@code arrivals} report exposes how big that hole is.
 */
public final class EcosystemStats {
    private static final Logger log = LoggerFactory.getLogger(EcosystemStats.class);

    private final String dbPath;
    private final boolean readOnly;

    public EcosystemStats(File cacheGenieRoot) {
        this(cacheGenieRoot, true);
    }

    /** @param readOnly open the database read-only (production) or read-write (tests). */
    public EcosystemStats(File cacheGenieRoot, boolean readOnly) {
        this.dbPath = new File(cacheGenieRoot, "graph.db").getAbsolutePath();
        this.readOnly = readOnly;
    }

    public boolean dbExists() {
        return new File(dbPath).exists();
    }

    public String dbPath() {
        return dbPath;
    }

    private Connection open() throws SQLException {
        Properties props = new Properties();
        if (readOnly) props.setProperty("duckdb.read_only", "true");
        return DriverManager.getConnection("jdbc:duckdb:" + dbPath, props);
    }

    // ------------------------------------------------------------------ types

    /** A titled result grid: column names plus row values (NULLs preserved). */
    public record Report(String title, List<String> columns, List<List<Object>> rows) {
    }

    /**
     * Optional analysis scope. {@code gid}/{@code aid} restrict to a group (and
     * its subgroups) or an exact group:artifact; {@code since}/{@code until}
     * restrict to versions published within a year range. All fields nullable.
     */
    public static final class Scope {
        public final String gid;
        public final String aid;
        public final Integer since;
        public final Integer until;

        public Scope(String gid, String aid, Integer since, Integer until) {
            this.gid = (gid == null || gid.isBlank()) ? null : gid.trim();
            this.aid = (aid == null || aid.isBlank()) ? null : aid.trim();
            this.since = since;
            this.until = until;
        }

        public static Scope all() {
            return new Scope(null, null, null, null);
        }

        /** Parse a {@code group} or {@code group:artifact} selector. */
        public static Scope of(String gav, Integer since, Integer until) {
            if (gav == null || gav.isBlank()) return new Scope(null, null, since, until);
            int i = gav.indexOf(':');
            if (i >= 0) return new Scope(gav.substring(0, i), gav.substring(i + 1), since, until);
            return new Scope(gav, null, since, until);
        }

        /** SQL predicate on the given group/artifact columns (incl. subgroup match), or "". */
        String gavFilter(String gidCol, String aidCol) {
            if (gid == null) return "";
            String g = esc(gid);
            if (aid != null) {
                return " AND " + gidCol + " = '" + g + "' AND " + aidCol + " = '" + esc(aid) + "'";
            }
            // group exactly, or any subgroup (org.foo and org.foo.*)
            return " AND (" + gidCol + " = '" + g + "' OR " + gidCol + " LIKE '" + g + ".%')";
        }

        /** SQL predicate on a publish-timestamp expression for the year window, or "". */
        String yearFilter(String pubExpr) {
            StringBuilder sb = new StringBuilder();
            if (since != null) sb.append(" AND year(").append(pubExpr).append(") >= ").append((int) since);
            if (until != null) sb.append(" AND year(").append(pubExpr).append(") <= ").append((int) until);
            return sb.toString();
        }

        private static String esc(String s) {
            return s.replace("'", "''");
        }
    }

    // -------------------------------------------------------------- shared SQL

    /**
     * {@code WITH mv (one row per dated version) , art (per-artifact rollup)}.
     * The gav scope is applied to {@code mv}; the year window is applied to
     * {@code art} (and re-applied by callers that read {@code mv} directly).
     */
    private static String mvArtCte(Scope s) {
        return "WITH mv AS (" +
                "SELECT ma.gid, ma.aid, v.version, " +
                "TRY_CAST(replace(v.published, 'Z', '') AS TIMESTAMP) AS pub " +
                "FROM meta_versions v JOIN meta_artifacts ma ON ma.id = v.ga_id " +
                "WHERE 1=1" + s.gavFilter("ma.gid", "ma.aid") + "), " +
                "art AS (" +
                "SELECT gid, aid, COUNT(*) AS versions, MIN(pub) AS first_pub, MAX(pub) AS last_pub, " +
                "date_diff('day', MIN(pub), MAX(pub)) AS span_days " +
                "FROM mv WHERE pub IS NOT NULL" + s.yearFilter("pub") + " GROUP BY gid, aid) ";
    }

    // ----------------------------------------------------------------- reports

    /** Catalogue coverage + arrival rate (versions/artifacts/groups over time). */
    public List<Report> arrivals(Scope s) throws SQLException {
        LinkedHashMap<String, String> q = new LinkedHashMap<>();

        q.put("Catalogue coverage (publish-date completeness)",
                mvArtCte(s) +
                        "SELECT COUNT(*) AS total_versions, COUNT(pub) AS with_timestamp, " +
                        "COUNT(*) - COUNT(pub) AS missing_timestamp, " +
                        "round(100.0 * COUNT(pub) / nullif(COUNT(*), 0), 2) AS pct_dated, " +
                        "MIN(pub) AS earliest_release, MAX(pub) AS latest_release FROM mv");

        q.put("Versions published per year",
                mvArtCte(s) +
                        "SELECT year(pub) AS yr, COUNT(*) AS versions_published " +
                        "FROM mv WHERE pub IS NOT NULL" + s.yearFilter("pub") +
                        " GROUP BY yr ORDER BY yr");

        q.put("New artifacts per year (first appearance)",
                mvArtCte(s) +
                        "SELECT year(first_pub) AS yr, COUNT(*) AS new_artifacts " +
                        "FROM art GROUP BY yr ORDER BY yr");

        q.put("New groups per year (first appearance)",
                mvArtCte(s) +
                        "SELECT year(g_first) AS yr, COUNT(*) AS new_groups FROM (" +
                        "SELECT gid, MIN(first_pub) AS g_first FROM art GROUP BY gid) GROUP BY yr ORDER BY yr");

        return runAll(q);
    }

    /** Versions per artifact, single-release share, lifespan, update frequency. */
    public List<Report> lifecycle(Scope s) throws SQLException {
        LinkedHashMap<String, String> q = new LinkedHashMap<>();

        q.put("Versions per artifact",
                mvArtCte(s) +
                        "SELECT COUNT(*) AS artifacts, round(AVG(versions), 2) AS mean_versions, " +
                        "median(versions) AS median_versions, quantile_cont(versions, 0.90) AS p90_versions, " +
                        "quantile_cont(versions, 0.99) AS p99_versions, MAX(versions) AS max_versions FROM art");

        q.put("Release-count distribution",
                mvArtCte(s) +
                        "SELECT CASE WHEN versions = 1 THEN '1' " +
                        "WHEN versions BETWEEN 2 AND 5 THEN '2-5' " +
                        "WHEN versions BETWEEN 6 AND 10 THEN '6-10' " +
                        "WHEN versions BETWEEN 11 AND 25 THEN '11-25' " +
                        "WHEN versions BETWEEN 26 AND 50 THEN '26-50' ELSE '50+' END AS release_bucket, " +
                        "COUNT(*) AS artifacts, round(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 2) AS pct " +
                        "FROM art GROUP BY release_bucket ORDER BY MIN(versions)");

        q.put("Single-release artifacts (\"one and done\")",
                mvArtCte(s) +
                        "SELECT COUNT(*) FILTER (WHERE versions = 1) AS single_release, COUNT(*) AS total, " +
                        "round(100.0 * COUNT(*) FILTER (WHERE versions = 1) / nullif(COUNT(*), 0), 2) AS pct_single FROM art");

        q.put("Lifespan first->last (artifacts with >1 release)",
                mvArtCte(s) +
                        "SELECT COUNT(*) AS artifacts, round(AVG(span_days), 1) AS mean_span_days, " +
                        "median(span_days) AS median_span_days, round(AVG(span_days) / 365.25, 2) AS mean_span_years, " +
                        "round(median(span_days) / 365.25, 2) AS median_span_years FROM art WHERE versions > 1");

        q.put("Lifespan distribution",
                mvArtCte(s) +
                        "SELECT CASE WHEN span_days = 0 THEN 'single release' " +
                        "WHEN span_days < 30 THEN '< 1 month' " +
                        "WHEN span_days < 365 THEN '< 1 year' " +
                        "WHEN span_days < 730 THEN '1-2 years' " +
                        "WHEN span_days < 1825 THEN '2-5 years' ELSE '5+ years' END AS lifespan_bucket, " +
                        "COUNT(*) AS artifacts, round(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 2) AS pct " +
                        "FROM art GROUP BY lifespan_bucket ORDER BY MIN(span_days)");

        q.put("Update frequency (releases/year over active life)",
                mvArtCte(s) +
                        "SELECT round(AVG(rpy), 3) AS mean_releases_per_year, median(rpy) AS median_releases_per_year, " +
                        "quantile_cont(rpy, 0.90) AS p90_releases_per_year FROM (" +
                        "SELECT versions / (span_days / 365.25) AS rpy FROM art WHERE versions > 1 AND span_days > 0)");

        q.put("Gap between consecutive releases (days)",
                mvArtCte(s) +
                        "SELECT COUNT(*) AS release_intervals, round(AVG(gap_days), 1) AS mean_gap_days, " +
                        "median(gap_days) AS median_gap_days, quantile_cont(gap_days, 0.90) AS p90_gap_days FROM (" +
                        "SELECT date_diff('day', lag(pub) OVER (PARTITION BY gid, aid ORDER BY pub), pub) AS gap_days " +
                        "FROM mv WHERE pub IS NOT NULL" + s.yearFilter("pub") + ") WHERE gap_days IS NOT NULL");

        return runAll(q);
    }

    /** Abandonment: how many artifacts have gone quiet, and a crude survival curve. */
    public List<Report> abandonment(Scope s) throws SQLException {
        LinkedHashMap<String, String> q = new LinkedHashMap<>();

        q.put("Abandonment summary (relative to now)",
                mvArtCte(s) +
                        "SELECT COUNT(*) AS artifacts, " +
                        "COUNT(*) FILTER (WHERE last_pub < CURRENT_TIMESTAMP - INTERVAL 2 YEAR) AS quiet_2y, " +
                        "round(100.0 * COUNT(*) FILTER (WHERE last_pub < CURRENT_TIMESTAMP - INTERVAL 2 YEAR) / nullif(COUNT(*), 0), 2) AS pct_quiet_2y, " +
                        "COUNT(*) FILTER (WHERE last_pub < CURRENT_TIMESTAMP - INTERVAL 5 YEAR) AS quiet_5y, " +
                        "round(100.0 * COUNT(*) FILTER (WHERE last_pub < CURRENT_TIMESTAMP - INTERVAL 5 YEAR) / nullif(COUNT(*), 0), 2) AS pct_quiet_5y FROM art");

        q.put("Last-release age distribution",
                mvArtCte(s) +
                        "SELECT CASE WHEN date_diff('day', last_pub, CURRENT_TIMESTAMP) < 365 THEN 'active (<1y)' " +
                        "WHEN date_diff('day', last_pub, CURRENT_TIMESTAMP) < 730 THEN '1-2y' " +
                        "WHEN date_diff('day', last_pub, CURRENT_TIMESTAMP) < 1825 THEN '2-5y' " +
                        "WHEN date_diff('day', last_pub, CURRENT_TIMESTAMP) < 3650 THEN '5-10y' ELSE '10y+' END AS last_release_age, " +
                        "COUNT(*) AS artifacts, round(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 2) AS pct " +
                        "FROM art GROUP BY last_release_age ORDER BY MIN(date_diff('day', last_pub, CURRENT_TIMESTAMP))");

        q.put("Single-release vs recency",
                mvArtCte(s) +
                        "SELECT versions = 1 AS single_release, COUNT(*) AS artifacts, " +
                        "round(AVG(date_diff('day', last_pub, CURRENT_TIMESTAMP) / 365.25), 2) AS mean_years_since_last " +
                        "FROM art GROUP BY single_release ORDER BY single_release");

        return runAll(q);
    }

    /**
     * Resolution coverage: how much of the catalogue has been mined and then
     * resolved into concrete dependency edges. Reports the catalogued -> mined ->
     * resolved funnel, the un-mined backlog, and POMs that resolve ran on but
     * which declared dependencies that did not project to any edge (the known
     * {@link PomResolver} gaps: version ranges, profiles, unresolved
     * {@code ${...}} properties). Note: the {@code deps_resolved} flag means
     * resolve <em>processed</em> a POM, not that every declared dependency
     * produced an edge. The {@code --since}/{@code --until} year filters do not
     * apply here (these tables carry no publish date); only the gav scope does.
     */
    public List<Report> resolution(Scope s) throws SQLException {
        LinkedHashMap<String, String> q = new LinkedHashMap<>();
        String gavMa = s.gavFilter("ma.gid", "ma.aid");
        String gavA = s.gavFilter("a.gid", "a.aid");

        q.put("Resolution funnel (catalogued -> mined -> resolved)",
                "WITH f AS (SELECT " +
                        "(SELECT COUNT(*) FROM meta_versions v JOIN meta_artifacts ma ON ma.id = v.ga_id " +
                        "WHERE NOT coalesce(v.missing_pom, false)" + gavMa + ") AS catalogued_with_pom, " +
                        "(SELECT COUNT(*) FROM pom_meta pm JOIN artifacts a ON a.id = pm.artifact_id WHERE 1=1" + gavA + ") AS mined, " +
                        "(SELECT COUNT(*) FROM pom_meta pm JOIN artifacts a ON a.id = pm.artifact_id WHERE coalesce(pm.deps_resolved, false)" + gavA + ") AS resolved, " +
                        "(SELECT COUNT(*) FROM pom_meta pm JOIN artifacts a ON a.id = pm.artifact_id WHERE NOT coalesce(pm.deps_resolved, false)" + gavA + ") AS mined_unresolved) " +
                        "SELECT catalogued_with_pom, mined, resolved, mined_unresolved, " +
                        "round(100.0 * resolved / nullif(mined, 0), 2) AS pct_resolved_of_mined, " +
                        "round(100.0 * mined / nullif(catalogued_with_pom, 0), 2) AS pct_mined_of_catalogued FROM f");

        q.put("Catalogued but not yet mined (run 'graph mine')",
                "SELECT COUNT(*) AS catalogued_unmined " +
                        "FROM meta_versions v JOIN meta_artifacts ma ON ma.id = v.ga_id " +
                        "LEFT JOIN artifacts a ON a.gid = ma.gid AND a.aid = ma.aid AND a.version = v.version AND coalesce(a.classifier, '') = '' " +
                        "LEFT JOIN pom_meta pm ON pm.artifact_id = a.id " +
                        "WHERE NOT coalesce(v.missing_pom, false)" + gavMa + " AND pm.artifact_id IS NULL");

        q.put("Resolved but no edges (unresolvable declared deps: ranges/properties/profiles)",
                "SELECT COUNT(*) AS resolved_but_no_edges " +
                        "FROM pom_meta pm JOIN artifacts a ON a.id = pm.artifact_id " +
                        "WHERE coalesce(pm.deps_resolved, false)" + gavA + " " +
                        "AND EXISTS (SELECT 1 FROM direct_dep dd WHERE dd.artifact_id = pm.artifact_id) " +
                        "AND NOT EXISTS (SELECT 1 FROM dependencies d WHERE d.parent_id = pm.artifact_id)");

        q.put("Sample unresolved mined POMs",
                "SELECT a.gid, a.aid, a.version FROM pom_meta pm JOIN artifacts a ON a.id = pm.artifact_id " +
                        "WHERE NOT coalesce(pm.deps_resolved, false)" + gavA + " ORDER BY a.gid, a.aid, a.version LIMIT 50");

        return runAll(q);
    }

    /**
     * Dependency version churn: across consecutive versions of the same artifact,
     * for each dependency present in BOTH, how often did its version change? New
     * and removed dependencies are excluded by construction (the LAG is NULL), so
     * this isolates version bumps of retained dependencies.
     *
     * @param raw  use raw declared {@code direct_dep} versions (needs only
     *             {@code graph mine}; literal-only, excludes {@code ${...}}/managed)
     *             instead of resolved {@code dependencies} edges (needs
     *             {@code graph resolve}; reflects effective versions).
     * @param topN if > 0, also include the top-N most-frequently-bumped dependencies.
     */
    public List<Report> churn(Scope s, boolean raw, int topN) throws SQLException {
        LinkedHashMap<String, String> q = new LinkedHashMap<>();
        String mode = raw ? "raw declared (direct_dep)" : "resolved (dependencies)";

        q.put("Dependency version churn — summary [" + mode + "]",
                churnSeqCte(s, raw) +
                        "SELECT COUNT(*) FILTER (WHERE prev_ver IS NOT NULL) AS retained_dep_transitions, " +
                        "COUNT(*) FILTER (WHERE prev_ver IS NOT NULL AND c_ver <> prev_ver) AS version_bumped, " +
                        "round(100.0 * COUNT(*) FILTER (WHERE prev_ver IS NOT NULL AND c_ver <> prev_ver) " +
                        "/ nullif(COUNT(*) FILTER (WHERE prev_ver IS NOT NULL), 0), 2) AS pct_bumped FROM seq");

        q.put("Dependency version churn — per release [" + mode + "]",
                churnSeqCte(s, raw) +
                        ", per_release AS (SELECT p_gid, p_aid, p_ver, " +
                        "COUNT(*) FILTER (WHERE prev_ver IS NOT NULL) AS retained, " +
                        "COUNT(*) FILTER (WHERE prev_ver IS NOT NULL AND c_ver <> prev_ver) AS bumped " +
                        "FROM seq GROUP BY p_gid, p_aid, p_ver) " +
                        "SELECT COUNT(*) FILTER (WHERE retained > 0) AS releases_with_retained_deps, " +
                        "COUNT(*) FILTER (WHERE bumped > 0) AS releases_that_bumped_something, " +
                        "round(100.0 * COUNT(*) FILTER (WHERE bumped > 0) / nullif(COUNT(*) FILTER (WHERE retained > 0), 0), 2) AS pct_releases_bumping_a_dep, " +
                        "round(AVG(CASE WHEN retained > 0 THEN 100.0 * bumped / retained END), 2) AS avg_pct_deps_bumped_per_release " +
                        "FROM per_release");

        if (topN > 0) {
            q.put("Most-frequently-bumped dependencies (top " + topN + ") [" + mode + "]",
                    churnSeqCte(s, raw) +
                            "SELECT c_gid, c_aid, " +
                            "COUNT(*) FILTER (WHERE prev_ver IS NOT NULL AND c_ver <> prev_ver) AS times_bumped, " +
                            "COUNT(*) FILTER (WHERE prev_ver IS NOT NULL) AS retained_transitions, " +
                            "round(100.0 * COUNT(*) FILTER (WHERE prev_ver IS NOT NULL AND c_ver <> prev_ver) " +
                            "/ nullif(COUNT(*) FILTER (WHERE prev_ver IS NOT NULL), 0), 2) AS pct_bumped " +
                            "FROM seq GROUP BY c_gid, c_aid ORDER BY times_bumped DESC, retained_transitions DESC LIMIT " + topN);
        }

        return runAll(q);
    }

    /**
     * The {@code WITH ... seq} prefix shared by the churn queries: parent edges
     * dated by the parent version's publish timestamp, with the previous resolved
     * version of the same (parent-line, child-coordinate) via LAG. Ends with the
     * {@code seq} CTE defined and ready for a trailing SELECT (or extra CTE).
     */
    private static String churnSeqCte(Scope s, boolean raw) {
        String mvp = "mvp AS (SELECT ma.gid, ma.aid, v.version, " +
                "TRY_CAST(replace(v.published, 'Z', '') AS TIMESTAMP) AS pub " +
                "FROM meta_versions v JOIN meta_artifacts ma ON ma.id = v.ga_id " +
                "WHERE 1=1" + s.gavFilter("ma.gid", "ma.aid") + ")";

        String edges = raw
                ? "edges AS (SELECT a.gid AS p_gid, a.aid AS p_aid, a.version AS p_ver, " +
                "dd.dep_gid AS c_gid, dd.dep_aid AS c_aid, dd.dep_version AS c_ver " +
                "FROM direct_dep dd JOIN artifacts a ON a.id = dd.artifact_id AND coalesce(a.classifier, '') = '' " +
                "WHERE dd.dep_version IS NOT NULL AND dd.dep_version NOT LIKE '%${%')"
                : "edges AS (SELECT pa.gid AS p_gid, pa.aid AS p_aid, pa.version AS p_ver, " +
                "ca.gid AS c_gid, ca.aid AS c_aid, ca.version AS c_ver " +
                "FROM dependencies d " +
                "JOIN artifacts pa ON pa.id = d.parent_id AND coalesce(pa.classifier, '') = '' " +
                "JOIN artifacts ca ON ca.id = d.child_id)";

        String dated = "dated AS (SELECT e.*, m.pub FROM edges e " +
                "JOIN mvp m ON m.gid = e.p_gid AND m.aid = e.p_aid AND m.version = e.p_ver " +
                "WHERE m.pub IS NOT NULL" + s.yearFilter("m.pub") + ")";

        String seq = "seq AS (SELECT p_gid, p_aid, p_ver, pub, c_gid, c_aid, c_ver, " +
                "lag(c_ver) OVER (PARTITION BY p_gid, p_aid, c_gid, c_aid ORDER BY pub) AS prev_ver FROM dated)";

        return "WITH " + mvp + ", " + edges + ", " + dated + ", " + seq + " ";
    }

    // -------------------------------------------------------------- execution

    /** Run each titled query; a query that fails (e.g. table absent) becomes a note row. */
    private List<Report> runAll(LinkedHashMap<String, String> titledSql) throws SQLException {
        List<Report> out = new ArrayList<>();
        try (Connection conn = open()) {
            for (var e : titledSql.entrySet()) {
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(e.getValue())) {
                    out.add(pack(e.getKey(), rs));
                } catch (SQLException ex) {
                    log.debug("report '{}' failed: {}", e.getKey(), ex.getMessage());
                    out.add(new Report(e.getKey(), List.of("note"),
                            List.of(List.of("unavailable: " + rootMessage(ex)))));
                }
            }
        }
        return out;
    }

    private static Report pack(String title, ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        List<String> cols = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) cols.add(md.getColumnLabel(i));
        List<List<Object>> rows = new ArrayList<>();
        while (rs.next()) {
            List<Object> row = new ArrayList<>(n);
            for (int i = 1; i <= n; i++) row.add(rs.getObject(i));
            rows.add(row);
        }
        return new Report(title, cols, rows);
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : m.split("\n", 2)[0];
    }
}
