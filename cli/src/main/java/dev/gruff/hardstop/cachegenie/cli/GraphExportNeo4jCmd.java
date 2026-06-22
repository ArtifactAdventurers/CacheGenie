package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.graph.GraphRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;

/**
 * Export the DuckDB graph as {@code neo4j-admin import} CSVs in Goblin's schema, for
 * the hybrid model: DuckDB stays the system of record (ingest, mining, analytics,
 * the relational meta tables), and Neo4j becomes an optional read-side for graph
 * traversal / Weaver interop. Because the output matches Goblin's node/edge model,
 * the exported graph is queryable with the same Cypher and conceptually mergeable
 * with a loaded Goblin dump. See {@code HYBRID-NEO4J.md}.
 */
@CommandLine.Command(name = "export-neo4j",
        description = "Export the graph as neo4j-admin import CSVs (Goblin schema) for an optional Neo4j read-side. See HYBRID-NEO4J.md.")
public class GraphExportNeo4jCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(GraphExportNeo4jCmd.class);

    @CommandLine.ParentCommand
    GraphCmd parent;

    @CommandLine.Option(names = {"-o", "--out"}, paramLabel = "<dir>",
            description = "Output directory for the CSVs (default <cachegenie>/neo4j-export).")
    File out;

    @CommandLine.Option(names = {"--with-metadata"},
            description = "Add name/url/scmUrl properties to Release nodes from pom_meta (run 'graph mine' first to populate).")
    boolean withMetadata;

    @Override
    public void run() {
        CacheGenie cg = parent.parent.genie();
        File dbFile = new File(cg.cacheGenieRoot(), "graph.db");
        if (!dbFile.exists()) {
            System.out.println("Graph database not found at " + dbFile.getAbsolutePath());
            System.out.println("Populate it first (graph import-goblin / graph deps / graph mine + resolve).");
            System.exit(1);
        }
        File dir = (out != null) ? out : new File(cg.cacheGenieRoot(), "neo4j-export");

        GraphRepository gr = new GraphRepository(cg.cacheGenieRoot());
        GraphRepository.Neo4jExportStats s = gr.exportNeo4jCsv(dir, withMetadata);

        System.out.printf("Exported %d release node(s), %d library node(s), %d dependency edge(s) to %s%n",
                s.releases(), s.libraries(), s.edges(), dir.getAbsolutePath());
        System.out.println();
        System.out.println("Bulk-load into a fresh database. Neo4j 5.x / 2025-2026 (CalVer) syntax:");
        System.out.println("  neo4j-admin database import full neo4j \\");
        System.out.println("    --nodes=Release=" + new File(dir, "releases.csv").getAbsolutePath() + " \\");
        System.out.println("    --nodes=Artifact=" + new File(dir, "libraries.csv").getAbsolutePath() + " \\");
        System.out.println("    --relationships=relationship_AR=" + new File(dir, "rel_ar.csv").getAbsolutePath() + " \\");
        System.out.println("    --relationships=dependency=" + new File(dir, "deps.csv").getAbsolutePath() + " \\");
        System.out.println("    --id-type=string --overwrite-destination");
        System.out.println("(Neo4j 4.x uses the older 'neo4j-admin import --database=neo4j ...' form.)");
        System.out.println("(To merge into an EXISTING graph instead, use LOAD CSV + MERGE, or 'graph push-neo4j' — see HYBRID-NEO4J.md.)");
        System.exit(0);
    }
}
