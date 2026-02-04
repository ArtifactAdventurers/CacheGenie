package dev.gruff.hardstop.cachegenie.cli;

import picocli.CommandLine;

@CommandLine.Command(name = "analyse", description = "Analyse the cache", subcommands = {AnalysePomCmd.class, AnalyseMetaCmd.class})
public class AnalyseCmd {
    @CommandLine.ParentCommand
    RootCmd parent;
}
