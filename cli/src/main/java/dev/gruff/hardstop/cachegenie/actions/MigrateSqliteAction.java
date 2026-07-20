package dev.gruff.hardstop.cachegenie.actions;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.graph.GraphRepository;
import dev.gruff.hardstop.cachegenie.graph.MetaRepository;
import dev.gruff.hardstop.cachegenie.graph.Sqlite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * One-off copier from the legacy DuckDB {@code graph.db} to the SQLite
 * {@code graph.sqlite} (see MIGRATION-SQLITE.md Phase 1). This is the only place
 * left that touches DuckDB; once run (and verified), both this command and the
 * {@code duckdb_jdbc} dependency can be deleted.
 *
 * <p>Copies the base tables with <b>ids preserved</b> (explicit id inserts into
 * the {@code INTEGER PRIMARY KEY} rowid aliases keep every FK-by-convention
 * intact), in batched transactions, then {@code ANALYZE}s and checkpoints.
 * Staging tables ({@code idx_stage}, {@code mine_s_*}, …) are deliberately not
 * copied. The DuckDB file is opened read-only and never modified — it remains
 * the fallback until the SQLite DB is proven.
 */
public class MigrateSqliteAction {
    private static final Logger log = LoggerFactory.getLogger(MigrateSqliteAction.class);

    /** Base tables, in an order that keeps id-referencing tables after their id owners. */
    private static final String[] TABLES = {
            "meta_artifacts", "meta_versions",
            "artifacts", "dependencies",
            "pom_meta", "direct_dep", "dependency_management",
            "pom_properties", "pom_developers", "pom_licenses",
    };

    private static final int BATCH = 5_000;

    private final CacheGenie cg;

    public MigrateSqliteAction(CacheGenie cg) {
        this.cg = cg;
    }

    /**
     * @param force delete an existing graph.sqlite (+ WAL sidecars) first.
     * @return true on success (all tables copied and row counts verified).
     */
    public boolean migrate(boolean force) {
        File root = cg.cacheGenieRoot();
        File src = new File(root, "graph.db");
        if (!src.exists()) {
            System.err.println("No DuckDB database at " + src.getAbsolutePath() + " — nothing to migrate.");
            return false;
        }
        File dest = new File(Sqlite.dbPath(root));
        if (dest.exists() && force) {
            for (String suffix : new String[]{"", "-wal", "-shm"}) {
                File f = new File(dest.getPath() + suffix);
                if (f.exists() && !f.delete()) {
                    System.err.println("Could not delete " + f.getAbsolutePath());
                    return false;
                }
            }
        } else if (dest.exists()) {
            // Resume mode: tables whose destination row count already matches the
            // source are skipped; partially-copied tables are wiped and re-copied.
            System.out.println(dest.getName() + " exists — resuming (already-complete tables are skipped; --force starts over).");
        }

        // Create the SQLite schema (tables + day-one indexes) through the repositories,
        // so the copy target is exactly what the code expects.
        new MetaRepository(root);
        new GraphRepository(root);

        Properties ro = new Properties();
        ro.setProperty("duckdb.read_only", "true");
        // CRITICAL: DuckDB's JDBC driver MATERIALISES every query result in native
        // memory unless streaming is opted into. A full-table SELECT over a wide
        // multi-million-row table (pom_meta) materialised whole got the process
        // OOM-killed on the 8GB Pi — twice, at the same spot, independent of -Xmx.
        ro.setProperty("jdbc_stream_results", "true");
        long t0 = System.currentTimeMillis();
        boolean ok = true;
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:" + src.getAbsolutePath(), ro);
             Connection lite = Sqlite.open(dest.getAbsolutePath())) {
            // Cap DuckDB's buffer manager: its default is ~80% of RAM, and on an 8GB
            // box the page cache it accumulates while streaming the big tables —
            // stacked on the JVM — trips the OS OOM-killer (observed right after the
            // 117M-row dependencies copy). Reads stream fine within 1GB.
            try (Statement st = duck.createStatement()) {
                st.execute("SET memory_limit = '1GB'");
                st.execute("SET threads = 1");
            } catch (SQLException e) {
                log.warn("could not cap DuckDB memory (continuing): {}", e.getMessage());
            }
            lite.setAutoCommit(false);
            for (String table : TABLES) {
                ok &= copyTable(duck, lite, table);
            }
            lite.commit();
            try (Statement st = lite.createStatement()) {
                st.execute("ANALYZE");
            }
            lite.commit();
            // Checkpoint must run OUTSIDE a transaction or it is a silent no-op
            // (same pattern as IndexStageLoader.merge()).
            lite.setAutoCommit(true);
            try (Statement st = lite.createStatement()) {
                st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
        } catch (SQLException e) {
            log.error("migration failed", e);
            System.err.println("Migration failed: " + e.getMessage());
            return false;
        }
        System.out.printf("Migration %s in %ds. The DuckDB file was not modified; keep %s as a fallback until you're happy, then delete it.%n",
                ok ? "complete" : "finished WITH ERRORS (see above)",
                (System.currentTimeMillis() - t0) / 1000, src.getName());
        return ok;
    }

