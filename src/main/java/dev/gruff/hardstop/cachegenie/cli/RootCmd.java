package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import picocli.CommandLine;

import java.io.File;
import java.net.URI;



@CommandLine.Command(name = "cachegenie", mixinStandardHelpOptions = true, subcommands = {IndexCmd.class,DBCmd.class, CompareCmd.class, CacheCmd.class,GraphCmd.class})

public class RootCmd  {

     // Global parameters
    @CommandLine.Option(names = {"-c", "--cache"}, description = "Location of local Maven cache. Defaults to ~/.m2 ")
    public File cache=CacheGenie.defaultM2();


    @CommandLine.Option(names = {"-r", "--repo"}, description = "Default remote repository")
    public URI repo=CacheGenie.defaultRemoteRepo();



    public CacheGenie genie() {
        return CacheGenie.build(cache,repo);
    }
}
