package dev.gruff.hardstop.cachegenie.cli;

import picocli.CommandLine;

@CommandLine.Command(name = "index", description = "Index remote cache")
public class IndexCmd implements Runnable {

    @CommandLine.ParentCommand
    RootCmd parent;

    @Override
    public void run() {
        System.out.println("Hello Index World!");
        System.out.println("root "+parent.repo);
        System.out.println("cache "+parent.cache);
        System.exit(0);
    }
}
