package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.Meta;
import dev.gruff.hardstop.cachegenie.MetaVersionSet;
import dev.gruff.hardstop.cachegenie.actions.index.IndexAction;
import dev.gruff.hardstop.resolver.DependencyTree;
import dev.gruff.hardstop.resolver.Resolver;
import picocli.CommandLine;

import java.util.List;


@CommandLine.Command(name = "graph", description = "Produce graph of dependencies ")
public class GraphCmd implements Runnable {

    @CommandLine.ParentCommand
    RootCmd parent;

    @CommandLine.Option(names = {"-g",  "--gid" }, required = true, paramLabel = "groupID", description = "Group ID of component to analyse")
    String gid;

    @CommandLine.Option(names = {"-a",  "--aid" }, required = true, paramLabel = "artifactID", description = "Artifact ID of component to analyse")
    String aid;

    @CommandLine.Option(arity = "1..*", names = {"-v",  "--versions" }, required = true, paramLabel = "version List", description = "List of versions to analyse")
    List<String> versionTargets;

    @Override
    public void run() {
        System.out.println("Graph");

        CacheGenie cg = parent.genie();
        IndexAction ia = new IndexAction(cg);

        MetaVersionSet versions = ia.versions(gid, aid);

        // do we have the versions requested?
        boolean failed = false;
        for (String vr : versionTargets) {
            if (!versions.hasVersion(vr)) {
                System.out.println("can't locate version " + vr);
                failed = true;
            }
        }
        if(failed) return;

        // if we only have one version given then we'll compare it with the previous one
        // unles its the first one in which case we'll bail.

        if(versionTargets.size()==1) {
            Meta.Version mv=versions.version(versionTargets.get(0));
            Meta.Version prev=versions.previous(mv.value());
            if(prev!=null) versionTargets.addFirst(mv.value());
            else {
                System.out.println("no prior version for"+mv.value());
            }
        }

        System.out.println("root "+parent.repo);
        System.out.println("cache "+parent.cache);
        System.out.println("gid "+gid);
        System.out.println("aid "+aid);

        Resolver r=new Resolver(cg);

        DependencyTree t=r.resolveTree(gid,aid,versionTargets.getFirst());



        System.exit(0);
    }
}
