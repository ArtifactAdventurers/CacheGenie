package dev.gruff.hardstop.cachegenie.graph;

import org.sqlite.SQLiteConfig;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * The single place CacheGenie opens its SQLite database (see MIGRATION-SQLITE.md).
 * Every connection — reader or writer, any module — comes through here so the
 * PRAGMA discipline is uniform and there is exactly one connection recipe to
 * reason about:
 *
 * <ul>
 *   <li>{@code journal_mode = WAL} — concurrent readers alongside the (single)
 *       writer, replacing the DuckDB-era "reader and writer never open at once"
 *       contortions. Set only on read-write opens (it is a persistent database
 *       property; a read-only connection can't change it and doesn't need to).</li>
 *   <li>{@code synchronous = NORMAL} — the standard WAL pairing: fsync on
 *       checkpoint, not on every commit. A power cut can lose the tail of the
 *       WAL but never corrupts — acceptable for a rebuildable cache.</li>
 *   <li>{@code busy_timeout = 30s} — writers queue behind each other instead of
 *       failing fast with SQLITE_BUSY.</li>
 *   <li>{@code cache_size = 256MB} (cap, not a target — SQLite's memory ceiling
 *       problems are the ones we <em>don't</em> have any more) and
 *       {@code temp_store = FILE} so big sorts spill.</li>
 * </ul>
 *
 * <p>Writers remain single-threaded by design (one writer connection at a time,
 * writes funnelled through it); WAL makes that a politeness rather than the hard
 * correctness rule it was under DuckDB.
 */
public final class Sqlite {

    /** Database filename inside the CacheGenie root, alongside the legacy DuckDB {@code graph.db}. */
    public static final String DB_FILE = "graph.sqlite";

    private Sqlite() {
    }

    /** Absolute path of the SQLite database under {@code cacheGenieRoot}. */
    public static String dbPath(File cacheGenieRoot) {
        return new File(cacheGenieRoot, DB_FILE).getAbsolutePath();
    }

    /** Open read-write (creates the file if absent). */
    public static Connection open(String dbPath) throws SQLException {
        return connect(dbPath, false);
    }

    /** Open read-only (fails if the file doesn't exist yet). */
    public static Connection openReadOnly(String dbPath) throws SQLException {
        return connect(dbPath, true);
    }

    private static Connection connect(String dbPath, boolean readOnly) throws SQLException {
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.setReadOnly(readOnly);
        if (!readOnly) {
            cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);
            cfg.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        }
        cfg.setBusyTimeout(30_000);
        cfg.setCacheSize(-262_144); // KiB, negative = size-based: 256MB page-cache cap
        cfg.setTempStore(SQLiteConfig.TempStore.FILE);
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath, cfg.toProperties());
    }
}
