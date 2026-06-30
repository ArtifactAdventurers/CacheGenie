package dev.gruff.hardstop.cachegenie.cli;

import picocli.CommandLine;

@CommandLine.Command(name = "analyse", description = "Analyse local POM files in the cache", subcommands = {AnalysePomCmd.class})
public class AnalyseCmd {
    @CommandLine.ParentCommand
    RootCmd parent;
}
