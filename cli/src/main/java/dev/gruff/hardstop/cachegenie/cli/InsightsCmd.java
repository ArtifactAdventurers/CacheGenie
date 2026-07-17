package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.graph.EcosystemStats;
import dev.gruff.hardstop.cachegenie.graph.EcosystemStats.Report;
import dev.gruff.hardstop.cachegenie.graph.EcosystemStats.Scope;
import picocli.CommandLine;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Ecosystem analysis over the DuckDB database — the "how does software evolve
 * and get abandoned" reports, as a sibling to {@code graph stats} (a quick
 * snapshot) and {@code graph query} (ad-hoc SQL). Each subcommand prints one or
 * more titled grids; {@code --format} switches between a padded table (default),
 * CSV, or JSON for piping.
 *
 * <p>All subcommands open the database read-only (so analysis never takes a
 * write lock) via {@link EcosystemStats}. Scope every report with
 * {@code -g/--gav} and/or {@code --since}/{@code --until}.
 */
@CommandLine.Command(name = "insights", aliases = {"insight"},
        description = "Analyse how artifacts arrive, evolve, update their dependencies, and get abandoned",
        subcommands = {
                InsightsCmd.ArrivalsCmd.class,
                InsightsCmd.LifecycleCmd.class,
                InsightsCmd.AbandonmentCmd.class,
                InsightsCmd.ChurnCmd.class,
                InsightsCmd.ResolutionCmd.class,
                InsightsCmd.ReportCmd.class,
        })
public class InsightsCmd implements Runnable {

