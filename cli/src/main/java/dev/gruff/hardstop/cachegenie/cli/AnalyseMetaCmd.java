package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.actions.AnalyseAction;
import picocli.CommandLine;

@CommandLine.Command(name = "meta", description = "Analyse meta properties files in cachegenie cache")
public class AnalyseMetaCmd implements Runnable {
    @CommandLine.ParentCommand
    AnalyseCmd parent;

    @Override
    public void run() {
        AnalyseAction action = new AnalyseAction(parent.parent.genie());
        action.analyseMeta();
    }
}
