package dev.gruff.hardstop.cachegenie.actions;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.graph.GraphRepository;
import dev.gruff.hardstop.cachegenie.graph.MetaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Database administration operations over the DuckDB graph database
 * ({@code ~/.m2/cachegenie/graph.db}). These are the things DuckDB-as-a-database
 * is good at that the populate/query commands don't cover: reclaiming space,
 * exporting for external analysis, creating helpful indexes, and defining
 * convenience views.
 *
 * <p>Replaces the old CSV-dumping {@code CreateDBAction}: the version date-range
 * dump it produced is now the {@code version_ranges} view, exportable via
 * {@link #export}.
 */
public final class DBAction {
    private static final Logger log = LoggerFactory.getLogger(DBAction.class);

    private final CacheGenie cg;

    public DBAction(CacheGenie cg) {
        this.cg = cg;
    }

    private File dbFile() {
        return new File(cg.cacheGenieRoot(), "graph.db");
    }

    /** Ensure both schemas (graph + meta) exist so views/queries don't fail on a partially-populated DB. */
    private void ensureSchema() {
        new GraphRepository(cg.cacheGenieRoot());
        new MetaRepository(cg.cacheGenieRoot());
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:duckdb:" + dbFile().getAbsolutePath());
    }

    private boolean dbMissing() {
        if (!dbFile().exists()) {
            System.out.println("Graph database not found at " + dbFile().getAbsolutePath());
            System.out.println("Run 'scan' or 'graph cache' first to populate it.");
            return true;
        }
        return false;
    }

    /**
     * Force a checkpoint so the write-ahead log is flushed into the main file and
     * freed blocks are reclaimed — useful after a large import leaves the file
     * bloated. Reports the file size before and after.
     */
    public void compact() {
        if (dbMissing()) return;
        long before = dbFile().length();
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("CHECKPOINT");
            st.execute("VACUUM");
        } catch (SQLException e) {
            System.err.println("Compact failed: " + e.getMessage());
            log.error("Compact failed", e);
            return;
        }
        long after = dbFile().length();
        System.out.printf("Compacted %s%n", dbFile().getAbsolutePath());
        System.out.printf("  before: %,d KB%n", before / 1024);
        System.out.printf("  after:  %,d KB%n", after / 1024);
        if (after < before) {
            System.out.printf("  reclaimed %,d KB%n", (before - after) / 1024);
        }
    }

    /**
     * Create secondary indexes on the columns the query/stats joins hit most, and
     * refresh planner statistics. The existing PK / UNIQUE constraints already
     * index (gid,aid,version), parent_id and ga_id prefixes; these add the gaps:
     * artifact lookups by (gid,aid) and reverse-dependency lookups by child_id.
     */
    public void optimize() {
        if (dbMissing()) return;
        ensureSchema();
        String[] ddl = {
                "CREATE INDEX IF NOT EXISTS idx_artifacts_ga ON artifacts(gid, aid)",
                "CREATE INDEX IF NOT EXISTS idx_dependencies_child ON dependencies(child_id)",
                "CREATE INDEX IF NOT EXISTS idx_meta_artifacts_ga ON meta_artifacts(gid, aid)",
        };
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            for (String sql : ddl) {
                st.execute(sql);
                log.info("ok: {}", sql);
            }
            st.execute("ANALYZE");
            System.out.println("Created indexes and refreshed statistics for " + dbFile().getAbsolutePath());
        } catch (SQLException e) {
            System.err.println("Optimize failed: " + e.getMessage());
            log.error("Optimize failed", e);
        }
    }

    /**
     * Create (or replace) convenience views so {@code graph query} can use friendly
     * names instead of hand-written joins:
     * <ul>
     *   <li>{@code gav} — artifacts with a single {@code gid:aid:version} string.</li>
     *   <li>{@code dependents} — every dependency edge flattened to readable coordinates.</li>
     *   <li>{@code version_ranges} — per group:artifact version count and first/last
     *       publish date (the successor to the old {@code range.db} dump).</li>
     * </ul>
     */
    public void views() {
        if (dbMissing()) return;
        ensureSchema();
        String[] ddl = {
                "CREATE OR REPLACE VIEW gav AS " +
                        "SELECT id, gid || ':' || aid || ':' || version AS gav, gid, aid, version, classifier " +
                        "FROM artifacts",
                "CREATE OR REPLACE VIEW dependents AS " +
                        "SELECT c.gid AS dep_gid, c.aid AS dep_aid, c.version AS dep_version, " +
                        "p.gid AS by_gid, p.aid AS by_aid, p.version AS by_version, d.scope " +
                        "FROM dependencies d " +
                        "JOIN artifacts p ON d.parent_id = p.id " +
                        "JOIN artifacts c ON d.child_id = c.id",
                "CREATE OR REPLACE VIEW version_ranges AS " +
                        "SELECT a.gid, a.aid, COUNT(v.version) AS version_count, " +
                        "MIN(v.published) AS first_published, MAX(v.published) AS last_published " +
                        "FROM meta_artifacts a JOIN meta_versions v ON a.id = v.ga_id " +
                        "WHERE v.published IS NOT NULL " +
                        "GROUP BY a.gid, a.aid",
        };
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            for (String sql : ddl) {
                st.execute(sql);
            }
            System.out.println("Created views: gav, dependents, version_ranges");
            System.out.println("Query them with e.g.  graph query \"SELECT * FROM version_ranges LIMIT 10\"");
        } catch (SQLException e) {
            System.err.println("Creating views failed: " + e.getMessage());
            log.error("Creating views failed", e);
        }
    }

    /**
     * Export tables and the {@code version_ranges} view to files for external
     * analysis (pandas/Polars/etc.) using DuckDB's native {@code COPY}.
     *
     * @param format  "parquet" (default), "csv", or "json".
     * @param outDir  destination directory; created if absent. Null → {@code <cachegenie>/export}.
     */
    public void export(String format, File outDir) {
        if (dbMissing()) return;
        ensureSchema();

        String fmt = (format == null ? "parquet" : format.trim().toLowerCase());
        String ext;
        String copyOpts;
        switch (fmt) {
            case "csv":
                ext = "csv";
                copyOpts = "(FORMAT CSV, HEADER)";
                break;
            case "json":
                ext = "json";
                copyOpts = "(FORMAT JSON, ARRAY true)";
                break;
            case "parquet":
                ext = "parquet";
                copyOpts = "(FORMAT PARQUET)";
                break;
            default:
                System.err.println("Unknown format '" + format + "'. Use parquet, csv, or json.");
                return;
        }

        File dir = (outDir != null) ? outDir : new File(cg.cacheGenieRoot(), "export");
        if (!dir.exists() && !dir.mkdirs()) {
            System.err.println("Could not create export directory " + dir.getAbsolutePath());
            return;
        }

        // Base tables plus the version_ranges view (created on demand).
        String[] relations = {"artifacts", "dependencies", "meta_artifacts", "meta_versions", "version_ranges"};

        try (Connection conn = open(); Statement st = conn.createStatement()) {
            // Make sure version_ranges exists for the export.
            st.execute("CREATE OR REPLACE VIEW version_ranges AS " +
                    "SELECT a.gid, a.aid, COUNT(v.version) AS version_count, " +
                    "MIN(v.published) AS first_published, MAX(v.published) AS last_published " +
                    "FROM meta_artifacts a JOIN meta_versions v ON a.id = v.ga_id " +
                    "WHERE v.published IS NOT NULL GROUP BY a.gid, a.aid");

            for (String rel : relations) {
                File out = new File(dir, rel + "." + ext);
                String sql = "COPY (SELECT * FROM " + rel + ") TO '" + out.getAbsolutePath().replace("'", "''") + "' " + copyOpts;
                try {
                    st.execute(sql);
                    System.out.println("  wrote " + out.getAbsolutePath());
                } catch (SQLException e) {
                    System.err.println("  skipped " + rel + ": " + e.getMessage());
                    log.warn("Export of {} failed", rel, e);
                }
            }
            System.out.println("Export complete (" + fmt + ") to " + dir.getAbsolutePath());
        } catch (SQLException e) {
            System.err.println("Export failed: " + e.getMessage());
            log.error("Export failed", e);
        }
    }
}
