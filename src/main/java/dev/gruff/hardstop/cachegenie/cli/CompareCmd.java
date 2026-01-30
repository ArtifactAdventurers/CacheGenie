package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.MavenMetaData;
import dev.gruff.hardstop.cachegenie.MetaVersionSet;

import dev.gruff.hardstop.cachegenie.actions.CompareAction;
import dev.gruff.hardstop.cachegenie.actions.index.IndexAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;


import java.util.List;


@CommandLine.Command(name = "compare", description = "Analyse APi differences")
public class CompareCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(CompareCmd.class);

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
        log.info("Compare");

        CacheGenie cg = parent.genie();
        IndexAction ia = new IndexAction(cg);

        MetaVersionSet versions = ia.versions(gid, aid);

        // do we have the versions requested?
        boolean failed = false;
        for (String vr : versionTargets) {
            if (!versions.hasVersion(vr)) {
                log.info("can't locate version {}", vr);
                failed = true;
            }
        }
        if(failed) return;

        // if we only have one version given then we'll compare it with the previous one
        // unles its the first one in which case we'll bail.

        if(versionTargets.size()==1) {
            MavenMetaData.Version mv=versions.version(versionTargets.get(0));
            MavenMetaData.Version prev=versions.previous(mv.value());
            if(prev!=null) versionTargets.addFirst(mv.value());
            else {
                log.info("no prior version for {}", mv.value());
            }
        }

        log.info("root {}", parent.repo);
        log.info("cache {}", parent.cache);
        log.info("gid {}", gid);
        log.info("aid {}", aid);
        log.info("comparing versions {}", versions);

        CompareAction ca=new CompareAction(cg);

        final MavenMetaData.Version[] last = {null};
        versions.stream().forEach( mv -> {
            if(last[0] !=null) {
                ca.compareVersions(gid,aid,last[0],mv);
            }
            last[0] =mv;
        });

        System.exit(0);
    }
}
