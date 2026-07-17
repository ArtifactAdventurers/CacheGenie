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

    @CommandLine.Option(names = {"--mem-limit"}, paramLabel = "<size>",
            description = "Cap DuckDB's memory (e.g. 8GB) for the staging merge so large intermediates "
                    + "spill to disk and leave headroom for the JVM. Strongly recommended for a full "
                    + "bootstrap (tens of millions of staged records) — set it below RAM minus the JVM "
                    + "heap. Default: DuckDB's own (~80% of RAM).")
    String memLimit;

    @CommandLine.Option(names = {"--db-threads"}, paramLabel = "<n>",
            description = "Cap DuckDB's own worker threads for the merge (default: one per core). "
                    + "Peak merge memory scales with this — lower it (e.g. 2) to trade speed for a "
                    + "smaller footprint if the merge still OOMs.")
    int dbThreads;

    @CommandLine.Option(names = {"--merge-batch"}, paramLabel = "<n>",
            description = "On a full pull, merge staged records into the meta tables every N records "
                    + "(default 5000000) instead of in one merge at the end, bounding peak merge memory "
                    + "by batch size rather than pull size. Result is identical (merges are idempotent, "
                    + "largest-file-wins across batches). 0 = single merge at the end.")
    long mergeBatch = 5_000_000;

    @Override
    public void run() {
        log.info("Starting index sync {}{}", full ? "(--full)" : "(incremental)", limit > 0 ? " limit=" + limit : "");
        new IndexerSyncAction(parent.genie()).sync(full, limit, memLimit, dbThreads, mergeBatch);
        System.exit(0);
    }
}
