package dev.gruff.hardstop.cachegenie.cli;

import picocli.CommandLine;

import java.util.List;

public final class DepOps {


    @CommandLine.Option(names = {"-g",  "--gid" }, required = true, paramLabel = "groupID", description = "Group ID of component to analyse")
    String gid;

    @CommandLine.Option(names = {"-a",  "--aid" }, required = true, paramLabel = "artifactID", description = "Artifact ID of component to analyse")
    String aid;

    @CommandLine.Option(arity = "1..*", names = {"-v",  "--versions" }, required = true, paramLabel = "version List", description = "List of versions to analyse")
    List<String> versionTargets;
}
