package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.Meta;
import dev.gruff.hardstop.cachegenie.MetaVersionSet;
import dev.gruff.hardstop.cachegenie.actions.CacheAction;
import dev.gruff.hardstop.cachegenie.actions.index.IndexAction;
import dev.gruff.hardstop.cachegenie.graph.DotViz;
import dev.gruff.hardstop.resolver.DependencySet;
import dev.gruff.hardstop.resolver.Resolver;
import picocli.CommandLine;


@CommandLine.Command(name = "graph", description = "Produce graph of artifact dependencies ", subcommands = {GraphCmd.GraphCacheCmd.class, GraphCmd.GraphArtifact.class})
public class GraphCmd  {

    @CommandLine.ParentCommand
    RootCmd parent;

    @CommandLine.Command(name = "cache", description = "Produce combined graph of local cache entries ")
    public  static class  GraphCacheCmd implements Runnable {

        @CommandLine.ParentCommand
        GraphCmd parent;

        @Override
        public void run() {
            System.out.println("Graph");

            CacheGenie cg = parent.parent.genie();
            CacheAction ca=new CacheAction(cg);
            ca.stream().forEach(f -> {
                System.out.println(f.artifact().value());
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
            System.out.println("Graph");

            CacheGenie cg = parent.parent.genie();
            IndexAction ia = new IndexAction(cg);

            MetaVersionSet versions = ia.versions(depops.gid, depops.aid);

            // do we have the versions requested?
            boolean failed = false;
            for (String vr : depops.versionTargets) {
                if (!versions.hasVersion(vr)) {
                    System.out.println("can't locate version " + vr);
                    failed = true;
                }
            }
            if (failed) return;

            // if we only have one version given then we'll compare it with the previous one
            // unles its the first one in which case we'll bail.

            if (depops.versionTargets.size() == 1) {
                Meta.Version mv = versions.version(depops.versionTargets.get(0));
                Meta.Version prev = versions.previous(mv.value());
                if (prev != null) depops.versionTargets.addFirst(mv.value());
                else {
                    System.out.println("no prior version for" + mv.value());
                }
            }

            System.out.println("root " + parent.parent.repo);
            System.out.println("cache " + parent.parent.cache);
            System.out.println("gid " + depops.gid);
            System.out.println("aid " + depops.aid);

            Resolver r = Resolver.Builder(cg).build();

            DependencySet set = r.resolveGraph(depops.gid, depops.aid, depops.versionTargets.getFirst());

            DotViz.viz(System.out, set);

            System.exit(0);
        }
    }
}
