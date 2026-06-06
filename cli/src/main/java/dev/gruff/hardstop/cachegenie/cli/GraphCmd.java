package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.MavenMetaData;
import dev.gruff.hardstop.cachegenie.MetaVersionSet;
import dev.gruff.hardstop.cachegenie.actions.CacheAction;
import dev.gruff.hardstop.cachegenie.actions.index.IndexAction;
import dev.gruff.hardstop.cachegenie.graph.DotViz;
import dev.gruff.hardstop.cachegenie.graph.GraphRepository;
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


@CommandLine.Command(name = "graph", aliases = {"map"}, description = "Produce graph of artifact dependencies ", subcommands = {GraphCmd.GraphCacheCmd.class, GraphCmd.GraphArtifact.class, GraphCmd.GraphQueryCmd.class, GraphCmd.GraphStatsCmd.class})
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
            log.info("Graph");

            CacheGenie cg = parent.parent.genie();
            CacheAction ca=new CacheAction(cg);
            ca.stream().forEach(f -> {
                log.info(f.artifact().value());
            });

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
            File metaDir = new File(cg.cacheGenieRoot(), "meta");
            List<MavenMetaData> matched = new java.util.ArrayList<>();

            if (metaDir.exists()) {
                // New JSON structure
                String groupPath = gid.replace('.', '/');
                File groupDir = new File(metaDir, groupPath);
                if (groupDir.exists()) {
                    if (aid == null || aid.equals("*")) {
                        // Scan all artifacts in group
                        File[] artifactDirs = groupDir.listFiles(File::isDirectory);
                        if (artifactDirs != null) {
                            for (File ad : artifactDirs) {
                                MavenMetaData m = loadMeta(ad);
                                if (m != null) matched.add(m);
                            }
                        }
                    } else {
                        File artifactDir = new File(groupDir, aid);
                        if (artifactDir.exists()) {
                            MavenMetaData m = loadMeta(artifactDir);
                            if (m != null) matched.add(m);
                        }
                    }
                }
            }

            // Legacy properties files
            File[] legacyFiles = cg.cacheGenieRoot().listFiles((dir, name) -> name.endsWith(".properties"));
            if (legacyFiles != null) {
                for (File f : legacyFiles) {
                    String name = f.getName();
                    String ga = name.substring(0, name.length() - 11); // remove .properties
                    String[] parts = ga.split(":");
                    if (parts.length == 2) {
                        if (parts[0].equals(gid)) {
                            if (aid == null || aid.equals("*") || parts[1].equals(aid)) {
                                // check if already added from JSON
                                boolean exists = matched.stream().anyMatch(m -> m.gid.equals(parts[0]) && m.aid.equals(parts[1]));
                                if (!exists) {
                                    matched.add(MavenMetaData.load(f));
                                }
                            }
                        }
                    }
                }
            }

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

        private MavenMetaData loadMeta(File artifactDir) {
            File jsonFile = new File(artifactDir, "metadata.json");
            if (jsonFile.exists()) {
                return MavenMetaData.loadJSON(jsonFile);
            }
            return null;
        }
    }

    @CommandLine.Command(name = "query", description = "Query the graph database")
    public static class GraphQueryCmd implements Runnable {

        @CommandLine.ParentCommand
        GraphCmd parent;

        @CommandLine.Parameters(index = "0", description = "SQL query to execute", defaultValue = "SELECT * FROM artifacts LIMIT 10")
        String query;

        @Override
        public void run() {
            CacheGenie cg = parent.parent.genie();
            File dbFile = new File(cg.cacheGenieRoot(), "graph.db");
            if (!dbFile.exists()) {
                System.out.println("Graph database not found at " + dbFile.getAbsolutePath());
                System.out.println("Run 'graph artifact' first to populate the database.");
                return;
            }

            log.debug("Executing query: {}", query);
            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:" + dbFile.getAbsolutePath());
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
                System.err.println("SQL Error: " + e.getMessage());
                log.error("Failed to execute query", e);
            }
        }
    }

    @CommandLine.Command(name = "stats", description = "Print statistics about the graph database")
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

            System.out.println("Graph Database Statistics");
            System.out.println("-------------------------");
            System.out.println("Location: " + dbFile.getAbsolutePath());
            System.out.println("Size: " + (dbFile.length() / 1024) + " KB");

            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:" + dbFile.getAbsolutePath());
                 Statement stmt = conn.createStatement()) {

                // Total Artifacts
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM artifacts")) {
                    if (rs.next()) {
                        System.out.println("Total Artifacts: " + rs.getLong(1));
                    }
                }

                // Total Dependencies
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM dependencies")) {
                    if (rs.next()) {
                        System.out.println("Total Dependency Links: " + rs.getLong(1));
                    }
                }

                // Top 5 Artifacts by Out-degree (Dependencies)
                System.out.println("\nTop 5 Artifacts by Number of Dependencies:");
                String topOutQuery = "SELECT a.gid, a.aid, a.version, COUNT(d.child_id) as count " +
                        "FROM artifacts a JOIN dependencies d ON a.id = d.parent_id " +
                        "GROUP BY a.gid, a.aid, a.version ORDER BY count DESC LIMIT 5";
                try (ResultSet rs = stmt.executeQuery(topOutQuery)) {
                    while (rs.next()) {
                        System.out.printf("  %s:%s:%s -> %d\n", rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4));
                    }
                }

                // Top 5 Artifacts by In-degree (Dependents)
                System.out.println("\nTop 5 Most Depended-upon Artifacts:");
                String topInQuery = "SELECT a.gid, a.aid, a.version, COUNT(d.parent_id) as count " +
                        "FROM artifacts a JOIN dependencies d ON a.id = d.child_id " +
                        "GROUP BY a.gid, a.aid, a.version ORDER BY count DESC LIMIT 5";
                try (ResultSet rs = stmt.executeQuery(topInQuery)) {
                    while (rs.next()) {
                        System.out.printf("  %s:%s:%s -> %d\n", rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4));
                    }
                }

                // Scope Distribution
                System.out.println("\nDependency Scope Distribution:");
                try (ResultSet rs = stmt.executeQuery("SELECT scope, COUNT(*) FROM dependencies GROUP BY scope ORDER BY COUNT(*) DESC")) {
                    while (rs.next()) {
                        String scope = rs.getString(1);
                        if (scope == null || scope.isEmpty()) scope = "(none)";
                        System.out.printf("  %-12s: %d\n", scope, rs.getLong(2));
                    }
                }

            } catch (SQLException e) {
                System.err.println("SQL Error: " + e.getMessage());
                log.error("Failed to gather statistics", e);
            }
        }
    }
}
