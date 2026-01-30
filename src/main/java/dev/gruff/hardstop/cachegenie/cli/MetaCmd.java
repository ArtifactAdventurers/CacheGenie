package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.actions.MetaAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

@CommandLine.Command(name = "meta", description = "Download missing POM files for cached versions")
public class MetaCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(MetaCmd.class);

    @CommandLine.ParentCommand
    RootCmd parent;

    @Override
    public void run() {
        log.info("Checking for missing POMs...");
        log.info("repo {}", parent.repo);
        log.info("cache {}", parent.cache);
        MetaAction action = new MetaAction(parent.genie());
        action.downloadMissingPoms();
        log.info("Done.");
        System.exit(0);
    }
}
