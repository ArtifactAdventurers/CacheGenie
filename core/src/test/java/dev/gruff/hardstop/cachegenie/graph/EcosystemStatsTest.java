package dev.gruff.hardstop.cachegenie.graph;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Exercises {@link EcosystemStats} against a small synthetic graph.db built in a
 * temp folder. Data:
 * <ul>
 *   <li>org.foo:bar      — 3 dated versions (2018, 2019, 2021) + 1 undated</li>
 *   <li>org.foo:solo     — 1 dated version (2017) — the "one and done"</li>
 *   <li>org.foo.sub:child— 2 dated versions (2020) — exercises subgroup scope</li>
 * </ul>
 * bar's resolved/declared deps move guava 30.0 -&gt; 31.0 -&gt; 31.0 and add junit
 * at 1.1, so exactly one retained-dependency version bump exists.
 */
public class EcosystemStatsTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File root;

    @Before
    public void setUp() throws Exception {
        root = tempFolder.getRoot();
        // Create the full schema (meta + graph + mining tables).
        new MetaRepository(root);
        new GraphRepository(root);

        String db = new File(root, "graph.db").getAbsolutePath();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + db);
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO meta_artifacts (id, gid, aid) VALUES " +
                    "(1,'org.foo','bar'),(2,'org.foo','solo'),(3,'org.foo.sub','child')");
            st.execute("INSERT INTO meta_versions (ga_id, version, published, missing_pom) VALUES " +
                    "(1,'1.0','2018-01-01T10:00:00Z',false)," +
                    "(1,'1.1','2019-06-01T10:00:00Z',false)," +
                    "(1,'2.0','2021-03-15T10:00:00.123Z',false)," +
                    "(1,'2.1',NULL,false)," +
                    "(2,'1.0','2017-02-02T08:00:00Z',false)," +
                    "(3,'0.1','2020-01-01T00:00:00Z',false)," +
                    "(3,'0.2','2020-09-01T00:00:00Z',false)");
            st.execute("INSERT INTO artifacts (id, gid, aid, version, classifier) VALUES " +
                    "(10,'org.foo','bar','1.0',''),(11,'org.foo','bar','1.1',''),(12,'org.foo','bar','2.0','')," +
                    "(20,'com.google.guava','guava','30.0',''),(21,'com.google.guava','guava','31.0','')," +
                    "(30,'junit','junit','4.13','')");
            st.execute("INSERT INTO dependencies (parent_id, child_id, scope) VALUES " +
                    "(10,20,'compile'),(11,21,'compile'),(11,30,'test'),(12,21,'compile')");
            st.execute("INSERT INTO direct_dep (artifact_id, ord, dep_gid, dep_aid, dep_version, scope) VALUES " +
                    "(10,0,'com.google.guava','guava','30.0','compile')," +
                    "(11,0,'com.google.guava','guava','31.0','compile')," +
                    "(11,1,'junit','junit','4.13','test')," +
                    "(12,0,'com.google.guava','guava','${guava.version}','compile')");
            // bar 1.0/1.1 mined+resolved, 2.0 mined-but-unresolved; solo + child + bar 2.1 left un-mined.
            st.execute("INSERT INTO pom_meta (artifact_id, deps_resolved) VALUES (10,true),(11,true),(12,false)");
        }
    }

    /** Read-write so the test never races a read-only open against its own writer. */
    private EcosystemStats stats() {
        return new EcosystemStats(root, false);
    }

    @Test
    public void arrivalsReportsCoverage() throws Exception {
        List<EcosystemStats.Report> r = stats().arrivals(EcosystemStats.Scope.all());
        EcosystemStats.Report cov = report(r, "Catalogue coverage");
        assertEquals(7L, lng(val(cov, "total_versions")));
        assertEquals(6L, lng(val(cov, "with_timestamp")));
        assertEquals(1L, lng(val(cov, "missing_timestamp")));

        // versions per year is present and ordered
        EcosystemStats.Report perYear = report(r, "Versions published per year");
        assertFalse(perYear.rows().isEmpty());
    }

    @Test
    public void lifecycleCountsSingleRelease() throws Exception {
        List<EcosystemStats.Report> r = stats().lifecycle(EcosystemStats.Scope.all());
        EcosystemStats.Report single = report(r, "Single-release");
        assertEquals(1L, lng(val(single, "single_release")));  // org.foo:solo
        assertEquals(3L, lng(val(single, "total")));
        assertEquals(33.33, dbl(val(single, "pct_single")), 0.01);
    }

    @Test
    public void churnResolvedCountsOneBump() throws Exception {
        List<EcosystemStats.Report> r = stats().churn(EcosystemStats.Scope.all(), false, 10);
        EcosystemStats.Report sum = report(r, "Dependency version churn — summary");
        assertEquals(2L, lng(val(sum, "retained_dep_transitions"))); // guava seen in 2 consecutive pairs
        assertEquals(1L, lng(val(sum, "version_bumped")));           // 30.0 -> 31.0
        assertEquals(50.0, dbl(val(sum, "pct_bumped")), 0.01);

        // top-N requested -> the most-bumped report is present
        assertTrue(r.stream().anyMatch(rep -> rep.title().startsWith("Most-frequently-bumped")));
    }

    @Test
    public void churnRawExcludesPropertyTokens() throws Exception {
        List<EcosystemStats.Report> r = stats().churn(EcosystemStats.Scope.all(), true, 0);
        EcosystemStats.Report sum = report(r, "Dependency version churn — summary");
        // bar 2.0's guava is ${guava.version} (dropped), leaving one literal pair 30.0->31.0
        assertEquals(1L, lng(val(sum, "retained_dep_transitions")));
        assertEquals(1L, lng(val(sum, "version_bumped")));
        assertEquals(100.0, dbl(val(sum, "pct_bumped")), 0.01);
    }

    @Test
    public void gavScopeMatchesSubgroups() throws Exception {
        // group scope on org.foo should also pick up org.foo.sub
        EcosystemStats.Scope scope = EcosystemStats.Scope.of("org.foo", null, null);
        List<EcosystemStats.Report> r = stats().arrivals(scope);
        assertEquals(7L, lng(val(report(r, "Catalogue coverage"), "total_versions")));

        // exact group:artifact narrows to just bar's versions
        EcosystemStats.Scope exact = EcosystemStats.Scope.of("org.foo:bar", null, null);
        List<EcosystemStats.Report> rb = stats().arrivals(exact);
        assertEquals(4L, lng(val(report(rb, "Catalogue coverage"), "total_versions")));
    }

    @Test
    public void abandonmentRunsAndCountsArtifacts() throws Exception {
        List<EcosystemStats.Report> r = stats().abandonment(EcosystemStats.Scope.all());
        EcosystemStats.Report sum = report(r, "Abandonment summary");
        assertEquals(3L, lng(val(sum, "artifacts")));
    }

    @Test
    public void resolutionReportsFunnelAndBacklog() throws Exception {
        List<EcosystemStats.Report> r = stats().resolution(EcosystemStats.Scope.all());

        EcosystemStats.Report funnel = report(r, "Resolution funnel");
        assertEquals(7L, lng(val(funnel, "catalogued_with_pom"))); // none missing_pom
        assertEquals(3L, lng(val(funnel, "mined")));               // 3 pom_meta rows
        assertEquals(2L, lng(val(funnel, "resolved")));            // 10, 11
        assertEquals(1L, lng(val(funnel, "mined_unresolved")));    // 12

        EcosystemStats.Report unmined = report(r, "Catalogued but not yet mined");
        assertEquals(4L, lng(val(unmined, "catalogued_unmined"))); // bar 2.1, solo 1.0, child 0.1/0.2
    }

    // --- helpers ---------------------------------------------------------

    private static EcosystemStats.Report report(List<EcosystemStats.Report> rs, String titlePrefix) {
        return rs.stream().filter(x -> x.title().startsWith(titlePrefix)).findFirst()
                .orElseThrow(() -> new AssertionError("no report titled like: " + titlePrefix));
    }

    private static Object val(EcosystemStats.Report r, String col) {
        int i = r.columns().indexOf(col);
        assertTrue("column '" + col + "' present in " + r.columns(), i >= 0);
        assertFalse("rows present for " + r.title(), r.rows().isEmpty());
        return r.rows().get(0).get(i);
    }

    private static long lng(Object o) {
        return ((Number) o).longValue();
    }

    private static double dbl(Object o) {
        return ((Number) o).doubleValue();
    }
}
