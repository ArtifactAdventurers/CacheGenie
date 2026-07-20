package dev.gruff.hardstop.cachegenie.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Read-only ecosystem analytics over the CacheGenie SQLite database
 * ({@code ~/.m2/cachegenie/graph.sqlite}). Where {@link GraphRepository} /
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
 * query}, the production path opens the file read-only (via
 * {@link Sqlite#openReadOnly}) so analysis never takes a write lock. It therefore
 * cannot run schema migrations: queries tolerate missing tables/columns by
 * surfacing a note rather than failing the whole run, so a partially-populated DB
 * (e.g. only {@code index-sync} has run, no mining yet) still produces what it can.
 *
 * <p><b>Timestamps.</b> {@code meta_versions.published} is an ISO-8601 string
 * with a trailing {@code Z}; every time-based query normalises it to
 * {@code 'YYYY-MM-DD HH:MM:SS[.SSS]'} text ({@code Z} stripped, {@code T}
 * replaced by a space) guarded by a {@code julianday(...) IS NOT NULL} parse
 * test, so rows that fail to parse or were discovered without a publish date
 * become NULL and drop out of time-based metrics. In that shape the text sorts
 * chronologically and compares lexicographically against
 * {@code datetime('now', ...)} output. The {@code arrivals} report exposes how
 * big the undated hole is.
 *
 * <p><b>Percentiles.</b> SQLite has no {@code median}/{@code quantile_cont};
 * {@link #quantiles} computes them in Java with the same linear-interpolation
 * semantics (one COUNT plus one ordered scan per report — constant memory).
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
        this.dbPath = Sqlite.dbPath(cacheGenieRoot);
        this.readOnly = readOnly;
    }

    public boolean dbExists() {
        return new File(dbPath).exists();
    }

    public String dbPath() {
        return dbPath;
    }

    private Connection open() throws SQLException {
        return readOnly ? Sqlite.openReadOnly(dbPath) : Sqlite.open(dbPath);
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
            if (since != null) sb.append(" AND CAST(strftime('%Y', ").append(pubExpr)
                    .append(") AS INTEGER) >= ").append((int) since);
            if (until != null) sb.append(" AND CAST(strftime('%Y', ").append(pubExpr)
                    .append(") AS INTEGER) <= ").append((int) until);
            return sb.toString();
        }

        private static String esc(String s) {
            return s.replace("'", "''");
        }
    }

    // -------------------------------------------------------------- shared SQL

    /**
     * Normalised publish timestamp: NULL unless the ISO-8601 string parses
     * ({@code julianday} test), else {@code 'YYYY-MM-DD HH:MM:SS[.SSS]'} text —
     * sortable and lexicographically comparable with {@code datetime('now',...)}.
     */
    private static String pubExpr(String col) {
        return "CASE WHEN julianday(replace(" + col + ", 'Z', '')) IS NOT NULL " +
                "THEN replace(replace(" + col + ", 'Z', ''), 'T', ' ') END";
    }

    /** {@code year(x)} equivalent over the normalised publish text. */
    private static String yearExpr(String pub) {
        return "CAST(strftime('%Y', " + pub + ") AS INTEGER)";
    }

    /**
     * Whole days from {@code from} to {@code to}, counted as day boundaries
     * crossed (both sides truncated to their date) — the same semantics as
     * DuckDB's {@code date_diff('day', from, to)}, so times of day don't shift
     * the bucketing.
     */
    private static String dayDiff(String from, String to) {
        return "CAST(julianday(date(" + to + ")) - julianday(date(" + from + ")) AS INTEGER)";
    }

    /**
     * {@code WITH mv (one row per dated version) , art (per-artifact rollup)}.
     * The gav scope is applied to {@code mv}; the year window is applied to
     * {@code art} (and re-applied by callers that read {@code mv} directly).
     */
    private static String mvArtCte(Scope s) {
        return "WITH mv AS (" +
                "SELECT ma.gid, ma.aid, v.version, " +
                pubExpr("v.published") + " AS pub " +
                "FROM meta_versions v JOIN meta_artifacts ma ON ma.id = v.ga_id " +
                "WHERE 1=1" + s.gavFilter("ma.gid", "ma.aid") + "), " +
                "art AS (" +
                "SELECT gid, aid, COUNT(*) AS versions, MIN(pub) AS first_pub, MAX(pub) AS last_pub, " +
                dayDiff("MIN(pub)", "MAX(pub)") + " AS span_days " +
                "FROM mv WHERE pub IS NOT NULL" + s.yearFilter("pub") + " GROUP BY gid, aid) ";
    }

    // ----------------------------------------------------------------- reports

    /** Catalogue coverage + arrival rate (versions/artifacts/groups over time). */
    public List<Report> arrivals(Scope s) throws SQLException {
        LinkedHashMap<String, ReportBody> q = new LinkedHashMap<>();

        q.put("Catalogue coverage (publish-date completeness)", sql(
                mvArtCte(s) +
                        "SELECT COUNT(*) AS total_versions, COUNT(pub) AS with_timestamp, " +
                        "COUNT(*) - COUNT(pub) AS missing_timestamp, " +
                        "round(100.0 * COUNT(pub) / nullif(COUNT(*), 0), 2) AS pct_dated, " +
                        "MIN(pub) AS earliest_release, MAX(pub) AS latest_release FROM mv"));

        q.put("Versions published per year", sql(
                mvArtCte(s) +
                        "SELECT " + yearExpr("pub") + " AS yr, COUNT(*) AS versions_published " +
                        "FROM mv WHERE pub IS NOT NULL" + s.yearFilter("pub") +
                        " GROUP BY yr ORDER BY yr"));

        q.put("New artifacts per year (first appearance)", sql(
                mvArtCte(s) +
                        "SELECT " + yearExpr("first_pub") + " AS yr, COUNT(*) AS new_artifacts " +
                        "FROM art GROUP BY yr ORDER BY yr"));

        q.put("New groups per year (first appearance)", sql(
                mvArtCte(s) +
                        "SELECT " + yearExpr("g_first") + " AS yr, COUNT(*) AS new_groups FROM (" +
                        "SELECT gid, MIN(first_pub) AS g_first FROM art GROUP BY gid) GROUP BY yr ORDER BY yr"));

        return runAll(q);
    }

    /** Versions per artifact, single-release share, lifespan, update frequency. */
    public List<Report> lifecycle(Scope s) throws SQLException {
        LinkedHashMap<String, ReportBody> q = new LinkedHashMap<>();

        q.put("Versions per artifact", (conn, title) -> {
            Object[] a = oneRow(conn, mvArtCte(s) +
                    "SELECT COUNT(*) AS artifacts, round(AVG(versions), 2) AS mean_versions, " +
                    "MAX(versions) AS max_versions FROM art", 3);
            Double[] p = quantiles(conn, mvArtCte(s) + "SELECT versions FROM art", 0.5, 0.90, 0.99);
            return new Report(title,
                    List.of("artifacts", "mean_versions", "median_versions", "p90_versions",
                            "p99_versions", "max_versions"),
                    List.of(Arrays.asList(a[0], a[1], p[0], p[1], p[2], a[2])));
        });

        // pct denominator: every art row lands in a bucket, so the grouped total
        // is simply COUNT(*) of art (a scalar subquery instead of DuckDB's
        // SUM(COUNT(*)) OVER ()).
        q.put("Release-count distribution", sql(
                mvArtCte(s) +
                        "SELECT CASE WHEN versions = 1 THEN '1' " +
                        "WHEN versions BETWEEN 2 AND 5 THEN '2-5' " +
                        "WHEN versions BETWEEN 6 AND 10 THEN '6-10' " +
                        "WHEN versions BETWEEN 11 AND 25 THEN '11-25' " +
                        "WHEN versions BETWEEN 26 AND 50 THEN '26-50' ELSE '50+' END AS release_bucket, " +
                        "COUNT(*) AS artifacts, round(100.0 * COUNT(*) / (SELECT COUNT(*) FROM art), 2) AS pct " +
                        "FROM art GROUP BY release_bucket ORDER BY MIN(versions)"));

        q.put("Single-release artifacts (\"one and done\")", sql(
                mvArtCte(s) +
                        "SELECT COUNT(*) FILTER (WHERE versions = 1) AS single_release, COUNT(*) AS total, " +
                        "round(100.0 * COUNT(*) FILTER (WHERE versions = 1) / nullif(COUNT(*), 0), 2) AS pct_single FROM art"));

        q.put("Lifespan first->last (artifacts with >1 release)", (conn, title) -> {
            Object[] a = oneRow(conn, mvArtCte(s) +
                    "SELECT COUNT(*) AS artifacts, round(AVG(span_days), 1) AS mean_span_days, " +
                    "round(AVG(span_days) / 365.25, 2) AS mean_span_years FROM art WHERE versions > 1", 3);
            Double[] p = quantiles(conn, mvArtCte(s) +
                    "SELECT span_days FROM art WHERE versions > 1", 0.5);
            Double medianYears = (p[0] == null) ? null : round2(p[0] / 365.25);
            return new Report(title,
                    List.of("artifacts", "mean_span_days", "median_span_days",
                            "mean_span_years", "median_span_years"),
                    List.of(Arrays.asList(a[0], a[1], p[0], a[2], medianYears)));
        });

        q.put("Lifespan distribution", sql(
                mvArtCte(s) +
                        "SELECT CASE WHEN span_days = 0 THEN 'single release' " +
                        "WHEN span_days < 30 THEN '< 1 month' " +
                        "WHEN span_days < 365 THEN '< 1 year' " +
                        "WHEN span_days < 730 THEN '1-2 years' " +
                        "WHEN span_days < 1825 THEN '2-5 years' ELSE '5+ years' END AS lifespan_bucket, " +
                        "COUNT(*) AS artifacts, round(100.0 * COUNT(*) / (SELECT COUNT(*) FROM art), 2) AS pct " +
                        "FROM art GROUP BY lifespan_bucket ORDER BY MIN(span_days)"));

        q.put("Update frequency (releases/year over active life)", (conn, title) -> {
            String rpy = mvArtCte(s) +
                    "SELECT versions / (span_days / 365.25) AS rpy FROM art WHERE versions > 1 AND span_days > 0";
            Object[] a = oneRow(conn, mvArtCte(s) +
                    "SELECT round(AVG(versions / (span_days / 365.25)), 3) AS mean_releases_per_year " +
                    "FROM art WHERE versions > 1 AND span_days > 0", 1);
            Double[] p = quantiles(conn, rpy, 0.5, 0.90);
            return new Report(title,
                    List.of("mean_releases_per_year", "median_releases_per_year", "p90_releases_per_year"),
                    List.of(Arrays.asList(a[0], p[0], p[1])));
        });

        q.put("Gap between consecutive releases (days)", (conn, title) -> {
            String gaps = mvArtCte(s) +
                    "SELECT gap_days FROM (" +
                    "SELECT " + dayDiff("lag(pub) OVER (PARTITION BY gid, aid ORDER BY pub)", "pub") + " AS gap_days " +
                    "FROM mv WHERE pub IS NOT NULL" + s.yearFilter("pub") + ") WHERE gap_days IS NOT NULL";
            Object[] a = oneRow(conn,
                    "SELECT COUNT(*) AS release_intervals, round(AVG(gap_days), 1) AS mean_gap_days FROM (" +
                            gaps + ")", 2);
            Double[] p = quantiles(conn, gaps, 0.5, 0.90);
            return new Report(title,
                    List.of("release_intervals", "mean_gap_days", "median_gap_days", "p90_gap_days"),
                    List.of(Arrays.asList(a[0], a[1], p[0], p[1])));
        });

        return runAll(q);
    }

    /** Abandonment: how many artifacts have gone quiet, and a crude survival curve. */
    public List<Report> abandonment(Scope s) throws SQLException {
        LinkedHashMap<String, ReportBody> q = new LinkedHashMap<>();
        // Age of the last release in whole days, day-boundary semantics as above.
        String age = dayDiff("last_pub", "'now'");

        q.put("Abandonment summary (relative to now)", sql(
                mvArtCte(s) +
                        "SELECT COUNT(*) AS artifacts, " +
                        "COUNT(*) FILTER (WHERE last_pub < datetime('now', '-2 years')) AS quiet_2y, " +
                        "round(100.0 * COUNT(*) FILTER (WHERE last_pub < datetime('now', '-2 years')) / nullif(COUNT(*), 0), 2) AS pct_quiet_2y, " +
                        "COUNT(*) FILTER (WHERE last_pub < datetime('now', '-5 years')) AS quiet_5y, " +
                        "round(100.0 * COUNT(*) FILTER (WHERE last_pub < datetime('now', '-5 years')) / nullif(COUNT(*), 0), 2) AS pct_quiet_5y FROM art"));

        q.put("Last-release age distribution", sql(
                mvArtCte(s) +
                        "SELECT CASE WHEN " + age + " < 365 THEN 'active (<1y)' " +
                        "WHEN " + age + " < 730 THEN '1-2y' " +
                        "WHEN " + age + " < 1825 THEN '2-5y' " +
                        "WHEN " + age + " < 3650 THEN '5-10y' ELSE '10y+' END AS last_release_age, " +
                        "COUNT(*) AS artifacts, round(100.0 * COUNT(*) / (SELECT COUNT(*) FROM art), 2) AS pct " +
                        "FROM art GROUP BY last_release_age ORDER BY MIN(" + age + ")"));

        q.put("Single-release vs recency", sql(
                mvArtCte(s) +
                        "SELECT versions = 1 AS single_release, COUNT(*) AS artifacts, " +
                        "round(AVG((julianday(date('now')) - julianday(date(last_pub))) / 365.25), 2) AS mean_years_since_last " +
                        "FROM art GROUP BY single_release ORDER BY single_release"));

        return runAll(q);
    }

    /**
     * Age of each artifact's latest (most recently published) release, in
     * whole days as of now — one row per distinct age with the count of
     * artifacts at that age. Unlike {@link #abandonment}'s named buckets, this
     * is unbucketed so it can be pivoted into whatever histogram/CDF the
     * caller wants downstream (e.g. "how much of the catalogue is >N days
     * old"). "Latest" means most-recently-published per {@code (gid, aid)},
     * the same {@code art} rollup used elsewhere — not the highest
     * version-string.
     */
    public List<Report> age(Scope s) throws SQLException {
        LinkedHashMap<String, ReportBody> q = new LinkedHashMap<>();
        String age = dayDiff("last_pub", "'now'");

        q.put("Latest-version age in days -> artifact count", sql(
                mvArtCte(s) +
                        "SELECT " + age + " AS age_days, COUNT(*) AS artifacts " +
                        "FROM art GROUP BY age_days ORDER BY age_days"));

        return runAll(q);
    }

    /**
     * Cross-group ("external") dependency coupling, scoped to each artifact's
     * <b>latest graphed version</b> — the newest-by-publish-date version among
     * those that have gone through {@code graph mine+resolve} / {@code graph
     * deps} / {@code graph import-goblin} and therefore have edges in {@code
     * dependencies}. "External" is a literal group-id mismatch
     * ({@code child.gid <> parent.gid}); a hierarchical split of the same
     * publisher (e.g. {@code org.foo} depending on {@code org.foo.bar}) still
     * counts as external under this definition — unlike {@link Scope}'s
     * subgroup-aware {@code -g/--gav} filter, which is about selecting rows,
     * not comparing two different groups against each other.
     *
     * <p>Many catalogued "latest" versions have not been mined/resolved yet,
     * so they have no row in {@code artifacts}/{@code dependencies} at all;
     * the summary reports both the full latest-version count and how many of
     * those are actually graphed, so the coupling stats aren't silently
     * computed over a biased subset without saying so.
     *
     * @param topN how many of the most-referenced external dependencies to list.
     */
    public List<Report> externalDeps(Scope s, int topN) throws SQLException {
        LinkedHashMap<String, ReportBody> q = new LinkedHashMap<>();
        String gavMa = s.gavFilter("ma.gid", "ma.aid");

        String cte = "WITH mv AS (" +
                "SELECT ma.gid, ma.aid, v.version, " + pubExpr("v.published") + " AS pub, " +
                "ROW_NUMBER() OVER (PARTITION BY ma.gid, ma.aid ORDER BY " + pubExpr("v.published") + " DESC) AS rn " +
                "FROM meta_versions v JOIN meta_artifacts ma ON ma.id = v.ga_id WHERE 1=1" + gavMa + "), " +
                "latest_meta AS (" +
                "SELECT gid, aid, version, pub FROM mv WHERE rn = 1 AND pub IS NOT NULL" + s.yearFilter("pub") + "), " +
                "latest_art AS (" +
                "SELECT a.id AS artifact_id, a.gid, a.aid, a.version FROM latest_meta lm " +
                "JOIN artifacts a ON a.gid = lm.gid AND a.aid = lm.aid AND a.version = lm.version " +
                "AND coalesce(a.classifier, '') = ''), " +
                "ext AS (" +
                "SELECT la.gid AS p_gid, la.aid AS p_aid, la.version AS p_version, " +
                "ca.gid AS c_gid, ca.aid AS c_aid, ca.version AS c_version " +
                "FROM dependencies d JOIN latest_art la ON la.artifact_id = d.parent_id " +
                "JOIN artifacts ca ON ca.id = d.child_id WHERE ca.gid <> la.gid) ";

        q.put("External-dependency coupling summary (latest graphed version per artifact)", sql(
                cte +
                        "SELECT (SELECT COUNT(*) FROM latest_meta) AS total_latest_artifacts, " +
                        "(SELECT COUNT(*) FROM latest_art) AS latest_artifacts_graphed, " +
                        "(SELECT COUNT(*) FROM latest_art la WHERE EXISTS (SELECT 1 FROM dependencies d WHERE d.parent_id = la.artifact_id)) AS latest_artifacts_with_any_deps, " +
                        "(SELECT COUNT(DISTINCT p_gid || ':' || p_aid || ':' || p_version) FROM ext) AS latest_artifacts_with_external_dep, " +
                        "(SELECT COUNT(*) FROM ext) AS external_dep_edges, " +
                        "round(100.0 * (SELECT COUNT(DISTINCT p_gid || ':' || p_aid || ':' || p_version) FROM ext) / " +
                        "nullif((SELECT COUNT(*) FROM latest_art), 0), 2) AS pct_graphed_with_external_dep"));

        q.put("Most-referenced external dependencies (top " + topN + ")", sql(
                cte +
                        "SELECT c_gid, c_aid, " +
                        "COUNT(DISTINCT p_gid || ':' || p_aid || ':' || p_version) AS referring_artifacts, " +
                        "COUNT(DISTINCT c_version) AS distinct_versions_referenced " +
                        "FROM ext GROUP BY c_gid, c_aid " +
                        "ORDER BY referring_artifacts DESC, c_gid, c_aid LIMIT " + topN));

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
        LinkedHashMap<String, ReportBody> q = new LinkedHashMap<>();
        String gavMa = s.gavFilter("ma.gid", "ma.aid");
        String gavA = s.gavFilter("a.gid", "a.aid");

        q.put("Resolution funnel (catalogued -> mined -> resolved)", sql(
                "WITH f AS (SELECT " +
                        "(SELECT COUNT(*) FROM meta_versions v JOIN meta_artifacts ma ON ma.id = v.ga_id " +
                        "WHERE NOT coalesce(v.missing_pom, false)" + gavMa + ") AS catalogued_with_pom, " +
                        "(SELECT COUNT(*) FROM pom_meta pm JOIN artifacts a ON a.id = pm.artifact_id WHERE 1=1" + gavA + ") AS mined, " +
                        "(SELECT COUNT(*) FROM pom_meta pm JOIN artifacts a ON a.id = pm.artifact_id WHERE coalesce(pm.deps_resolved, false)" + gavA + ") AS resolved, " +
                        "(SELECT COUNT(*) FROM pom_meta pm JOIN artifacts a ON a.id = pm.artifact_id WHERE NOT coalesce(pm.deps_resolved, false)" + gavA + ") AS mined_unresolved) " +
                        "SELECT catalogued_with_pom, mined, resolved, mined_unresolved, " +
                        "round(100.0 * resolved / nullif(mined, 0), 2) AS pct_resolved_of_mined, " +
                        "round(100.0 * mined / nullif(catalogued_with_pom, 0), 2) AS pct_mined_of_catalogued FROM f"));

        q.put("Catalogued but not yet mined (run 'graph mine')", sql(
                "SELECT COUNT(*) AS catalogued_unmined " +
                        "FROM meta_versions v JOIN meta_artifacts ma ON ma.id = v.ga_id " +
                        "LEFT JOIN artifacts a ON a.gid = ma.gid AND a.aid = ma.aid AND a.version = v.version AND coalesce(a.classifier, '') = '' " +
                        "LEFT JOIN pom_meta pm ON pm.artifact_id = a.id " +
                        "WHERE NOT coalesce(v.missing_pom, false)" + gavMa + " AND pm.artifact_id IS NULL"));

        q.put("Resolved but no edges (unresolvable declared deps: ranges/properties/profiles)", sql(
                "SELECT COUNT(*) AS resolved_but_no_edges " +
                        "FROM pom_meta pm JOIN artifacts a ON a.id = pm.artifact_id " +
                        "WHERE coalesce(pm.deps_resolved, false)" + gavA + " " +
                        "AND EXISTS (SELECT 1 FROM direct_dep dd WHERE dd.artifact_id = pm.artifact_id) " +
                        "AND NOT EXISTS (SELECT 1 FROM dependencies d WHERE d.parent_id = pm.artifact_id)"));

        q.put("Sample unresolved mined POMs", sql(
                "SELECT a.gid, a.aid, a.version FROM pom_meta pm JOIN artifacts a ON a.id = pm.artifact_id " +
                        "WHERE NOT coalesce(pm.deps_resolved, false)" + gavA + " ORDER BY a.gid, a.aid, a.version LIMIT 50"));

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
        LinkedHashMap<String, ReportBody> q = new LinkedHashMap<>();
        String mode = raw ? "raw declared (direct_dep)" : "resolved (dependencies)";

        q.put("Dependency version churn — summary [" + mode + "]", sql(
                churnSeqCte(s, raw) +
                        "SELECT COUNT(*) FILTER (WHERE prev_ver IS NOT NULL) AS retained_dep_transitions, " +
                        "COUNT(*) FILTER (WHERE prev_ver IS NOT NULL AND c_ver <> prev_ver) AS version_bumped, " +
                        "round(100.0 * COUNT(*) FILTER (WHERE prev_ver IS NOT NULL AND c_ver <> prev_ver) " +
                        "/ nullif(COUNT(*) FILTER (WHERE prev_ver IS NOT NULL), 0), 2) AS pct_bumped FROM seq"));

        q.put("Dependency version churn — per release [" + mode + "]", sql(
                churnSeqCte(s, raw) +
                        ", per_release AS (SELECT p_gid, p_aid, p_ver, " +
                        "COUNT(*) FILTER (WHERE prev_ver IS NOT NULL) AS retained, " +
                        "COUNT(*) FILTER (WHERE prev_ver IS NOT NULL AND c_ver <> prev_ver) AS bumped " +
                        "FROM seq GROUP BY p_gid, p_aid, p_ver) " +
                        "SELECT COUNT(*) FILTER (WHERE retained > 0) AS releases_with_retained_deps, " +
                        "COUNT(*) FILTER (WHERE bumped > 0) AS releases_that_bumped_something, " +
                        "round(100.0 * COUNT(*) FILTER (WHERE bumped > 0) / nullif(COUNT(*) FILTER (WHERE retained > 0), 0), 2) AS pct_releases_bumping_a_dep, " +
                        "round(AVG(CASE WHEN retained > 0 THEN 100.0 * bumped / retained END), 2) AS avg_pct_deps_bumped_per_release " +
                        "FROM per_release"));

        if (topN > 0) {
            q.put("Most-frequently-bumped dependencies (top " + topN + ") [" + mode + "]", sql(
                    churnSeqCte(s, raw) +
                            "SELECT c_gid, c_aid, " +
                            "COUNT(*) FILTER (WHERE prev_ver IS NOT NULL AND c_ver <> prev_ver) AS times_bumped, " +
                            "COUNT(*) FILTER (WHERE prev_ver IS NOT NULL) AS retained_transitions, " +
                            "round(100.0 * COUNT(*) FILTER (WHERE prev_ver IS NOT NULL AND c_ver <> prev_ver) " +
                            "/ nullif(COUNT(*) FILTER (WHERE prev_ver IS NOT NULL), 0), 2) AS pct_bumped " +
                            "FROM seq GROUP BY c_gid, c_aid ORDER BY times_bumped DESC, retained_transitions DESC LIMIT " + topN));
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
                pubExpr("v.published") + " AS pub " +
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

    /** Produces one titled {@link Report} against an open connection. */
    @FunctionalInterface
    private interface ReportBody {
        Report run(Connection conn, String title) throws SQLException;
    }

    /** A plain single-statement report. */
    private static ReportBody sql(String query) {
        return (conn, title) -> {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(query)) {
                return pack(title, rs);
            }
        };
    }

    /** Run each titled report; one that fails (e.g. table absent) becomes a note row. */
    private List<Report> runAll(LinkedHashMap<String, ReportBody> titled) throws SQLException {
        List<Report> out = new ArrayList<>();
        try (Connection conn = open()) {
            for (var e : titled.entrySet()) {
                try {
                    out.add(e.getValue().run(conn, e.getKey()));
                } catch (SQLException ex) {
                    log.debug("report '{}' failed: {}", e.getKey(), ex.getMessage());
                    out.add(new Report(e.getKey(), List.of("note"),
                            List.of(List.of("unavailable: " + rootMessage(ex)))));
                }
            }
        }
        return out;
    }

    /** Fetch the single row of an aggregate query as objects (NULLs preserved). */
    private static Object[] oneRow(Connection conn, String query, int cols) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(query)) {
            Object[] out = new Object[cols];
            if (rs.next()) {
                for (int i = 0; i < cols; i++) out[i] = rs.getObject(i + 1);
            }
            return out;
        }
    }

    /**
     * Continuous quantiles of a single-column numeric query, replacing DuckDB's
     * {@code median}/{@code quantile_cont}: for the {@code n} ordered values the
     * {@code q}-quantile sits at 0-based rank {@code q*(n-1)}; a fractional rank
     * interpolates linearly between the two neighbouring values (exactly
     * {@code quantile_cont}'s semantics; {@code median} = quantile 0.5). The
     * value query must already exclude NULLs. Costs one COUNT plus one ordered
     * scan capped at the highest rank needed — constant memory regardless of
     * result size. Returns all-null when the value set is empty.
     */
    private static Double[] quantiles(Connection conn, String valuesQuery, double... qs) throws SQLException {
        long n = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM (" + valuesQuery + ")")) {
            if (rs.next()) n = rs.getLong(1);
        }
        Double[] out = new Double[qs.length];
        if (n == 0) return out;

        double[] pos = new double[qs.length];
        TreeSet<Long> ranks = new TreeSet<>();
        for (int i = 0; i < qs.length; i++) {
            pos[i] = qs[i] * (n - 1);
            ranks.add((long) Math.floor(pos[i]));
            ranks.add((long) Math.ceil(pos[i]));
        }
        long maxRank = ranks.last();

        Map<Long, Double> at = new HashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(valuesQuery + " ORDER BY 1 LIMIT " + (maxRank + 1))) {
            long r = 0;
            while (rs.next()) {
                if (ranks.contains(r)) at.put(r, rs.getDouble(1));
                r++;
            }
        }

        for (int i = 0; i < qs.length; i++) {
            Double lo = at.get((long) Math.floor(pos[i]));
            Double hi = at.get((long) Math.ceil(pos[i]));
            if (lo == null || hi == null) continue; // value set shorter than expected
            double frac = pos[i] - Math.floor(pos[i]);
            out[i] = lo + frac * (hi - lo);
        }
        return out;
    }

    private static Double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
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
