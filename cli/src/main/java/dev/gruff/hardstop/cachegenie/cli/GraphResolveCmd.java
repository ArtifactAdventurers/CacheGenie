package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.graph.PomResolver;
import dev.gruff.hardstop.cachegenie.graph.Sqlite;
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
            description = "Reader workers for the per-node resolve (default 8). Each worker gets its own "
                    + "read-only connection (WAL lets them run alongside the writer); writes still funnel "
                    + "through a single writer connection. The work is indexed-read-bound, so this is the "
                    + "knob that matters. There is no network here, hence no --rate.")
    int threads = 8;

    @Override
    public void run() {
        CacheGenie cg = parent.parent.genie();
        File db = new File(Sqlite.dbPath(cg.cacheGenieRoot()));
        if (!db.exists()) {
            System.out.println("Graph database not found at " + db.getAbsolutePath());
            System.out.println("Run 'graph mine' first to populate the POM tables.");
            System.exit(1);
        }

        System.out.printf("Resolving mined POMs%s with %d worker(s) — use -P for progress, -l info for logging.%n",
                all ? " (--all)" : "", Math.max(1, threads));
        PomResolver resolver = new PomResolver(cg.cacheGenieRoot());
        PomResolver.ResolveStats s = resolver.resolveAll(all, threads);

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
