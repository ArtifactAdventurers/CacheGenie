package dev.gruff.hardstop.cachegenie.graph;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Drives the streamed, multi-threaded {@link PomResolver} over a small synthetic
 * mined graph that exercises every resolution path:
 * <ul>
 *   <li>parent-chain property inheritance ({@code ${guava.version}} → managed version),</li>
 *   <li>import-scope BOM managed versions (junit via org.bom:bom),</li>
 *   <li>a literal version targeting a coordinate that is not yet an artifact (synthetic insert),</li>
 *   <li>an unresolvable {@code ${missing}} token (counted, no edge).</li>
 * </ul>
 */
public class PomResolverTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File root;
    private String db;

    @Before
    public void setUp() throws Exception {
        root = tempFolder.getRoot();
        new GraphRepository(root); // creates artifacts, dependencies, pom_meta, mining tables, seq_artifact_id
        db = new File(root, "graph.db").getAbsolutePath();

        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + db);
             Statement st = c.createStatement()) {
            // Artifacts: 1=parent, 2=child, 3=guava (exists), 4=junit (exists), 5=bom.
            st.execute("INSERT INTO artifacts (id, gid, aid, version, classifier) VALUES " +
                    "(1,'org.ex','parent','1.0',''),(2,'org.ex','child','2.0','')," +
                    "(3,'com.google.guava','guava','30.0',''),(4,'junit','junit','4.13','')," +
                    "(5,'org.bom','bom','5.0','')");
            // Advance the artifact-id sequence past the manual ids so a synthetic insert can't collide.
            st.execute("SELECT nextval('seq_artifact_id') FROM range(5)");

            st.execute("INSERT INTO pom_meta (artifact_id, parent_gid, parent_aid, parent_version) VALUES " +
                    "(1,NULL,NULL,NULL),(2,'org.ex','parent','1.0'),(5,NULL,NULL,NULL)");
            st.execute("INSERT INTO pom_properties (artifact_id, prop_key, prop_value) VALUES (1,'guava.version','30.0')");
            st.execute("INSERT INTO dependency_management (artifact_id, ord, dep_gid, dep_aid, dep_version, scope, dep_type, dep_classifier) VALUES " +
                    "(1,0,'com.google.guava','guava','${guava.version}',NULL,NULL,NULL)," +
                    "(1,1,'org.bom','bom','5.0','import','pom',NULL)," +
                    "(5,0,'junit','junit','4.13',NULL,NULL,NULL)");
            st.execute("INSERT INTO direct_dep (artifact_id, ord, dep_gid, dep_aid, dep_version, scope, dep_type, dep_classifier) VALUES " +
                    "(2,0,'com.google.guava','guava',NULL,NULL,NULL,NULL)," +
                    "(2,1,'junit','junit',NULL,'test',NULL,NULL)," +
                    "(2,2,'org.apache.commons','commons-lang3','3.12.0',NULL,NULL,NULL)," +
                    "(2,3,'foo','bar','${missing}',NULL,NULL,NULL)");
        }
    }

    @Test
    public void resolvesParentBomAndPropertyThreaded() throws Exception {
        PomResolver.ResolveStats stats = new PomResolver(root).resolveAll(false, 4);

        assertEquals("all three mined nodes processed", 3, stats.nodes());
        assertEquals("one unresolvable ${missing} dep", 1, stats.unresolved());

        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + db);
             Statement st = c.createStatement()) {
            // child(2) should have exactly three resolved edges
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM dependencies WHERE parent_id = 2")) {
                rs.next();
                assertEquals(3, rs.getInt(1));
            }
            // guava resolved via parent property -> managed version 30.0 (existing artifact 3)
            assertEdge(st, 2, "com.google.guava", "guava", "30.0", "compile");
            // junit resolved via the import BOM, scope preserved
            assertEdge(st, 2, "junit", "junit", "4.13", "test");
            // commons-lang3 had no artifact row -> writer inserted one and linked it
            assertEdge(st, 2, "org.apache.commons", "commons-lang3", "3.12.0", "compile");
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM artifacts WHERE gid='org.apache.commons' AND aid='commons-lang3' AND version='3.12.0'")) {
                rs.next();
                assertEquals("synthetic artifact created", 1, rs.getInt(1));
            }
            // everything mined is now marked resolved
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM pom_meta WHERE deps_resolved = FALSE")) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    public void reResolveIsIdempotent() throws Exception {
        new PomResolver(root).resolveAll(false, 2);
        // --all re-runs every node; INSERT OR IGNORE means edges don't duplicate.
        new PomResolver(root).resolveAll(true, 2);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + db);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM dependencies WHERE parent_id = 2")) {
            rs.next();
            assertEquals(3, rs.getInt(1));
        }
    }

    @Test
    public void literalOnlyPomResolvedSetBased() throws Exception {
        // A POM with only literal, concrete versions — one to an existing artifact,
        // one to a coordinate that must be synthesised — should be fully resolved by
        // the set-based fast path (no parent/property/management needed). Manual ids
        // are high so they can't collide with sequence-assigned synthetic ids.
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + db);
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO artifacts (id, gid, aid, version, classifier) VALUES " +
                    "(50,'org.lit','app','1.0',''),(51,'org.lib','present','2.0','')");
            st.execute("INSERT INTO pom_meta (artifact_id, parent_gid, parent_aid, parent_version) VALUES (50,NULL,NULL,NULL)");
            st.execute("INSERT INTO direct_dep (artifact_id, ord, dep_gid, dep_aid, dep_version, scope, dep_type, dep_classifier) VALUES " +
                    "(50,0,'org.lib','present','2.0',NULL,NULL,NULL)," +
                    "(50,1,'org.lib','fresh','9.9',NULL,NULL,NULL)");
        }

        new PomResolver(root).resolveAll(false, 4);

        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + db);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT deps_resolved FROM pom_meta WHERE artifact_id = 50")) {
                rs.next();
                assertTrue("literal-only POM marked resolved", rs.getBoolean(1));
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM dependencies WHERE parent_id = 50")) {
                rs.next();
                assertEquals(2, rs.getInt(1));
            }
            assertEdge(st, 50, "org.lib", "present", "2.0", "compile");
            assertEdge(st, 50, "org.lib", "fresh", "9.9", "compile"); // synthesised then linked
        }
    }

    @Test
    public void samePomPropertyAndManagedResolvedSetBased() throws Exception {
        // POM 60: a dep whose version is exactly ${lib.version} defined in this POM,
        //         and a dep with no version pinned by this POM's own dependencyManagement.
        // Both are context-free (same-POM), so the set-based pass should fully resolve it.
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + db);
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO artifacts (id, gid, aid, version, classifier) VALUES " +
                    "(60,'org.app','svc','1.0',''),(61,'org.lib','byprop','7.0',''),(62,'org.lib','bymgmt','8.0','')");
            st.execute("INSERT INTO pom_meta (artifact_id, parent_gid, parent_aid, parent_version) VALUES (60,NULL,NULL,NULL)");
            st.execute("INSERT INTO pom_properties (artifact_id, prop_key, prop_value) VALUES (60,'lib.version','7.0')");
            st.execute("INSERT INTO dependency_management (artifact_id, ord, dep_gid, dep_aid, dep_version, scope, dep_type, dep_classifier) VALUES " +
                    "(60,0,'org.lib','bymgmt','8.0',NULL,NULL,NULL)");
            st.execute("INSERT INTO direct_dep (artifact_id, ord, dep_gid, dep_aid, dep_version, scope, dep_type, dep_classifier) VALUES " +
                    "(60,0,'org.lib','byprop','${lib.version}',NULL,NULL,NULL)," +
                    "(60,1,'org.lib','bymgmt',NULL,NULL,NULL,NULL)");
        }

        new PomResolver(root).resolveAll(false, 4);

        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + db);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT deps_resolved FROM pom_meta WHERE artifact_id = 60")) {
                rs.next();
                assertTrue("same-POM property+managed POM fully resolved set-based", rs.getBoolean(1));
            }
            assertEdge(st, 60, "org.lib", "byprop", "7.0", "compile"); // via ${lib.version}
            assertEdge(st, 60, "org.lib", "bymgmt", "8.0", "compile"); // via same-POM dependencyManagement
        }
    }

    @Test
    public void duplicateDeclaredDependencyResolvesToOneEdge() throws Exception {
        // A POM may declare the same dependency twice (direct_dep keeps both rows). The
        // set-based edge insert must SELECT DISTINCT so it doesn't emit duplicate
        // (parent_id, child_id, scope) tuples in one command (which DuckDB rejects).
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + db);
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO artifacts (id, gid, aid, version, classifier) VALUES " +
                    "(70,'org.dup','app','1.0',''),(71,'org.dup','lib','5.0','')");
            st.execute("INSERT INTO pom_meta (artifact_id, parent_gid, parent_aid, parent_version) VALUES (70,NULL,NULL,NULL)");
            st.execute("INSERT INTO direct_dep (artifact_id, ord, dep_gid, dep_aid, dep_version, scope, dep_type, dep_classifier) VALUES " +
                    "(70,0,'org.dup','lib','5.0','compile',NULL,NULL)," +
                    "(70,1,'org.dup','lib','5.0',NULL,NULL,NULL)"); // duplicate; NULL scope also -> compile
        }

        new PomResolver(root).resolveAll(false, 4); // must not throw

        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + db);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM dependencies WHERE parent_id = 70")) {
            rs.next();
            assertEquals("duplicate declaration collapses to one edge", 1, rs.getInt(1));
        }
    }

    @Test
    public void inheritedParentChainResolvedSetBased() throws Exception {
        // grandparent (80) defines a property and a managed version that uses it;
        // parent (81) inherits from it; kid (82) has a null-version dep that must be
        // resolved from the GRANDPARENT's dependencyManagement + property. This is
        // pure parent-chain inheritance — resolvable by the set-based inherited pass.
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + db);
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO artifacts (id, gid, aid, version, classifier) VALUES " +
                    "(80,'g','gp','1',''),(81,'g','par','1',''),(82,'g','kid','1','')");
            st.execute("INSERT INTO pom_meta (artifact_id, parent_gid, parent_aid, parent_version) VALUES " +
                    "(80,NULL,NULL,NULL),(81,'g','gp','1'),(82,'g','par','1')");
            st.execute("INSERT INTO pom_properties (artifact_id, prop_key, prop_value) VALUES (80,'dep.ver','9.9')");
            st.execute("INSERT INTO dependency_management (artifact_id, ord, dep_gid, dep_aid, dep_version, scope, dep_type, dep_classifier) VALUES " +
                    "(80,0,'org.dep','lib','${dep.ver}',NULL,NULL,NULL)");
            st.execute("INSERT INTO direct_dep (artifact_id, ord, dep_gid, dep_aid, dep_version, scope, dep_type, dep_classifier) VALUES " +
                    "(82,0,'org.dep','lib',NULL,NULL,NULL,NULL)");
        }

        // set-based only: the inherited pass must resolve POM 82 with no per-node work.
        new PomResolver(root).resolveAll(false, 2, true);

        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + db);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT deps_resolved FROM pom_meta WHERE artifact_id = 82")) {
                rs.next();
                assertTrue("kid resolved via grandparent dm + property, set-based", rs.getBoolean(1));
            }
            // grandparent-managed version ${dep.ver}=9.9 -> org.dep:lib:9.9 (synthesised)
            assertEdge(st, 82, "org.dep", "lib", "9.9", "compile");
        }
    }

    @Test
    public void importBomManagedResolvedSetBased() throws Exception {
        // Spring-style import BOM: a BOM (90) manages spring-core via its own property; a
        // company parent (91) imports that BOM (version via an inherited property); an app
        // (92) inherits the import and has a null-version spring-core dep. The BOM pass must
        // resolve it — including when the consumer only inherits the import via its parent.
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + db);
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO artifacts (id, gid, aid, version, classifier) VALUES " +
                    "(90,'org.boot','deps','3.1.0',''),(91,'com.co','parent','1',''),(92,'com.co','app','1',''),(93,'org.sf','core','6.0.11','')");
            st.execute("INSERT INTO pom_meta (artifact_id, parent_gid, parent_aid, parent_version) VALUES " +
                    "(90,NULL,NULL,NULL),(91,NULL,NULL,NULL),(92,'com.co','parent','1')");
            st.execute("INSERT INTO pom_properties (artifact_id, prop_key, prop_value) VALUES " +
                    "(90,'sf.version','6.0.11'),(91,'boot.version','3.1.0')");
            st.execute("INSERT INTO dependency_management (artifact_id, ord, dep_gid, dep_aid, dep_version, scope, dep_type, dep_classifier) VALUES " +
                    "(90,0,'org.sf','core','${sf.version}',NULL,NULL,NULL)," +               // BOM manages core via its property
                    "(91,0,'org.boot','deps','${boot.version}','import','pom',NULL)");         // parent imports the BOM
            st.execute("INSERT INTO direct_dep (artifact_id, ord, dep_gid, dep_aid, dep_version, scope, dep_type, dep_classifier) VALUES " +
                    "(92,0,'org.sf','core',NULL,NULL,NULL,NULL)");
        }

        new PomResolver(root).resolveAll(false, 2, true);

        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + db);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT deps_resolved FROM pom_meta WHERE artifact_id = 92")) {
                rs.next();
                assertTrue("app resolved via inherited import BOM, set-based", rs.getBoolean(1));
            }
            assertEdge(st, 92, "org.sf", "core", "6.0.11", "compile");
        }
    }

    @Test
    public void transitiveBomOfBomResolvedSetBased() throws Exception {
        // app imports an OUTER BOM which imports an INNER BOM (version via the outer BOM's
        // property); the INNER BOM actually manages the dep (via its own property). The
        // transitive BOM closure must reach the inner BOM and resolve the dep.
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + db);
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO artifacts (id, gid, aid, version, classifier) VALUES " +
                    "(94,'boot','outer','1.0',''),(95,'boot','inner','2.0',''),(96,'org.sf','core','6.0.11',''),(97,'co','app2','1','')");
            st.execute("INSERT INTO pom_meta (artifact_id, parent_gid, parent_aid, parent_version) VALUES " +
                    "(94,NULL,NULL,NULL),(95,NULL,NULL,NULL),(97,NULL,NULL,NULL)");
            st.execute("INSERT INTO pom_properties (artifact_id, prop_key, prop_value) VALUES " +
                    "(94,'inner.ver','2.0'),(95,'sf.ver','6.0.11')");
            st.execute("INSERT INTO dependency_management (artifact_id, ord, dep_gid, dep_aid, dep_version, scope, dep_type, dep_classifier) VALUES " +
                    "(94,0,'boot','inner','${inner.ver}','import','pom',NULL)," +   // outer imports inner
                    "(95,0,'org.sf','core','${sf.ver}',NULL,NULL,NULL)," +          // inner manages core
                    "(97,0,'boot','outer','1.0','import','pom',NULL)");             // app imports outer
            st.execute("INSERT INTO direct_dep (artifact_id, ord, dep_gid, dep_aid, dep_version, scope, dep_type, dep_classifier) VALUES " +
                    "(97,0,'org.sf','core',NULL,NULL,NULL,NULL)");
        }

        new PomResolver(root).resolveAll(false, 2, true);

        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + db);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT deps_resolved FROM pom_meta WHERE artifact_id = 97")) {
                rs.next();
                assertTrue("resolved via transitive BOM-of-BOM", rs.getBoolean(1));
            }
            assertEdge(st, 97, "org.sf", "core", "6.0.11", "compile");
        }
    }

    private static void assertEdge(Statement st, int parentId, String g, String a, String v, String scope) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT d.scope FROM dependencies d JOIN artifacts a ON a.id = d.child_id " +
                        "WHERE d.parent_id = " + parentId + " AND a.gid = '" + g + "' AND a.aid = '" + a + "' AND a.version = '" + v + "'")) {
            assertTrue("expected edge to " + g + ":" + a + ":" + v, rs.next());
            assertEquals(scope, rs.getString(1));
        }
    }
}
