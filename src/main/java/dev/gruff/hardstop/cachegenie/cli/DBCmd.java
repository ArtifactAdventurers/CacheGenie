package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.actions.CreateDBAction;
import dev.gruff.hardstop.cachegenie.actions.index.IndexAction;
import picocli.CommandLine;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;

@CommandLine.Command(name = "db", description = "create db from index")
public class DBCmd implements Runnable {

    @CommandLine.ParentCommand
    RootCmd parent;



    @Override
    public void run() {
        System.out.println("Create CacheGenie Repo Index DB");
        System.out.println("root "+parent.repo);
        System.out.println("cache "+parent.cache);
        CreateDBAction action=new CreateDBAction(parent.genie());
        try {
            action.create();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        System.exit(0);
    }
}
