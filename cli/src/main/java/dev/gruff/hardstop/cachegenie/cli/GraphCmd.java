package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.MavenMetaData;
import dev.gruff.hardstop.cachegenie.MetaVersionSet;
import dev.gruff.hardstop.cachegenie.entities.POMStatus;
import dev.gruff.hardstop.cachegenie.actions.CacheAction;
import dev.gruff.hardstop.cachegenie.actions.index.IndexAction;
import dev.gruff.hardstop.cachegenie.graph.DotViz;
import dev.gruff.hardstop.cachegenie.graph.GraphRepository;
import dev.gruff.hardstop.cachegenie.graph.MetaRepository;
import dev.gruff.hardstop.cachegenie.utils.Progress;
import dev.gruff.hardstop.resolver.DependencySet;
import dev.gruff.hardstop.resolver.Resolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.sql.*;


@CommandLine.Command(name = "graph", aliases = {"map"}, description = "Produce graph of artifact dependencies ", subcommands = {GraphCmd.GraphCacheCmd.class, GraphCmd.GraphArtifact.class, GraphDepsCmd.class, GraphMineCmd.class, GraphResolveCmd.class, GraphImportCmd.class, GraphExportNeo4jCmd.class, GraphPushNeo4jCmd.class, GraphCmd.GraphQueryCmd.class, GraphCmd.GraphStatsCmd.class})
public class GraphCmd  {
    private static final Logger log = LoggerFactory.getLogger(GraphCmd.class);

    @CommandLine.ParentCommand
    RootCmd parent;

    @CommandLine.Command(name = "cache", description = "Produce combined graph of local cache entries ")
    public  static class  GraphCacheCmd implements Runnable {

        @CommandLine.ParentCommand
        GraphCmd parent;

        @Override
        public void run() {
            log.info("Graph cache: building combined dependency graph from local cache");

            CacheGenie cg = parent.parent.genie();
            CacheAction ca = new CacheAction(cg);
            GraphRepository gr = new GraphRepository(cg.cacheGenieRoot());
            Resolver r = Resolver.Builder(cg).build();
            Progress progress = Progress.start("Graph cache");

            // [0]=POMs read, [1]=parse-skipped, [2]=already in db,
            // [3]=resolve-failed, [4]=persisted, [5]=nodes, [6]=links
            long[] c = new long[7];

            ca.stream().forEach(pom -> {
                c[0]++;
                if (pom.status() != POMStatus.OK) {
                    c[1]++;
                    progress.tick(pom.artifact().value() + " [" + pom.status() + "]");
                    return;
                }

                var ref = pom.artifact();
                String gid = ref.groupID().value();
                String aid = ref.artifactID().value();
                String ver = ref.version().value();
                String gav = gid + ":" + aid + ":" + ver;
                progress.tick(gav);

                if (gr.isArtifactPresent(gid, aid, ver)) {
                    c[2]++;
                    return;
                }

                try {
                    DependencySet set = r.resolveGraph(gid, aid, ver);
                    if (set == null || set.getNodes().isEmpty()) {
                        c[3]++;
                        log.warn("Could not resolve dependency graph for {}", gav);
                        return;
                    }
                    gr.persist(set);
                    c[4]++;
                    c[5] += set.getNodes().size();
                    for (Set<DependencySet.Node> kids : set.getLinks().values()) {
                        if (kids != null) c[6] += kids.size();
                    }
                } catch (Exception e) {
                    c[3]++;
                    log.warn("Failed to resolve {}: {}", gav, e.getMessage());
                }
            });

            progress.done();

            System.out.printf(
                    "Graph cache complete: %d POMs scanned (%d malformed, %d already persisted), "
                  + "%d newly persisted, %d failed to resolve%n",
                    c[0], c[1], c[2], c[4], c[3]);
            System.out.printf("Added %d nodes and %d links%n", c[5], c[6]);
            System.out.println("Graph persisted to " + new File(cg.cacheGenieRoot(), "graph.db").getAbsolutePath());
        }

    }

