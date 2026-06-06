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

    @CommandLine.ArgGroup(exclusive = false, multiplicity = "1")
    DepOps depops;

    @Override
    public void run() {
        log.info("Compare");

        CacheGenie cg = parent.genie();
        IndexAction ia = new IndexAction(cg);

        String gid;
        String aid;
        List<String> versionTargets;

        if (depops.gav != null) {
            String[] parts = depops.gav.split(":");
            if (parts.length < 3) {
                throw new CommandLine.ParameterException(new CommandLine(this), "Invalid GAV format for compare. Expected group:artifact:version");
            }
            gid = parts[0];
            aid = parts[1];
            versionTargets = new java.util.ArrayList<>(List.of(parts[2]));
        } else if (depops.gid != null && depops.aid != null && depops.versionTargets != null && !depops.versionTargets.isEmpty()) {
            gid = depops.gid;
            aid = depops.aid;
            versionTargets = new java.util.ArrayList<>(depops.versionTargets);
        } else {
            throw new CommandLine.ParameterException(new CommandLine(this), "Missing required options: use either --gav or --group-id, --artifact-id and --version");
        }

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
        log.info("comparing versions {}", versionTargets);

        CompareAction ca=new CompareAction(cg);

        final MavenMetaData.Version[] last = {null};
        versionTargets.forEach( v -> {
            MavenMetaData.Version mv = versions.version(v);
            if(last[0] !=null) {
                ca.compareVersions(gid,aid,last[0],mv);
            }
            last[0] =mv;
        });

        System.exit(0);
    }
}
