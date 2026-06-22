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

    @Override
    public void run() {
        CacheGenie cg = parent.parent.genie();
        File db = new File(cg.cacheGenieRoot(), "graph.db");
        if (!db.exists()) {
            System.out.println("Graph database not found at " + db.getAbsolutePath());
            System.out.println("Run 'graph mine' first to populate the POM tables.");
            System.exit(1);
        }

        System.out.println("Resolving mined POMs" + (all ? " (--all)" : "") + " — use -P for progress, -l info for logging.");
        PomResolver resolver = new PomResolver(cg.cacheGenieRoot());
        PomResolver.ResolveStats s = resolver.resolveAll(all);

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