    @CommandLine.ParentCommand
    RootCmd parent;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        // No subcommand — show usage rather than guessing.
        spec.commandLine().usage(System.out);
    }

    // ------------------------------------------------------------- options

    /** Shared scope + output options, mixed into each subcommand. */
    public static class ScopeOptions {
        @CommandLine.Option(names = {"-g", "--gav"}, paramLabel = "GROUP[:ARTIFACT]",
                description = "Restrict to a group (and its subgroups) or an exact group:artifact")
        String gav;

        @CommandLine.Option(names = "--since", paramLabel = "YEAR",
                description = "Only consider versions published in this year or later")
        Integer since;

        @CommandLine.Option(names = "--until", paramLabel = "YEAR",
                description = "Only consider versions published in this year or earlier")
        Integer until;

        @CommandLine.Option(names = {"-f", "--format"}, paramLabel = "FORMAT", defaultValue = "table",
                description = "Output format: table (default), csv, json")
        String format;

        Scope scope() {
            return Scope.of(gav, since, until);
        }
    }

    // ---------------------------------------------------------- subcommands

    @CommandLine.Command(name = "arrivals",
            description = "Catalogue coverage and arrival rate over time (versions/artifacts/groups per year)")
    public static class ArrivalsCmd implements Runnable {
        @CommandLine.ParentCommand
        InsightsCmd parent;
        @CommandLine.Mixin
        ScopeOptions opts;

        @Override
        public void run() {
            execute(parent, opts, s -> s.arrivals(opts.scope()));
        }
    }

    @CommandLine.Command(name = "lifecycle",
            description = "Versions per artifact, single-release share, lifespan, and update frequency")
    public static class LifecycleCmd implements Runnable {
        @CommandLine.ParentCommand
        InsightsCmd parent;
        @CommandLine.Mixin
        ScopeOptions opts;

        @Override
        public void run() {
            execute(parent, opts, s -> s.lifecycle(opts.scope()));
        }
    }

    @CommandLine.Command(name = "abandonment",
            description = "How many artifacts have gone quiet, with a last-release-age survival curve")
    public static class AbandonmentCmd implements Runnable {
        @CommandLine.ParentCommand
        InsightsCmd parent;
        @CommandLine.Mixin
        ScopeOptions opts;

        @Override
        public void run() {
            execute(parent, opts, s -> s.abandonment(opts.scope()));
        }
    }

    @CommandLine.Command(name = "churn",
            description = "How often consecutive versions of an artifact bump a dependency they already declare")
    public static class ChurnCmd implements Runnable {
        @CommandLine.ParentCommand
        InsightsCmd parent;
        @CommandLine.Mixin
        ScopeOptions opts;

        @CommandLine.Option(names = "--raw",
                description = "Use raw declared direct_dep versions (needs only 'graph mine'; literal versions only) "
                        + "instead of resolved 'dependencies' edges (needs 'graph resolve'; effective versions)")
        boolean raw;

        @CommandLine.Option(names = "--top", paramLabel = "N", defaultValue = "30",
                description = "Also list the N most-frequently-bumped dependencies (0 to skip)")
        int top;

        @Override
        public void run() {
            execute(parent, opts, s -> s.churn(opts.scope(), raw, top));
        }
    }

    @CommandLine.Command(name = "resolution", aliases = {"coverage"},
            description = "How much of the catalogue is mined and resolved into edges (catalogued->mined->resolved funnel + gaps)")
    public static class ResolutionCmd implements Runnable {
        @CommandLine.ParentCommand
        InsightsCmd parent;
        @CommandLine.Mixin
        ScopeOptions opts;

        @Override
        public void run() {
            execute(parent, opts, s -> s.resolution(opts.scope()));
        }
    }

    @CommandLine.Command(name = "report",
            description = "Run arrivals + lifecycle + abandonment + churn (summary) + resolution in one pass")
    public static class ReportCmd implements Runnable {
        @CommandLine.ParentCommand
        InsightsCmd parent;
        @CommandLine.Mixin
        ScopeOptions opts;

        @Override
        public void run() {
            execute(parent, opts, s -> {
                Scope sc = opts.scope();
                List<Report> all = new ArrayList<>();
                all.addAll(s.arrivals(sc));
                all.addAll(s.lifecycle(sc));
                all.addAll(s.abandonment(sc));
                all.addAll(s.churn(sc, false, 0)); // churn summary only (top-N omitted)
                all.addAll(s.resolution(sc));
                return all;
            });
        }
    }

    // ------------------------------------------------------------- plumbing

    @FunctionalInterface
    interface ReportQuery {
        List<Report> run(EcosystemStats stats) throws SQLException;
    }

    private static void execute(InsightsCmd insights, ScopeOptions opts, ReportQuery query) {
        CacheGenie cg = insights.parent.genie();
        EcosystemStats stats = new EcosystemStats(cg.cacheGenieRoot());
        if (!stats.dbExists()) {
            System.out.println("Graph database not found at " + stats.dbPath());
            System.out.println("Run 'index-sync' (then 'graph mine' + 'graph resolve') first to populate it.");
            return;
        }
        try {
            emit(query.run(stats), opts.format);
        } catch (SQLException e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Conflicting lock") || msg.contains("Could not set lock"))) {
                System.err.println("graph.db is locked by another CacheGenie process (most likely a running 'graph deps'/'mine').");
                System.err.println("DuckDB cannot attach (even read-only) while another process holds it read-write; wait for that run to finish.");
            } else {
                System.err.println("SQL Error: " + msg);
            }
        }
    }

    // -------------------------------------------------------------- output

    private static void emit(List<Report> reports, String format) {
        String fmt = (format == null ? "table" : format.trim().toLowerCase());
        switch (fmt) {
            case "table" -> printTable(reports);
            case "csv" -> printCsv(reports);
            case "json" -> printJson(reports);
            default -> System.err.println("Unknown format '" + format + "'. Use table, csv, or json.");
        }
    }

    private static String cell(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static void printTable(List<Report> reports) {
        boolean first = true;
        for (Report r : reports) {
            if (!first) System.out.println();
            first = false;
            System.out.println(r.title());
            System.out.println("-".repeat(r.title().length()));
            if (r.rows().isEmpty()) {
                System.out.println("(no rows)");
                continue;
            }
            int n = r.columns().size();
            int[] w = new int[n];
            for (int i = 0; i < n; i++) w[i] = r.columns().get(i).length();
            for (List<Object> row : r.rows()) {
                for (int i = 0; i < n; i++) w[i] = Math.max(w[i], cell(row.get(i)).length());
            }
            StringBuilder hdr = new StringBuilder();
            for (int i = 0; i < n; i++) {
                hdr.append(pad(r.columns().get(i), w[i]));
                if (i < n - 1) hdr.append("  ");
            }
            System.out.println(hdr.toString().stripTrailing());
            for (List<Object> row : r.rows()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    sb.append(pad(cell(row.get(i)), w[i]));
                    if (i < n - 1) sb.append("  ");
                }
                System.out.println(sb.toString().stripTrailing());
            }
        }
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    private static void printCsv(List<Report> reports) {
        boolean first = true;
        for (Report r : reports) {
            if (!first) System.out.println();
            first = false;
            System.out.println("# " + r.title());
            System.out.println(csvRow(r.columns()));
            for (List<Object> row : r.rows()) {
                List<String> vals = new ArrayList<>(row.size());
                for (Object o : row) vals.add(cell(o));
                System.out.println(csvRow(vals));
            }
        }
    }

    private static String csvRow(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(csvEscape(values.get(i)));
        }
        return sb.toString();
    }

    private static String csvEscape(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    private static void printJson(List<Report> reports) {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int ri = 0; ri < reports.size(); ri++) {
            Report r = reports.get(ri);
            sb.append("  {\n");
            sb.append("    \"report\": ").append(jsonString(r.title())).append(",\n");
            sb.append("    \"data\": [");
            for (int i = 0; i < r.rows().size(); i++) {
                List<Object> row = r.rows().get(i);
                sb.append(i == 0 ? "\n" : ",\n");
                sb.append("      {");
                for (int c = 0; c < r.columns().size(); c++) {
                    if (c > 0) sb.append(", ");
                    sb.append(jsonString(r.columns().get(c))).append(": ").append(jsonValue(row.get(c)));
                }
                sb.append("}");
            }
            sb.append(r.rows().isEmpty() ? "]" : "\n    ]");
            sb.append("\n  }");
            if (ri < reports.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]");
        System.out.println(sb);
    }

    private static String jsonValue(Object o) {
        if (o == null) return "null";
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        return jsonString(String.valueOf(o));
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        return sb.append('"').toString();
    }
}
