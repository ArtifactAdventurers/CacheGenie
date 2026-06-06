package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.actions.MetaAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.util.List;

@CommandLine.Command(name = "meta", aliases = {"fetch"}, description = "Download missing POM files for cached versions")
public class MetaCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(MetaCmd.class);

    @CommandLine.ParentCommand
    RootCmd parent;

    @CommandLine.Option(arity = "0..*", names = {"-gav", "--gav"}, paramLabel = "<gav>", description = "List of group:artifact:version selectors to update meta data for.")
    List<String> gavs;

    @CommandLine.Option(names = {"-u", "--update"}, description = "Retry downloading missing POMs")
    boolean update = false;

    @Override
    public void run() {
        log.info("Checking for missing POMs...");
        log.info("repo {}", parent.repo);
        log.info("cache {}", parent.cache);
        MetaAction action = new MetaAction(parent.genie());
        action.downloadMissingPoms(gavs, update);
        log.info("Done.");
        System.exit(0);
    }
}
