package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.utils.Progress;
import picocli.CommandLine;

import java.io.File;
import java.net.URI;



@CommandLine.Command(name = "cachegenie", mixinStandardHelpOptions = true,
        description = "Build and maintain a queryable database of Maven artifacts, their direct dependencies, and risk annotations.",
        header = {
                "CacheGenie discovers Maven artifacts, mines their POMs for facts and direct",
                "dependencies into a local SQLite database, and keeps a local POM/metadata cache",
                "as a byproduct. Dependency graphs and stats are then queries over that database."
        },
        footer = {
                "",
                "Normal Operation (build & maintain the artifact database):",
                "  1. index-sync      : Discover artifacts/versions from the repository's published index.",
                "  2. graph mine      : Fetch each version's POM (one GET) and store its raw facts + direct deps.",
                "  3. graph resolve   : Project mined POMs into concrete dependency edges.",
                "  4. metadata        : (optional) Write maven-metadata.xml into the local repo for offline resolution.",
                "  5. cache (hydrate) : (optional) Download the actual JARs into your local repository.",
                "",
                "Targeted / single-GAV tools:",
                "  - scan (index)     : Crawl a remote repo for a specific --gav (immediacy; no published index needed).",
                "  - fetch (meta)     : Download a specific POM onto disk.",
                "",
                "Analysis:",
                "  - graph query      : Execute SQL (incl. recursive CTEs) against the SQLite graph.",
                "  - graph stats      : Graph and discovery-metadata statistics (quick snapshot).",
                "  - insights         : Ecosystem evolution reports (arrivals, lifecycle, abandonment, dependency churn).",
                "",
                "Data Locations:",
                "  - Local Repository : Artifacts (JARs/POMs) in your Maven repository (default: ~/.m2/repository).",
                "  - Graph database   : Catalogue + dependency graph in ~/.m2/cachegenie/graph.sqlite (SQLite).",
                "",
                "Example (keep current):",
                "  cachegenie index-sync",
                "  cachegenie graph mine --since 30d --rate 60",
                "  cachegenie graph resolve"
        },
        subcommands = {IndexCmd.class, SyncIndexCmd.class, DBCmd.class, CompareCmd.class, CacheCmd.class, GraphCmd.class, MetaCmd.class, MetadataCmd.class, AnalyseCmd.class, InsightsCmd.class, ViewCmd.class})

public class RootCmd  {

     // Global parameters
    @CommandLine.Option(names = {"-c", "--cache"}, description = "Location of local Maven cache. Defaults to ~/.m2 ")
    public File cache=CacheGenie.defaultM2();


    @CommandLine.Option(names = {"-r", "--repo"}, description = "Default remote repository")
    public URI repo=CacheGenie.defaultRemoteRepo();

    @CommandLine.Option(names = {"-P", "--progress"}, description = "Emit periodic progress messages to stderr during long-running commands.")
    public void setProgress(boolean enabled) {
        Progress.setEnabled(enabled);
    }

    @CommandLine.Option(names = {"-l", "--log"}, description = "Log level (trace, debug, info, warn, error)", defaultValue = "info")
    public void setLogLevel(String level) {
        System.setProperty("LOG_LEVEL", level);
        org.slf4j.ILoggerFactory factory = org.slf4j.LoggerFactory.getILoggerFactory();
        if (factory instanceof ch.qos.logback.classic.LoggerContext loggerContext) {
            ch.qos.logback.classic.joran.JoranConfigurator configurator = new ch.qos.logback.classic.joran.JoranConfigurator();
            configurator.setContext(loggerContext);
            loggerContext.reset();
            try {
                java.net.URL url = getClass().getResource("/logback.xml");
                if (url != null) {
                    configurator.doConfigure(url);
                } else {
                    // No config on the classpath — don't leave the context with
                    // zero appenders (which would silence all logging). Fall back
                    // to a basic console appender at the requested level.
                    new ch.qos.logback.classic.BasicConfigurator().configure(loggerContext);
                }
            } catch (ch.qos.logback.core.joran.spi.JoranException e) {
                new ch.qos.logback.classic.BasicConfigurator().configure(loggerContext);
            }
        }
    }



    public CacheGenie genie() {
        return CacheGenie.build(cache,repo);
    }
}
