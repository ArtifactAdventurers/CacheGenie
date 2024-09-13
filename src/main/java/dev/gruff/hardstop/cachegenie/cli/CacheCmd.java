package dev.gruff.hardstop.cachegenie.cli;


import dev.gruff.hardstop.cachegenie.actions.CacheAction;
import picocli.CommandLine;

@CommandLine.Command(name = "cache", description = "cache artifacts")
public class CacheCmd implements Runnable {

    @CommandLine.ParentCommand
    RootCmd parent;
    @CommandLine.ArgGroup(exclusive = false, multiplicity = "1")
    DepOps depops;

    @Override
    public void run() {
        CacheAction ca=new CacheAction(parent.genie());
        ca.cache(depops.gid+":"+depops.aid+":"+depops.versionTargets.getFirst());

        System.out.println("Cache!");
        System.out.println("root "+parent.repo);
        System.out.println("cache "+parent.cache);
        System.exit(0);
    }
}
