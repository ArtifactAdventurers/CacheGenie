package dev.gruff.hardstop.cachegenie.cli;


import picocli.CommandLine;

public class Main  {



    public static void main(String... args) {

        int exitCode = new CommandLine(new RootCmd()).execute(args);
        System.exit(exitCode);
    }
}
