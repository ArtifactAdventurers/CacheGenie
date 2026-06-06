package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.actions.CacheAction;
import dev.gruff.hardstop.cachegenie.actions.index.IndexAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import javax.xml.parsers.ParserConfigurationException;
import java.net.URISyntaxException;
import java.util.List;

@CommandLine.Command(name = "index", aliases = {"scan"}, description = "Index remote cache")
public class IndexCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(IndexCmd.class);

    @CommandLine.ParentCommand
    RootCmd parent;

    @CommandLine.Option(arity = "0..*", names = {"-gav",  "--gav" }, required = false, paramLabel = "<gav>", description = "List of group:artifact:version selectors to index. Defaults to '?' (random walk)")
    List<String> gavs = List.of("?");


    @Override
    public void run() {
        log.info("CacheGenie Repo Index");
        log.info("root {}", parent.repo);
        log.info("cache {}", parent.cache);
        log.info("List {}", gavs);
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
