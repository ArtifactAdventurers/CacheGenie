package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.actions.MigrateMetaAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.IOException;

@CommandLine.Command(name = "migrate-meta", description = "Import legacy .properties/.json meta files into the DuckDB meta tables (one-time migration)")
public class MigrateMetaCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(MigrateMetaCmd.class);

    @CommandLine.ParentCommand
    RootCmd parent;

    @CommandLine.Option(names = "--fresh",
            description = "Clear the meta tables and re-import everything. Without this, the migration resumes: coordinates already imported are skipped, so an interrupted run can be safely restarted.")
    boolean fresh;

    @Override
    public void run() {
        log.info("Starting metadata migration...{}", fresh ? " (--fresh: full re-import)" : " (resuming; already-imported coordinates are skipped)");
        MigrateMetaAction action = new MigrateMetaAction(parent.genie());
        try {
            action.migrate(fresh);
        } catch (IOException e) {
            log.error("Migration failed: {}", e.getMessage(), e);
            System.exit(1);
        }
        log.info("Done.");
        System.exit(0);
    }
}
