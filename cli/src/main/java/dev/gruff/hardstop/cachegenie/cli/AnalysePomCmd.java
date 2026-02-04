package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.actions.AnalyseAction;
import picocli.CommandLine;

@CommandLine.Command(name = "pom", description = "Analyse POM files in local maven cache")
public class AnalysePomCmd implements Runnable {
    @CommandLine.ParentCommand
    AnalyseCmd parent;

    @Override
    public void run() {
        AnalyseAction action = new AnalyseAction(parent.parent.genie());
        action.analysePom();
    }
}
