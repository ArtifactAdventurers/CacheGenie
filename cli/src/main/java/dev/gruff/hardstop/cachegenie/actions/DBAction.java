package dev.gruff.hardstop.cachegenie.actions;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.graph.GraphRepository;
import dev.gruff.hardstop.cachegenie.graph.MetaRepository;
import dev.gruff.hardstop.cachegenie.graph.Sqlite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Database administration operations over the SQLite graph database
 * ({@code ~/.m2/cachegenie/graph.sqlite}). These are the things a database
 * needs that the populate/query commands don't cover: reclaiming space,
 * exporting for external analysis, creating helpful indexes, and defining
 * convenience views.
 *
 * <p>Replaces the old CSV-dumping DB action: the version date-range dump it
 * produced is now the {@code version_ranges} view, exportable via {@link #export}.
 */
public final class DBAction {
    private static final Logger log = LoggerFactory.getLogger(DBAction.class);

    private static final String VERSION_RANGES_VIEW =
            "CREATE VIEW version_ranges AS " +
                    "SELECT a.gid, a.aid, COUNT(v.version) AS version_count, " +
                    "MIN(v.published) AS first_published, MAX(v.published) AS last_published " +
                    "FROM meta_artifacts a JOIN meta_versions v ON a.id = v.ga_id " +
                    "WHERE v.published IS NOT NULL " +
                    "GROUP BY a.gid, a.aid";

    private final CacheGenie cg;

    public DBAction(CacheGenie cg) {
        this.cg = cg;
    }

    private File dbFile() {
        return new File(Sqlite.dbPath(cg.cacheGenieRoot()));
    }

    /** Ensure both schemas (graph + meta) exist so views/queries don't fail on a partially-populated DB. */
    private void ensureSchema() {
        new GraphRepository(cg.cacheGenieRoot());
        new MetaRepository(cg.cacheGenieRoot());
    }

    private Connection open() throws SQLException {
        return Sqlite.open(dbFile().getAbsolutePath());
    }

    private boolean dbMissing() {
        if (!dbFile().exists()) {
            System.out.println("Graph database not found at " + dbFile().getAbsolutePath());
            System.out.println("Run 'index-sync' (then 'graph mine' + 'graph resolve') first to populate it.");
            return true;
        }
        return false;
    }

    /**
     * Reclaim space in place: fold the write-ahead log back into the main file
     * and truncate it ({@code PRAGMA wal_checkpoint(TRUNCATE)}), then
     * {@code VACUUM} to rebuild the file without free pages. Unlike the old
     * DuckDB CHECKPOINT, VACUUM actually returns freed space to the OS, so the
     * file can shrink here — useful after a large import or delete leaves it
     * bloated. Needs transient scratch space up to roughly the size of the live
     * data. Reports the file size before and after.
     */
    public void compact() {
        if (dbMissing()) return;
        long before = dbFile().length();
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
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
        } else {
            System.out.println("  (no change — the file was already compact)");
        }
    }

    /**
     * Compaction into a fresh file: {@code VACUUM INTO} writes a rebuilt copy
     * containing only live data, which is then atomically swapped in. Where
     * {@link #compact} rewrites in place, this variant leaves the original
     * untouched until the rebuild has fully succeeded and keeps it as a backup.
     *
     * <p>Safety: the rebuild is written to a sibling temp file and only swapped in
     * if it succeeds; the original is left untouched on any error. A {@code .bak}
     * copy of the original is kept after a successful swap.
     */
    public void compactRewrite() {
        if (dbMissing()) return;
        File db = dbFile();
        File tmp = new File(db.getParentFile(), db.getName() + ".compact");
        File bak = new File(db.getParentFile(), db.getName() + ".bak");
        if (tmp.exists() && !tmp.delete()) {
            System.err.println("Could not remove stale temp file " + tmp.getAbsolutePath());
            return;
        }
        long before = db.length();

        try (Connection conn = open(); Statement st = conn.createStatement()) {
            // Empty the WAL first so the .bak we keep is self-contained, then
            // rebuild into the temp file (VACUUM INTO refuses existing targets,
            // hence the delete above).
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            st.execute("VACUUM INTO '" + tmp.getAbsolutePath().replace("'", "''") + "'");
        } catch (SQLException e) {
            System.err.println("Rewrite failed (original left untouched): " + e.getMessage());
            log.error("compactRewrite failed", e);
            tmp.delete();
            return;
        }

        if (!tmp.exists() || tmp.length() == 0) {
            System.err.println("Rewrite produced no output; original left untouched.");
            tmp.delete();
            return;
        }

        // Swap: original -> .bak, temp -> original. Remove the now-stale WAL
        // sidecars (checkpointed above, so they carry no unmerged data).
        try {
            java.nio.file.Path dbp = db.toPath();
            Files.deleteIfExists(new File(db.getAbsolutePath() + "-wal").toPath());
            Files.deleteIfExists(new File(db.getAbsolutePath() + "-shm").toPath());
            Files.move(dbp, bak.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp.toPath(), dbp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            System.err.println("Swap failed: " + e.getMessage() + " — check " + tmp.getAbsolutePath() + " and " + bak.getAbsolutePath());
            log.error("compactRewrite swap failed", e);
            return;
        }

        long after = db.length();
        System.out.printf("Rewrote %s%n", db.getAbsolutePath());
        System.out.printf("  before: %,d KB%n", before / 1024);
        System.out.printf("  after:  %,d KB%n", after / 1024);
        System.out.printf("  reclaimed %,d KB%n", Math.max(0, (before - after)) / 1024);
        System.out.println("  previous file kept at " + bak.getAbsolutePath() + " (delete once verified)");
    }

    /**
     * Create the secondary B-tree indexes the hot lookups and joins rely on, and
     * refresh planner statistics. Under SQLite these indexes actually serve
     * point-lookup predicates ({@code WHERE artifact_id = ?}, {@code (gid,aid)}
     * equality) — the whole reason for the move off DuckDB, whose ART indexes
     * were ignored for exactly those filters. Covers: artifact lookups by
     * coordinate, forward/reverse dependency traversal, catalogue lookups,
     * and the POM-mining parent-chain / per-POM detail fetches used by
     * {@code graph resolve}.
     */
    public void optimize() {
        if (dbMissing()) return;
        ensureSchema();
        String[] ddl = {
                // graph: artifact-by-coordinate and both directions of edge traversal
                "CREATE INDEX IF NOT EXISTS idx_artifacts_ga ON artifacts(gid, aid)",
                "CREATE INDEX IF NOT EXISTS idx_dependencies_child ON dependencies(child_id)",
                "CREATE INDEX IF NOT EXISTS idx_dependencies_parent ON dependencies(parent_id)",
                // catalogue: artifact-by-coordinate and versions-per-artifact
                "CREATE INDEX IF NOT EXISTS idx_meta_artifacts_ga ON meta_artifacts(gid, aid)",
                "CREATE INDEX IF NOT EXISTS idx_meta_versions_ga ON meta_versions(ga_id)",
                // POM mining: parent-chain walks plus the per-POM detail rows
                // (deps / managed versions / properties) 'graph resolve' loads per node
                "CREATE INDEX IF NOT EXISTS idx_pom_meta_parent ON pom_meta(parent_gid, parent_aid, parent_version)",
                "CREATE INDEX IF NOT EXISTS idx_direct_dep_artifact ON direct_dep(artifact_id)",
                "CREATE INDEX IF NOT EXISTS idx_depmgmt_artifact ON dependency_management(artifact_id)",
                "CREATE INDEX IF NOT EXISTS idx_pom_properties_artifact ON pom_properties(artifact_id)",
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
     * Create (or replace, via drop-and-create — SQLite has no {@code CREATE OR
     * REPLACE VIEW}) convenience views so {@code graph query} can use friendly
     * names instead of hand-written joins:
     * <ul>
     *   <li>{@code gav} — artifacts with a single {@code gid:aid:version} string.</li>
     *   <li>{@code dependents} — every dependency edge flattened to readable coordinates.</li>
     *   <li>{@code version_ranges} — per group:artifact version count and first/last
     *       publish date (the successor to the old {@code range.db} dump).</li>
     *   <li>{@code released} — one normalised row per dated version.</li>
     * </ul>
     */
    public void views() {
        if (dbMissing()) return;
        ensureSchema();
        String[][] viewDdl = {
                {"gav",
                        "CREATE VIEW gav AS " +
                                "SELECT id, gid || ':' || aid || ':' || version AS gav, gid, aid, version, classifier " +
                                "FROM artifacts"},
                {"dependents",
                        "CREATE VIEW dependents AS " +
                                "SELECT c.gid AS dep_gid, c.aid AS dep_aid, c.version AS dep_version, " +
                                "p.gid AS by_gid, p.aid AS by_aid, p.version AS by_version, d.scope " +
                                "FROM dependencies d " +
                                "JOIN artifacts p ON d.parent_id = p.id " +
                                "JOIN artifacts c ON d.child_id = c.id"},
                {"version_ranges", VERSION_RANGES_VIEW},
                // released: one normalised row per dated version. `published` is the
                // ISO-8601 string reshaped to 'YYYY-MM-DD HH:MM:SS[.SSS]' text
                // (trailing 'Z' stripped, 'T' -> space), guarded by a julianday()
                // parse test — in that shape it sorts chronologically and compares
                // lexicographically against datetime('now', ...) output. The
                // 'insights' reports and any time-based ad-hoc query can build on
                // this instead of repeating the normalisation. Versions without a
                // parseable publish date are excluded.
                {"released",
                        "CREATE VIEW released AS " +
                                "SELECT a.gid, a.aid, v.version, " +
                                "replace(replace(v.published, 'Z', ''), 'T', ' ') AS published " +
                                "FROM meta_artifacts a JOIN meta_versions v ON a.id = v.ga_id " +
                                "WHERE julianday(replace(v.published, 'Z', '')) IS NOT NULL"},
        };
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            for (String[] view : viewDdl) {
                st.execute("DROP VIEW IF EXISTS " + view[0]);
                st.execute(view[1]);
            }
            System.out.println("Created views: gav, dependents, version_ranges, released");
            System.out.println("Query them with e.g.  graph query \"SELECT * FROM version_ranges LIMIT 10\"");
        } catch (SQLException e) {
            System.err.println("Creating views failed: " + e.getMessage());
            log.error("Creating views failed", e);
        }
    }

    /**
     * Export tables and the {@code version_ranges} view to files for external
     * analysis (pandas/Polars/etc.). CSV (RFC 4180) and JSON (array of objects)
     * are written app-side over streamed ResultSets. Parquet is no longer
     * supported in-app; for ad-hoc parquet, ATTACH the SQLite file from the
     * standalone duckdb CLI instead.
     *
     * @param format  "csv" (default) or "json".
     * @param outDir  destination directory; created if absent. Null → {@code <cachegenie>/export}.
     */
    public void export(String format, File outDir) {
        if (dbMissing()) return;
        ensureSchema();

        String fmt = (format == null ? "csv" : format.trim().toLowerCase());
        if (fmt.equals("parquet")) {
            System.err.println("Parquet export was removed with the move to SQLite; use csv or json.");
            System.err.println("For ad-hoc parquet, use the standalone duckdb CLI against the SQLite file, e.g.:");
            System.err.println("  duckdb -c \"ATTACH '" + dbFile().getAbsolutePath() + "' AS g (TYPE sqlite); " +
                    "COPY (SELECT * FROM g.artifacts) TO 'artifacts.parquet' (FORMAT PARQUET)\"");
            return;
        }
        boolean json = fmt.equals("json");
        if (!json && !fmt.equals("csv")) {
            System.err.println("Unknown format '" + format + "'. Use csv or json.");
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
            st.execute("DROP VIEW IF EXISTS version_ranges");
            st.execute(VERSION_RANGES_VIEW);

            for (String rel : relations) {
                File out = new File(dir, rel + "." + fmt);
                try (Statement qst = conn.createStatement();
                     ResultSet rs = qst.executeQuery("SELECT * FROM " + rel)) {
                    if (json) {
                        writeJson(rs, out);
                    } else {
                        writeCsv(rs, out);
                    }
                    System.out.println("  wrote " + out.getAbsolutePath());
                } catch (SQLException | IOException e) {
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

    // ------------------------------------------------------------- exporters

    /** RFC 4180 CSV: header row, CRLF records, NULL as an empty field. */
    private static void writeCsv(ResultSet rs, File out) throws SQLException, IOException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        try (BufferedWriter w = Files.newBufferedWriter(out.toPath(), StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= n; i++) {
                if (i > 1) sb.append(',');
                sb.append(csvField(md.getColumnLabel(i)));
            }
            sb.append("\r\n");
            w.write(sb.toString());
            while (rs.next()) {
                sb.setLength(0);
                for (int i = 1; i <= n; i++) {
                    if (i > 1) sb.append(',');
                    Object v = rs.getObject(i);
                    if (v != null) sb.append(csvField(v.toString()));
                }
                sb.append("\r\n");
                w.write(sb.toString());
            }
        }
    }

    /** Quote a CSV field iff it contains a comma, quote, CR or LF; double embedded quotes. */
    private static String csvField(String s) {
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\r') < 0 && s.indexOf('\n') < 0) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    /** JSON array of objects, one per row; keys are column labels, NULL as null. */
    private static void writeJson(ResultSet rs, File out) throws SQLException, IOException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        String[] keys = new String[n];
        for (int i = 0; i < n; i++) keys[i] = jsonString(md.getColumnLabel(i + 1));
        try (BufferedWriter w = Files.newBufferedWriter(out.toPath(), StandardCharsets.UTF_8)) {
            w.write("[");
            boolean first = true;
            StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                sb.setLength(0);
                sb.append(first ? "\n" : ",\n").append("  {");
                first = false;
                for (int i = 1; i <= n; i++) {
                    if (i > 1) sb.append(", ");
                    sb.append(keys[i - 1]).append(": ").append(jsonValue(rs.getObject(i)));
                }
                sb.append("}");
                w.write(sb.toString());
            }
            w.write(first ? "]\n" : "\n]\n");
        }
    }

    private static String jsonValue(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean b) return b.toString();
        if (v instanceof Number num) {
            double d = num.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
            return num.toString();
        }
        return jsonString(v.toString());
    }

    /** JSON string literal with proper escaping (quotes, backslash, control chars). */
    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