    @CommandLine.Command(name = "artifact", description = "Produce graph of artifact ")

    public  static class GraphArtifact implements Runnable {

        @CommandLine.ParentCommand
        GraphCmd parent;

        @CommandLine.ArgGroup(exclusive = false, multiplicity = "1")
        DepOps depops;

        @CommandLine.Option(names = {"-f", "--format"}, required = false, paramLabel = "output format", description = "Output format")
        String format = "dot";


        @Override
        public void run() {
            log.info("Graph");

            CacheGenie cg = parent.parent.genie();
            IndexAction ia = new IndexAction(cg);

            String gid;
            String aid = null;
            String version = null;

            if (depops.gav != null) {
                String[] parts = depops.gav.split(":");
                gid = parts[0];
                if (parts.length > 1) aid = parts[1];
                if (parts.length > 2) version = parts[2];
            } else if (depops.gid != null) {
                gid = depops.gid;
                aid = depops.aid;
                if (depops.versionTargets != null && !depops.versionTargets.isEmpty()) {
                    version = depops.versionTargets.getFirst();
                }
            } else {
                throw new CommandLine.ParameterException(new CommandLine(this), "Missing required options: use either --gav or --group-id, --artifact-id and --version");
            }

            if (aid == null || aid.equals("*") || version == null || version.equals("*")) {
                processPattern(cg, ia, gid, aid, version);
                return;
            }

            MetaVersionSet versions = ia.versions(gid, aid);

            // do we have the version requested?
            if (!versions.hasVersion(version)) {
                System.err.println("Error: Can't locate version " + version + " for artifact " + gid + ":" + aid);
                System.err.println("Try running 'scan --gav " + gid + ":" + aid + "' first to discover available versions.");
                System.exit(1);
            }

            log.info("root {}", parent.parent.repo);
            log.info("cache {}", parent.parent.cache);
            log.info("gid {}", gid);
            log.info("aid {}", aid);
            log.info("version {}", version);
            
            GraphRepository gr = new GraphRepository(cg.cacheGenieRoot());
            if (gr.isArtifactPresent(gid, aid, version)) {
                System.out.println(gid + ":" + aid + ":" + version + " already in the db");
                System.exit(0);
            }

            Resolver r = Resolver.Builder(cg).build();

            DependencySet set = r.resolveGraph(gid, aid, version);

            if (set == null || set.getNodes().isEmpty()) {
                System.err.println("Error: Failed to resolve dependency graph for " + gid + ":" + aid + ":" + version);
                System.exit(1);
            }

            gr.persist(set);

            int nodes = set.getNodes().size();
            int linksCount = 0;
            for (Map.Entry<DependencySet.Node, Set<DependencySet.Node>> entry : set.getLinks().entrySet()) {
                DependencySet.Node parent = entry.getKey();
                for (DependencySet.Node kid : entry.getValue()) {
                    if (!(parent.gid.equals(kid.gid) && parent.aid.equals(kid.aid) && parent.ver.equals(kid.ver))) {
                        linksCount++;
                    }
                }
            }
            System.out.printf("Resolved %d nodes and %d links\n", nodes, linksCount);
            System.out.println("Graph persisted to " + new File(cg.cacheGenieRoot(), "graph.db").getAbsolutePath());

            if ("dot".equalsIgnoreCase(format)) {
                DotViz.viz(System.out, set);
            }

            System.exit(0);
        }

