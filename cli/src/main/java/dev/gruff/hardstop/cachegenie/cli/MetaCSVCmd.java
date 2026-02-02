package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.actions.CreateMetaCSVAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;

@CommandLine.Command(name = "meta-csv", description = "Create a CSV file from all meta properties files")
public class MetaCSVCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(MetaCSVCmd.class);

    @CommandLine.ParentCommand
    RootCmd parent;

    @CommandLine.Option(names = {"-o", "--output"}, description = "Output CSV file", defaultValue = "meta.csv")
    File output;

    @Override
    public void run() {
        log.info("Generating Meta CSV...");
        CreateMetaCSVAction action = new CreateMetaCSVAction(parent.genie());
        try {
            action.create(output);
        } catch (IOException e) {
            log.error("Failed to create CSV: {}", e.getMessage());
            System.exit(1);
        }
        log.info("Done.");
        System.exit(0);
    }
}
