package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.actions.MigrateMetaAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.IOException;

@CommandLine.Command(name = "migrate-meta", description = "Migrate metadata files from .properties to .json in a directory hierarchy")
public class MigrateMetaCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(MigrateMetaCmd.class);

    @CommandLine.ParentCommand
    RootCmd parent;

    @Override
    public void run() {
        log.info("Starting metadata migration...");
        MigrateMetaAction action = new MigrateMetaAction(parent.genie());
        try {
            action.migrate();
        } catch (IOException e) {
            log.error("Migration failed: {}", e.getMessage(), e);
            System.exit(1);
        }
        log.info("Done.");
        System.exit(0);
    }
}
