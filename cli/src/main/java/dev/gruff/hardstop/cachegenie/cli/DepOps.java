package dev.gruff.hardstop.cachegenie.cli;

import picocli.CommandLine;

import java.util.List;

public final class DepOps {


    @CommandLine.Option(names = {"-gav", "--gav"}, paramLabel = "<gav>", description = "Group:Artifact:Version selector")
    String gav;

    @CommandLine.Option(names = {"-g",  "--group-id" }, paramLabel = "<group>", description = "Group ID of component to analyse")
    String gid;

    @CommandLine.Option(names = {"-a",  "--artifact-id" }, paramLabel = "<artifact>", description = "Artifact ID of component to analyse")
    String aid;

    @CommandLine.Option(arity = "1..*", names = {"-v",  "--version" }, paramLabel = "<version>", description = "List of versions to analyse")
    List<String> versionTargets;
}
