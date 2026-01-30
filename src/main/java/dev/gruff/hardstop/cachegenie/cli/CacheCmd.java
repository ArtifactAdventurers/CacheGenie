package dev.gruff.hardstop.cachegenie.cli;


import dev.gruff.hardstop.cachegenie.actions.CacheAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

@CommandLine.Command(name = "cache", description = "cache artifacts")
public class CacheCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(CacheCmd.class);

    @CommandLine.ParentCommand
    RootCmd parent;
    @CommandLine.ArgGroup(exclusive = false, multiplicity = "1")
    DepOps depops;

    @Override
    public void run() {
        CacheAction ca=new CacheAction(parent.genie());
        ca.cache(depops.gid+":"+depops.aid+":"+depops.versionTargets.getFirst());

        log.info("Cache!");
        log.info("root {}", parent.repo);
        log.info("cache {}", parent.cache);
        System.exit(0);
    }
}
