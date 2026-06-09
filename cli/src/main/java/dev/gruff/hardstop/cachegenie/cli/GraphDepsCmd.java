package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.MavenMetaData;
import dev.gruff.hardstop.cachegenie.graph.GraphRepository;
import dev.gruff.hardstop.cachegenie.graph.MetaRepository;
import dev.gruff.hardstop.cachegenie.utils.Progress;
import dev.gruff.hardstop.resolver.Resolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.util.List;

/**
 * Build the <em>direct</em>-dependency graph for a targeted set of coordinates,
 * driven from the discovery catalogue ({@code meta_*} tables). For each selected
 * version it reads the effective direct dependencies (one descriptor read, no
 * transitive collection) and persists the edges; transitive trees are then a
 * recursive-CTE query. Skips versions already graphed and versions with a known
 * missing POM. Sequential for now (targeted scope is small).
 */
@CommandLine.Command(name = "deps",
        description = "Build the direct-dependency graph for targeted coordinates from the catalogue (transitive trees are then queryable via recursive SQL)")
public class GraphDepsCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(GraphDepsCmd.class);

    @CommandLine.ParentCommand
    GraphCmd parent;

    @CommandLine.Option(names = {"-gav", "--gav"}, arity = "1..*", required = true, paramLabel = "<selector>",
            description = "group, group:artifact, or group:artifact:version selectors (artifact/version may be '*' or omitted for all).")
    List<String> gavs;

    @Override
    public void run() {
        CacheGenie cg = parent.parent.genie();
        MetaRepository metaRepo = new MetaRepository(cg.cacheGenieRoot());
        GraphRepository gr = new GraphRepository(cg.cacheGenieRoot());
        Resolver resolver = Resolver.Builder(cg).build();
        Progress progress = Progress.start("Graph deps");

        long graphed = 0, edges = 0, skippedPresent = 0, skippedMissing = 0;
        for (String sel : gavs) {
            String[] parts = sel.trim().split(":");
            String gid = parts[0];
            String aidFilter = (parts.length > 1 && !parts[1].equals("*")) ? parts[1] : null;
            String verFilter = (parts.length > 2 && !parts[2].equals("*")) ? parts[2] : null;

            List<MavenMetaData> matched = metaRepo.loadByPattern(gid, aidFilter);
            if (matched.isEmpty()) {
                System.err.println("No catalogue entries matching " + sel);
                continue;
            }
            for (MavenMetaData m : matched) {
                for (String v : m.versions.keySet()) {
                    if (verFilter != null && !verFilter.equals(v)) continue;
                    if (m.isMissingPom(v)) { skippedMissing++; continue; }
                    if (gr.isArtifactPresent(m.gid, m.aid, v)) { skippedPresent++; continue; }

                    String gav = m.gid + ":" + m.aid + ":" + v;
                    progress.tick(gav);
                    List<Resolver.DirectDep> deps = resolver.directDependencies(gav);
                    if (deps == null) { skippedMissing++; continue; }
                    gr.persistDirect(m.gid, m.aid, v, deps);
                    graphed++;
                    edges += deps.size();
                }
            }
        }
        progress.done();
        System.out.printf(
                "Graph deps complete: %d artifacts graphed (%d direct edges); %d already graphed, %d skipped (missing POM / read failed)%n",
                graphed, edges, skippedPresent, skippedMissing);
        System.exit(0);
    }
}