    /** Copy one table over the intersection of source/destination columns; verify counts. */
    private boolean copyTable(Connection duck, Connection lite, String table) throws SQLException {
        // Destination columns (authoritative: the schema the code creates).
        List<String> destCols = new ArrayList<>();
        try (Statement st = lite.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + table + " LIMIT 0")) {
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) destCols.add(md.getColumnName(i).toLowerCase());
        }
        // Source columns; skip tables absent from the source DB entirely.
        List<String> srcCols;
        try (Statement st = duck.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + table + " LIMIT 0")) {
            ResultSetMetaData md = rs.getMetaData();
            srcCols = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) srcCols.add(md.getColumnName(i).toLowerCase());
        } catch (SQLException e) {
            // Only a genuinely missing table is skippable; anything else must fail loudly.
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("does not exist") || msg.contains("Catalog Error")) {
                System.out.printf("  %-24s absent in source, skipped%n", table);
                return true;
            }
            throw e;
        }
        List<String> cols = new ArrayList<>(destCols);
        cols.retainAll(srcCols);
        if (cols.isEmpty()) {
            System.err.printf("  %-24s no common columns?!%n", table);
            return false;
        }

        // Resume support: skip tables already fully copied; wipe and redo partial ones
        // (batches commit as they go, so a killed run leaves a clean prefix).
        long srcCount = count(duck, table);
        long already = count(lite, table);
        if (already == srcCount) {
            System.out.printf("  %-24s already migrated (%,d rows), skipped%n", table, already);
            return true;
        }
        if (already > 0) {
            System.out.printf("  %-24s partial (%,d of %,d rows) — wiping and re-copying%n", table, already, srcCount);
            try (Statement st = lite.createStatement()) {
                st.execute("DELETE FROM " + table);
            }
            lite.commit();
        }
        List<String> srcOnly = new ArrayList<>(srcCols);
        srcOnly.removeAll(cols);
        if (!srcOnly.isEmpty()) {
            System.out.printf("  %-24s note: source columns not in destination (dropped): %s%n", table, srcOnly);
        }

        String colList = String.join(", ", cols);
        String placeholders = String.join(", ", java.util.Collections.nCopies(cols.size(), "?"));
        long copied = 0;
        try (Statement read = duck.createStatement();
             ResultSet rs = read.executeQuery("SELECT " + colList + " FROM " + table);
             PreparedStatement ins = lite.prepareStatement(
                     "INSERT INTO " + table + " (" + colList + ") VALUES (" + placeholders + ")")) {
            int pending = 0;
            while (rs.next()) {
                for (int i = 1; i <= cols.size(); i++) {
                    Object v = rs.getObject(i);
                    if (v instanceof Boolean b) {
                        ins.setBoolean(i, b); // store 0/1, the convention the new code reads
                    } else {
                        ins.setObject(i, v);  // String/Integer/Long/null pass through
                    }
                }
                ins.addBatch();
                if (++pending >= BATCH) {
                    ins.executeBatch();
                    lite.commit();
                    copied += pending;
                    pending = 0;
                    if (copied % 1_000_000 == 0) {
                        System.out.printf("  %-24s ... %,d rows%n", table, copied);
                    }
                }
            }
            if (pending > 0) {
                ins.executeBatch();
                lite.commit();
                copied += pending;
            }
        }

        long destCount = count(lite, table);
        boolean match = srcCount == destCount && srcCount == copied;
        System.out.printf("  %-24s %,d rows copied (source %,d, destination %,d)%s%n",
                table, copied, srcCount, destCount, match ? "" : "  << MISMATCH");
        return match;
    }

    private static long count(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
