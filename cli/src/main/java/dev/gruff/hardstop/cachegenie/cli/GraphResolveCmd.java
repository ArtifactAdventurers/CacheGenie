package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.graph.PomResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;

/**
 * Resolve mined POMs (from {@code graph mine}) into concrete dependency edges:
 * walk the parent chain and import-scope BOMs to fill managed versions, interpolate
 * {@code ${...}} properties, and write the resolvable direct edges into the
 * {@code dependencies} table. Idempotent; only touches nodes not yet resolved
 * unless {@code --all} is given.
 *
 * <p>First-cut resolver — see {@link PomResolver} for the cases it does and does not
 * handle. Transitive trees remain a recursive-CTE query over {@code dependencies}.
 */
@CommandLine.Command(name = "resolve",
        description = "Resolve mined POMs into concrete dependency edges (parent/BOM/property resolution); run after 'graph mine'")
public class GraphResolveCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(GraphResolveCmd.class);

    @CommandLine.ParentCommand
    GraphCmd parent;

    @CommandLine.Option(names = {"--all", "--force"},
            description = "Re-resolve every mined node, not just those not yet resolved.")
    boolean all;

    @CommandLine.Option(names = {"--threads"}, paramLabel = "<n>",
            description = "Reader workers for the per-node phase (default 8). Writes still funnel "
                    + "through a single writer (DuckDB single-writer); the per-node phase is read-bound, "
                    + "so this is the knob that matters there. There is no network here, hence no --rate.")
    int threads = 8;

    @CommandLine.Option(names = {"--set-based-only"},
            description = "Run only the fast set-based passes (literal, same-POM, parent-chain) and skip "
                    + "the per-node pass. Use this while the per-node residue (import BOMs, embedded tokens) "
                    + "is still scan-bound; re-run without the flag once that residue is small.")
    boolean setBasedOnly;

    @CommandLine.Option(names = {"--mem-limit"}, paramLabel = "<size>",
            description = "Cap DuckDB's memory (e.g. 8GB) for the whole resolve so large intermediates "
                    + "spill to disk and leave headroom for the JVM. Strongly recommended on a full-Central "
                    + "graph: the inherited pass otherwise OOMs. Default: DuckDB's own (~80% of RAM).")
    String memLimit;

    @CommandLine.Option(names = {"--inherited-batch"}, paramLabel = "<n>",
            description = "Nodes per batch in the parent-chain set-based pass (default 25000). Lower it if "
                    + "the inherited pass still runs out of memory (peak memory scales with each batch's "
                    + "ancestor-property fan-out, not just the node count); raise it for speed on a big box.")
    int inheritedBatch;

    @CommandLine.Option(names = {"--db-threads"}, paramLabel = "<n>",
            description = "Cap DuckDB's own worker threads for the SQL passes (default: one per core). "
                    + "The set-based passes build a hash table per thread for their big joins/aggregates, "
                    + "so peak memory scales with this — lower it (e.g. 4) to trade speed for a smaller "
                    + "footprint if resolve still OOMs. Distinct from --threads (the per-node reader pool).")
    int dbThreads;

    @Override
    public void run() {
        CacheGenie cg = parent.parent.genie();
        File db = new File(cg.cacheGenieRoot(), "graph.db");
        if (!db.exists()) {
            System.out.println("Graph database not found at " + db.getAbsolutePath());
            System.out.println("Run 'graph mine' first to populate the POM tables.");
            System.exit(1);
        }

        System.out.printf("Resolving mined POMs%s%s with %d worker(s) — use -P for progress, -l info for logging.%n",
                all ? " (--all)" : "", setBasedOnly ? " (set-based only)" : "", Math.max(1, threads));
        PomResolver resolver = new PomResolver(cg.cacheGenieRoot(), memLimit, inheritedBatch, dbThreads);
        PomResolver.ResolveStats s = resolver.resolveAll(all, threads, setBasedOnly);

        if (s.nodes() == 0) {
            System.out.println("Nothing to resolve. 'graph resolve' works on mined POMs that aren't resolved yet — "
                    + "run 'graph mine' first, or pass --all to re-resolve everything already mined.");
            System.exit(0);
        }

        System.out.printf("Resolve complete: %d node(s) resolved, %d edge(s) written, %d dependency version(s) unresolved.%n",
                s.nodes(), s.edges(), s.unresolved());
        if (s.unresolved() > 0) {
            System.out.println("Unresolved deps are usually versions managed by a parent/BOM not yet mined, "
                    + "property tokens with no definition, or version ranges. Mine more, then re-run.");
        }
        System.exit(0);
    }
}
