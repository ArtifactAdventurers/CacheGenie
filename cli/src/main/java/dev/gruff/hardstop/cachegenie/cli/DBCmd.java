package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.actions.DBAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;

@CommandLine.Command(name = "db",
        description = "Manage the DuckDB graph database: compact, export, optimize, views",
        subcommands = {DBCmd.CompactCmd.class, DBCmd.ExportCmd.class, DBCmd.OptimizeCmd.class, DBCmd.ViewsCmd.class})
public class DBCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(DBCmd.class);

    @CommandLine.ParentCommand
    RootCmd parent;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        // No subcommand given — show usage rather than doing anything destructive.
        spec.commandLine().usage(System.out);
    }

    @CommandLine.Command(name = "compact", description = "Checkpoint the DB; --rewrite rebuilds into a fresh, smaller file")
    public static class CompactCmd implements Runnable {
        @CommandLine.ParentCommand
        DBCmd parent;

        @CommandLine.Option(names = "--rewrite",
                description = "Rebuild the database into a fresh file to actually shrink it (keeps a .bak of the original)")
        boolean rewrite;

        @Override
        public void run() {
            DBAction action = new DBAction(parent.parent.genie());
            if (rewrite) {
                action.compactRewrite();
            } else {
                action.compact();
            }
            System.exit(0);
        }
    }

    @CommandLine.Command(name = "optimize", description = "Create secondary indexes on hot columns and refresh planner statistics")
    public static class OptimizeCmd implements Runnable {
        @CommandLine.ParentCommand
        DBCmd parent;

        @Override
        public void run() {
            new DBAction(parent.parent.genie()).optimize();
            System.exit(0);
        }
    }

    @CommandLine.Command(name = "views", description = "Create convenience views (gav, dependents, version_ranges)")
    public static class ViewsCmd implements Runnable {
        @CommandLine.ParentCommand
        DBCmd parent;

        @Override
        public void run() {
            new DBAction(parent.parent.genie()).views();
            System.exit(0);
        }
    }

    @CommandLine.Command(name = "export", description = "Export tables and the version_ranges view for external analysis")
    public static class ExportCmd implements Runnable {
        @CommandLine.ParentCommand
        DBCmd parent;

        @CommandLine.Option(names = {"-f", "--format"}, paramLabel = "FORMAT",
                description = "parquet (default), csv, or json")
        String format = "parquet";

        @CommandLine.Option(names = {"-o", "--out"}, paramLabel = "DIR",
                description = "Output directory (default: <cachegenie>/export)")
        File out;

        @Override
        public void run() {
            new DBAction(parent.parent.genie()).export(format, out);
            System.exit(0);
        }
    }
}
