package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.Meta;
import dev.gruff.hardstop.cachegenie.MetaVersionSet;

import dev.gruff.hardstop.cachegenie.actions.CompareAction;
import dev.gruff.hardstop.cachegenie.actions.index.IndexAction;
import picocli.CommandLine;


import java.util.List;


@CommandLine.Command(name = "compare", description = "Analyse APi differences")
public class CompareCmd implements Runnable {

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
        System.out.println("Compare");

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
        System.out.println("comparing versions "+versions);

        CompareAction ca=new CompareAction(cg);

        final Meta.Version[] last = {null};
        versions.stream().forEach( mv -> {
            if(last[0] !=null) {
                ca.compareVersions(gid,aid,last[0],mv);
            }
            last[0] =mv;
        });

        System.exit(0);
    }
}
