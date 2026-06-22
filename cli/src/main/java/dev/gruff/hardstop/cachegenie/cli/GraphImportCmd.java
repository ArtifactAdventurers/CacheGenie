package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.graph.GraphRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;

/**
 * Seed the dependency graph from a Goblin export CSV instead of crawling/mining.
 * Goblin's edges are Aether-resolved effective direct dependencies (the same data
 * {@code graph deps} produces), pre-computed for all of Maven Central up to the
 * dataset's snapshot date — so this loads the bulk graph in minutes with zero load
 * on Maven Central. Keep current afterwards with {@code graph mine --since <date>}.
 *
 * <p>Input is the CSV exported from the Goblin Neo4j dump (see {@code GOBLIN-IMPORT.md}),
 * header {@code source,targetArtifact,targetVersion,scope}. The load is set-based in
 * DuckDB and idempotent.
 */
@CommandLine.Command(name = "import-goblin",
        description = "Seed artifacts/dependencies from a Goblin export CSV (resolved edges; bulk-load, no Central traffic). See GOBLIN-IMPORT.md.")
public class GraphImportCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(GraphImportCmd.class);

    @CommandLine.ParentCommand
    GraphCmd parent;

    @CommandLine.Option(names = {"--edges"}, required = true, paramLabel = "<file.csv>",
            description = "Goblin dependency-edge CSV (columns: source,targetArtifact,targetVersion,scope).")
    File edges;

    @CommandLine.Option(names = {"--include-ranges"},
            description = "Also import edges whose targetVersion is a version range/property token (skipped by default; their child release does not exist as a concrete node).")
    boolean includeRanges;

    @Override
    public void run() {
        if (!edges.isFile()) {
            System.err.println("Edges CSV not found: " + edges.getAbsolutePath());
            System.exit(1);
        }
        CacheGenie cg = parent.parent.genie();
        GraphRepository gr = new GraphRepository(cg.cacheGenieRoot());

        log.info("Importing Goblin edges from {}{}", edges, includeRanges ? " (including ranges)" : "");
        GraphRepository.GoblinImportStats s = gr.importGoblinEdges(edges, includeRanges);

        System.out.printf("Goblin import complete: %d artifact node(s) added, %d edge(s) added "
                        + "(%d rows read, %d skipped as range/property versions).%n",
                s.artifactsAdded(), s.edgesAdded(), s.rowsRead(), s.rowsSkipped());
        System.out.println("Next: keep the graph current with 'graph mine --since <dataset-snapshot-date>' "
                + "then 'graph resolve' (and 'db optimize' to index).");
        System.exit(0);
    }
}