        private void processPattern(CacheGenie cg, IndexAction ia, String gid, String aid, String version) {
            log.info("Processing pattern {}:{}:{}", gid, aid, version);
            MetaRepository metaRepo = new MetaRepository(cg.cacheGenieRoot());
            String aidFilter = (aid == null || aid.equals("*")) ? null : aid;
            List<MavenMetaData> matched = metaRepo.loadByPattern(gid, aidFilter);

            if (matched.isEmpty()) {
                System.err.println("No metadata found matching pattern " + gid + ":" + (aid == null ? "*" : aid));
                return;
            }

            Resolver r = Resolver.Builder(cg).build();
            GraphRepository gr = new GraphRepository(cg.cacheGenieRoot());
            int totalNodes = 0;
            int totalLinks = 0;

            for (MavenMetaData m : matched) {
                Set<String> versionsToProcess = new java.util.HashSet<>();
                if (version == null || version.equals("*")) {
                    versionsToProcess.addAll(m.versions().stream().map(MavenMetaData.Version::value).collect(java.util.stream.Collectors.toList()));
                } else {
                    if (m.versions().hasVersion(version)) {
                        versionsToProcess.add(version);
                    }
                }

                for (String v : versionsToProcess) {
                    if (gr.isArtifactPresent(m.gid, m.aid, v)) {
                        System.out.println(m.gid + ":" + m.aid + ":" + v + " already in the db");
                        continue;
                    }
                    log.info("Resolving {}:{}:{}", m.gid, m.aid, v);
                    DependencySet set = r.resolveGraph(m.gid, m.aid, v);
                    if (set != null && !set.getNodes().isEmpty()) {
                        gr.persist(set);
                        totalNodes += set.getNodes().size();
                        // simplistic link count for report
                        for (Set<DependencySet.Node> kids : set.getLinks().values()) {
                            totalLinks += kids.size();
                        }
                        if ("dot".equalsIgnoreCase(format)) {
                            System.out.println("// Graph for " + m.gid + ":" + m.aid + ":" + v);
                            DotViz.viz(System.out, set);
                        }
                    }
                }
            }

            System.out.printf("Total Resolved Nodes: %d, Total Links Persisted: %d\n", totalNodes, totalLinks);
            System.out.println("Graphs persisted to " + new File(cg.cacheGenieRoot(), "graph.db").getAbsolutePath());
            System.exit(0);
        }
    }

    @CommandLine.Command(name = "query", description = "Query the graph database")
    public static class GraphQueryCmd implements Runnable {

        @CommandLine.ParentCommand
        GraphCmd parent;

        @CommandLine.Parameters(index = "0", description = "SQL query to execute", defaultValue = "SELECT * FROM artifacts LIMIT 10")
        String query;

        @CommandLine.Option(names = {"-w", "--write"}, description = "Open the database read-write (needed for INSERT/UPDATE/DDL). " +
                "By default 'query' opens read-only so it never takes a write lock; note that a read-write open cannot proceed while another process (e.g. 'graph deps') holds the database.")
        boolean write = false;

