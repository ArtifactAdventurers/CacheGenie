package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.utils.Progress;
import picocli.CommandLine;

import java.io.File;
import java.net.URI;



@CommandLine.Command(name = "cachegenie", mixinStandardHelpOptions = true,
        description = "A tool to manage and analyze Maven artifact caches.",
        header = {
                "CacheGenie helps you discover, fetch, and analyze Maven artifacts.",
                "It works by layering metadata and artifacts in your local cache."
        },
        footer = {
                "",
                "Normal Operation (The 'Genie' Workflow):",
                "  1. scan (index)  : Discover versions of an artifact in remote repositories.",
                "  2. fetch (meta)  : Download the POM 'recipes' for those versions.",
                "  3. map (graph)   : Analyze and visualize the dependency structures.",
                "  4. hydrate (fill): Download the actual JAR files into your local repository.",
                "",
                "Analysis and Persistence:",
                "  - map artifact   : Resolves dependencies and persists them to a local DuckDB.",
                "  - map query      : Execute SQL queries against the persisted dependency graph.",
                "",
                "Data Locations:",
                "  - Local Repository: Artifacts (JARs/POMs) are stored in your Maven repository (default: ~/.m2/repository).",
                "  - Metadata: Discovery info and analysis results are stored in ~/.m2/cachegenie/meta.",
                "",
                "Example:",
                "  cachegenie scan --gav org.slf4j:slf4j-api",
                "  cachegenie fetch --gav org.slf4j:slf4j-api:2.0.9",
                "  cachegenie map artifact --gav org.slf4j:slf4j-api:2.0.9"
        },
        subcommands = {IndexCmd.class, DBCmd.class, CompareCmd.class, CacheCmd.class, GraphCmd.class, MetaCmd.class, MetaCSVCmd.class, AnalyseCmd.class, MigrateMetaCmd.class, ViewCmd.class})

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
