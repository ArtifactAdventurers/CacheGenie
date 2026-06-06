package dev.gruff.hardstop.cachegenie.cli;


import picocli.CommandLine;

public class Main  {



    public static void main(String... args) {
        try {
            int exitCode = new CommandLine(new RootCmd()).execute(args);
            System.exit(exitCode);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }
}
