package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import picocli.CommandLine;

import java.io.File;
import java.net.URI;



@CommandLine.Command(name = "cachegenie", mixinStandardHelpOptions = true, subcommands = {IndexCmd.class,DBCmd.class, CompareCmd.class, CacheCmd.class,GraphCmd.class, MetaCmd.class})

public class RootCmd  {

     // Global parameters
    @CommandLine.Option(names = {"-c", "--cache"}, description = "Location of local Maven cache. Defaults to ~/.m2 ")
    public File cache=CacheGenie.defaultM2();


    @CommandLine.Option(names = {"-r", "--repo"}, description = "Default remote repository")
    public URI repo=CacheGenie.defaultRemoteRepo();

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
                }
            } catch (ch.qos.logback.core.joran.spi.JoranException e) {
                // ignore
            }
        }
    }



    public CacheGenie genie() {
        return CacheGenie.build(cache,repo);
    }
}
