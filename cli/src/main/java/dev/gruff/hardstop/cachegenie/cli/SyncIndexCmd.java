package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.actions.IndexerSyncAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

@CommandLine.Command(name = "index-sync", aliases = {"central-sync"},
        description = "Discover artifacts/versions from the repository's published Maven index (full first run, incremental after) instead of crawling HTML")
public class SyncIndexCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(SyncIndexCmd.class);

    @CommandLine.ParentCommand
    RootCmd parent;

    @CommandLine.Option(names = "--full",
            description = "Ignore local sync state and re-pull the entire index (otherwise resumes incrementally).")
    boolean full;

    @CommandLine.Option(names = "--limit", paramLabel = "<n>",
            description = "Stop after processing N records (0 = no limit). For quickly smoke-testing the full path. Note: with --limit, local sync state is NOT advanced, so this won't mark you up to date.")
    long limit = 0;

    @Override
    public void run() {
        log.info("Starting index sync {}{}", full ? "(--full)" : "(incremental)", limit > 0 ? " limit=" + limit : "");
        new IndexerSyncAction(parent.genie()).sync(full, limit);
        System.exit(0);
    }
}
