package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.actions.CreateDBAction;
import dev.gruff.hardstop.cachegenie.actions.index.IndexAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;

@CommandLine.Command(name = "db", description = "create db from index")
public class DBCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(DBCmd.class);

    @CommandLine.ParentCommand
    RootCmd parent;



    @Override
    public void run() {
        log.info("Create CacheGenie Repo Index DB");
        log.info("root {}", parent.repo);
        log.info("cache {}", parent.cache);
        CreateDBAction action=new CreateDBAction(parent.genie());
        try {
            action.create();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        System.exit(0);
    }
}