        @Override
        public void run() {
            CacheGenie cg = parent.parent.genie();
            File dbFile = new File(cg.cacheGenieRoot(), "graph.db");
            if (!dbFile.exists()) {
                System.out.println("Graph database not found at " + dbFile.getAbsolutePath());
                System.out.println("Run 'graph artifact' first to populate the database.");
                return;
            }

            // Default to a READ-ONLY open so an ad-hoc query never takes a write
            // lock (and can run while the DB is otherwise idle). Schema migrations
            // require a writer, so only run them on the --write path; a read-only
            // query against an older DB simply sees whatever columns exist.
            java.util.Properties props = new java.util.Properties();
            if (write) {
                // Ensure both schemas (incl. any column migrations) exist so an
                // ad-hoc query doesn't trip on a DB created before a column was added.
                new GraphRepository(cg.cacheGenieRoot());
                new MetaRepository(cg.cacheGenieRoot());
            } else {
                props.setProperty("duckdb.read_only", "true");
            }

            log.debug("Executing query ({}): {}", write ? "read-write" : "read-only", query);
            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:" + dbFile.getAbsolutePath(), props);
                 Statement stmt = conn.createStatement()) {

                boolean hasResultSet = stmt.execute(query);
                if (hasResultSet) {
                    try (ResultSet rs = stmt.getResultSet()) {
                        ResultSetMetaData metaData = rs.getMetaData();
                        int columnCount = metaData.getColumnCount();

                        // Print headers
                        for (int i = 1; i <= columnCount; i++) {
                            System.out.print(metaData.getColumnName(i) + (i < columnCount ? "\t" : ""));
                        }
                        System.out.println();
                        System.out.println("-".repeat(columnCount * 10));

                        // Print rows
                        boolean hasRows = false;
                        while (rs.next()) {
                            hasRows = true;
                            for (int i = 1; i <= columnCount; i++) {
                                System.out.print(rs.getString(i) + (i < columnCount ? "\t" : ""));
                            }
                            System.out.println();
                        }

                        if (!hasRows) {
                            System.out.println("(No results found)");
                        }
                    }
                } else {
                    int updateCount = stmt.getUpdateCount();
                    System.out.println("Query executed successfully. Update count: " + updateCount);
                }

            } catch (SQLException e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("Conflicting lock") || msg.contains("Could not set lock"))) {
                    System.err.println("graph.db is locked by another CacheGenie process (most likely a running 'graph deps').");
                    if (write) {
                        System.err.println("A read-write query cannot attach while it is held; drop --write to query read-only,");
                        System.err.println("or wait for that run to finish.");
                    } else {
                        System.err.println("DuckDB cannot attach (even read-only) while another process holds it read-write;");
                        System.err.println("wait for that run to finish.");
                    }
                } else {
                    System.err.println("SQL Error: " + msg);
                }
                log.error("Failed to execute query", e);
            }
        }
    }

    @CommandLine.Command(name = "stats", description = "Print statistics about the graph and discovery metadata")
    public static class GraphStatsCmd implements Runnable {

        @CommandLine.ParentCommand
        GraphCmd parent;

        @Override
        public void run() {
            CacheGenie cg = parent.parent.genie();
            File dbFile = new File(cg.cacheGenieRoot(), "graph.db");
            if (!dbFile.exists()) {
                System.out.println("Graph database not found at " + dbFile.getAbsolutePath());
                System.out.println("Run 'graph artifact' first to populate the database.");
                return;
            }

            // Open READ-ONLY. 'stats' is a pure reader, so it must not take a
            // write lock - that would block, and be blocked by, a running
            // 'graph deps'. The trade-off is that we cannot run schema
            // migrations here, so we tolerate absent tables/columns instead: a
            // DB created by only 'scan' has just the meta tables; one created by
            // only 'graph artifact/cache' has just the graph tables.
            //
            // NOTE: DuckDB still refuses to open a file (even read-only) while
            // another process holds it read-write, so this does not let 'stats'
            // run *during* a 'graph deps' run - it just avoids 'stats' itself
            // ever taking a write lock. See the lock-conflict message below.
            java.util.Properties props = new java.util.Properties();
            props.setProperty("duckdb.read_only", "true");

            System.out.println("Graph Database Statistics");
            System.out.println("-------------------------");
            System.out.println("Location: " + dbFile.getAbsolutePath());
            System.out.println("Size: " + (dbFile.length() / 1024) + " KB");

            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:" + dbFile.getAbsolutePath(), props);
                 Statement stmt = conn.createStatement()) {

                graphStats(stmt);
                metaStats(stmt);

            } catch (SQLException e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("Conflicting lock") || msg.contains("Could not set lock"))) {
                    System.err.println();
                    System.err.println("graph.db is locked by another CacheGenie process (most likely a running 'graph deps').");
                    System.err.println("DuckDB permits only a single read-write process and no concurrent access while it is");
                    System.err.println("held, so 'stats' cannot attach until that run finishes.");
                } else {
                    System.err.println("SQL Error: " + msg);
                }
                log.error("Failed to gather statistics", e);
            }
        }

        /** Graph-table stats. Prints a note and returns if the graph tables aren't present. */
        private static void graphStats(Statement stmt) {
            try {
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM artifacts")) {
                    if (rs.next()) System.out.println("Total Artifacts: " + rs.getLong(1));
                }

                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM dependencies")) {
                    if (rs.next()) System.out.println("Total Dependency Links: " + rs.getLong(1));
                }

                System.out.println("\nTop 5 Artifacts by Number of Dependencies:");
                String topOutQuery = "SELECT a.gid, a.aid, a.version, COUNT(d.child_id) as count " +
                        "FROM artifacts a JOIN dependencies d ON a.id = d.parent_id " +
                        "GROUP BY a.gid, a.aid, a.version ORDER BY count DESC LIMIT 5";
                try (ResultSet rs = stmt.executeQuery(topOutQuery)) {
                    while (rs.next()) {
                        System.out.printf("  %s:%s:%s -> %d\n", rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4));
                    }
                }

                System.out.println("\nTop 5 Most Depended-upon Artifacts:");
                String topInQuery = "SELECT a.gid, a.aid, a.version, COUNT(d.parent_id) as count " +
                        "FROM artifacts a JOIN dependencies d ON a.id = d.child_id " +
                        "GROUP BY a.gid, a.aid, a.version ORDER BY count DESC LIMIT 5";
                try (ResultSet rs = stmt.executeQuery(topInQuery)) {
                    while (rs.next()) {
                        System.out.printf("  %s:%s:%s -> %d\n", rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4));
                    }
                }

                System.out.println("\nDependency Scope Distribution:");
                try (ResultSet rs = stmt.executeQuery("SELECT scope, COUNT(*) FROM dependencies GROUP BY scope ORDER BY COUNT(*) DESC")) {
                    while (rs.next()) {
                        String scope = rs.getString(1);
                        if (scope == null || scope.isEmpty()) scope = "(none)";
                        System.out.printf("  %-12s: %d\n", scope, rs.getLong(2));
                    }
                }
            } catch (SQLException e) {
                System.out.println("\n(graph tables not present -- run 'graph deps' to populate)");
            }
        }

        /** Metadata (discovery) stats, populated by 'scan'. Prints a note and returns if absent. */
        private static void metaStats(Statement stmt) {
            System.out.println("\nMetadata Statistics");
            System.out.println("-------------------");
            try {
                long metaArtifacts = 0;
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM meta_artifacts")) {
                    if (rs.next()) metaArtifacts = rs.getLong(1);
                }
                System.out.println("Tracked group:artifacts: " + metaArtifacts);

                long metaVersions = 0;
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM meta_versions")) {
                    if (rs.next()) metaVersions = rs.getLong(1);
                }
                System.out.println("Discovered versions: " + metaVersions);

                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM meta_versions WHERE missing_pom = TRUE")) {
                    if (rs.next()) System.out.println("Versions with missing POMs: " + rs.getLong(1));
                }

                if (metaArtifacts > 0) {
                    System.out.printf("Average versions per artifact: %.1f%n",
                            metaVersions / (double) metaArtifacts);
                }

                System.out.println("\nTop 5 Artifacts by Number of Versions:");
                String topVersions = "SELECT a.gid, a.aid, COUNT(v.version) as count " +
                        "FROM meta_artifacts a JOIN meta_versions v ON a.id = v.ga_id " +
                        "GROUP BY a.gid, a.aid ORDER BY count DESC LIMIT 5";
                try (ResultSet rs = stmt.executeQuery(topVersions)) {
                    while (rs.next()) {
                        System.out.printf("  %s:%s -> %d versions\n", rs.getString(1), rs.getString(2), rs.getLong(3));
                    }
                }

                System.out.println("\nTop 5 Artifacts by Missing POMs:");
                String topMissing = "SELECT a.gid, a.aid, COUNT(*) as count " +
                        "FROM meta_artifacts a JOIN meta_versions v ON a.id = v.ga_id " +
                        "WHERE v.missing_pom = TRUE " +
                        "GROUP BY a.gid, a.aid ORDER BY count DESC LIMIT 5";
                try (ResultSet rs = stmt.executeQuery(topMissing)) {
                    boolean any = false;
                    while (rs.next()) {
                        any = true;
                        System.out.printf("  %s:%s -> %d missing\n", rs.getString(1), rs.getString(2), rs.getLong(3));
                    }
                    if (!any) System.out.println("  (none)");
                }
            } catch (SQLException e) {
                System.out.println("(meta tables not present -- run 'scan' to populate)");
            }
        }
    }
}
