package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.graph.GraphRepository;
import dev.gruff.hardstop.cachegenie.graph.MetaRepository;
import dev.gruff.hardstop.cachegenie.graph.Sqlite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.*;


@CommandLine.Command(name = "graph", aliases = {"map"}, description = "Build, resolve, and query the dependency graph (mine POMs, resolve edges, run SQL)", subcommands = {GraphDepsCmd.class, GraphMineCmd.class, GraphResolveCmd.class, GraphImportCmd.class, GraphExportNeo4jCmd.class, GraphPushNeo4jCmd.class, GraphCmd.GraphQueryCmd.class, GraphCmd.GraphStatsCmd.class})
public class GraphCmd  {
    private static final Logger log = LoggerFactory.getLogger(GraphCmd.class);

    @CommandLine.ParentCommand
    RootCmd parent;

    @CommandLine.Command(name = "query", description = "Run SQL (SQLite dialect, incl. recursive CTEs for transitive deps) against the graph database")
    public static class GraphQueryCmd implements Runnable {

        @CommandLine.ParentCommand
        GraphCmd parent;

        @CommandLine.Parameters(index = "0", arity = "0..1",
                description = "SQL query to execute (omit when using -f/--file)")
        String query;

        @CommandLine.Option(names = {"-f", "--file"}, paramLabel = "FILE",
                description = "Read the SQL query from a file instead of the command line " +
                        "(handy for long or multi-line ad-hoc queries)")
        File queryFile;

        @CommandLine.Option(names = {"-w", "--write"}, description = "Open the database read-write (needed for INSERT/UPDATE/DDL). " +
                "By default 'query' opens read-only.")
        boolean write = false;

        @Override
        public void run() {
            CacheGenie cg = parent.parent.genie();
            String dbPath = Sqlite.dbPath(cg.cacheGenieRoot());
            File dbFile = new File(dbPath);
            if (!dbFile.exists()) {
                System.out.println("Graph database not found at " + dbFile.getAbsolutePath());
                System.out.println("Run 'graph mine' + 'graph resolve' first to populate the database.");
                return;
            }

            String sql;
            if (queryFile != null) {
                if (query != null) {
                    System.err.println("Specify either a SQL query argument or -f/--file, not both.");
                    return;
                }
                try {
                    sql = Files.readString(queryFile.toPath()).trim();
                } catch (IOException e) {
                    System.err.println("Failed to read SQL file " + queryFile + ": " + e.getMessage());
                    return;
                }
                if (sql.isEmpty()) {
                    System.err.println("SQL file " + queryFile + " is empty.");
                    return;
                }
            } else {
                sql = (query == null || query.isBlank()) ? "SELECT * FROM artifacts LIMIT 10" : query;
            }

            // Default to a READ-ONLY open so an ad-hoc query can never mutate the
            // database by accident. Schema migrations require a writer, so only
            // run them on the --write path; a read-only query against an older DB
            // simply sees whatever columns exist.
            if (write) {
                // Ensure both schemas (incl. any column migrations) exist so an
                // ad-hoc query doesn't trip on a DB created before a column was added.
                new GraphRepository(cg.cacheGenieRoot());
                new MetaRepository(cg.cacheGenieRoot());
            }

            log.debug("Executing query ({}): {}", write ? "read-write" : "read-only", sql);
            try (Connection conn = write ? Sqlite.open(dbPath) : Sqlite.openReadOnly(dbPath);
                 Statement stmt = conn.createStatement()) {

                boolean hasResultSet = stmt.execute(sql);
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

    @CommandLine.Command(name = "stats", description = "Print statistics about the graph and discovery metadata")
    public static class GraphStatsCmd implements Runnable {

        @CommandLine.ParentCommand
        GraphCmd parent;

        @Override
        public void run() {
            CacheGenie cg = parent.parent.genie();
            String dbPath = Sqlite.dbPath(cg.cacheGenieRoot());
            File dbFile = new File(dbPath);
            if (!dbFile.exists()) {
                System.out.println("Graph database not found at " + dbFile.getAbsolutePath());
                System.out.println("Run 'graph mine' + 'graph resolve' first to populate the database.");
                return;
            }

            // Open READ-ONLY. 'stats' is a pure reader; under WAL it runs happily
            // alongside a concurrent writer (e.g. 'graph deps'). The trade-off is
            // that we cannot run schema migrations here, so we tolerate absent
            // tables/columns instead: a DB created by only 'scan' has just the
            // meta tables; one created by only 'graph deps'/'mine' has just the
            // graph tables.
            System.out.println("Graph Database Statistics");
            System.out.println("-------------------------");
            System.out.println("Location: " + dbFile.getAbsolutePath());
            System.out.println("Size: " + (dbFile.length() / 1024) + " KB");

            try (Connection conn = Sqlite.openReadOnly(dbPath);
                 Statement stmt = conn.createStatement()) {

                graphStats(stmt);
                metaStats(stmt);

            } catch (SQLException e) {
                System.err.println("SQL Error: " + e.getMessage());
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
                System.out.println("\n(graph tables not present -- run 'graph deps' or 'graph mine'+'graph resolve' to populate)");
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
