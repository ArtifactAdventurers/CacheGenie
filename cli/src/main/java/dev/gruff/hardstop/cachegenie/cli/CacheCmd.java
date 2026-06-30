package dev.gruff.hardstop.cachegenie.cli;


import dev.gruff.hardstop.cachegenie.actions.CacheAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

@CommandLine.Command(name = "cache", aliases = {"hydrate", "fill"},
        description = "Download artifact JARs into the local Maven repository (~/.m2/repository)")
public class CacheCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(CacheCmd.class);

    @CommandLine.ParentCommand
    RootCmd parent;
    @CommandLine.ArgGroup(exclusive = false, multiplicity = "1")
    DepOps depops;

    @Override
    public void run() {
        CacheAction ca=new CacheAction(parent.genie());
        String target;
        if (depops.gav != null) {
            target = depops.gav;
        } else if (depops.gid != null && depops.aid != null && depops.versionTargets != null && !depops.versionTargets.isEmpty()) {
            target = depops.gid + ":" + depops.aid + ":" + depops.versionTargets.getFirst();
        } else {
            throw new CommandLine.ParameterException(new CommandLine(this), "Missing required options: use either --gav or --group-id, --artifact-id and --version");
        }
        ca.cache(target);

        log.info("Cache!");
        log.info("root {}", parent.repo);
        log.info("cache {}", parent.cache);
        System.exit(0);
    }
}
