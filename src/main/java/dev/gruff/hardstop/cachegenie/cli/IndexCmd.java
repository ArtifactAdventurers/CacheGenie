package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.actions.CacheAction;
import dev.gruff.hardstop.cachegenie.actions.index.IndexAction;
import picocli.CommandLine;

import javax.xml.parsers.ParserConfigurationException;
import java.net.URISyntaxException;
import java.util.List;

@CommandLine.Command(name = "index", description = "Index remote cache")
public class IndexCmd implements Runnable {

    @CommandLine.ParentCommand
    RootCmd parent;

    @CommandLine.Option(arity = "1..*", names = {"-gav",  "--group-artifact-version" }, required = true, paramLabel = "GA List", description = "List of group:artifact:version selectors to index. ")
    List<String> gavs;


    @Override
    public void run() {
        System.out.println("CacheGenie Repo Index");
        System.out.println("root "+parent.repo);
        System.out.println("cache "+parent.cache);
        System.out.println("List "+gavs);
        IndexAction action=new IndexAction(parent.genie());
        try {
            action.index(gavs);
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        System.exit(0);
    }
}
