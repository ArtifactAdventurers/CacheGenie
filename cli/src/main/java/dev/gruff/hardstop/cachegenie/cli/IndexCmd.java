package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.actions.index.IndexAction;
import dev.gruff.hardstop.cachegenie.actions.index.IndexBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import javax.xml.parsers.ParserConfigurationException;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;

@CommandLine.Command(name = "index", aliases = {"scan"},
        description = "Discover artifact versions by crawling a remote repo's HTML listings (random-walk, or targeted with --gav). For bulk discovery prefer 'index-sync'.")
public class IndexCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(IndexCmd.class);

    @CommandLine.ParentCommand
    RootCmd parent;

    @CommandLine.Option(arity = "0..*", names = {"-gav", "--gav"}, required = false, paramLabel = "<gav>", description = "List of group:artifact:version selectors to index. Defaults to '?' (random walk)")
    List<String> gavs = List.of("?");

    @CommandLine.Option(names = {"--rate"}, paramLabel = "<req/min>",
            description = "Total remote requests per minute across the whole crawl (default ${DEFAULT-VALUE}). Lower it to be gentler on the remote repo.")
    int rate = IndexBuilder.DEFAULT_RATE_PER_MINUTE;

    @CommandLine.Option(names = {"--threads"}, paramLabel = "<n>",
            description = "Concurrent crawl workers (default ${DEFAULT-VALUE}). The --rate budget is shared across them.")
    int threads = IndexBuilder.DEFAULT_THREADS;

    @CommandLine.Option(names = {"--max-age"}, paramLabel = "<dur>",
            description = "On an undirected scan, skip re-checking metadata seen within this window, e.g. 7d, 24h, 30m (0 = always check). Default 7d. Ignored for explicit --gav requests.")
    String maxAge = "7d";


    @Override
    public void run() {
        log.info("CacheGenie Repo Index");
        log.info("root {}", parent.repo);
        log.info("cache {}", parent.cache);
        log.info("List {}", gavs);
        log.info("rate {}/min across {} threads, max-age {}", rate, threads, maxAge);
        IndexAction action=new IndexAction(parent.genie());
        try {
            action.index(gavs, rate, threads, parseDuration(maxAge));
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        System.exit(0);
    }

    /** Parse "7d"/"24h"/"30m"/"45s" or a bare number (days). "0" = always check. Falls back to 7d. */
    private static Duration parseDuration(String s) {
        if (s == null) return IndexBuilder.DEFAULT_MAX_AGE;
        s = s.trim().toLowerCase();
        if (s.isEmpty()) return IndexBuilder.DEFAULT_MAX_AGE;
        if (s.equals("0")) return Duration.ZERO;
        try {
            char unit = s.charAt(s.length() - 1);
            if (Character.isDigit(unit)) return Duration.ofDays(Long.parseLong(s));
            long n = Long.parseLong(s.substring(0, s.length() - 1).trim());
            return switch (unit) {
                case 'd' -> Duration.ofDays(n);
                case 'h' -> Duration.ofHours(n);
                case 'm' -> Duration.ofMinutes(n);
                case 's' -> Duration.ofSeconds(n);
                default -> IndexBuilder.DEFAULT_MAX_AGE;
            };
        } catch (NumberFormatException e) {
            return IndexBuilder.DEFAULT_MAX_AGE;
        }
    }
}
