package dev.gruff.hardstop.cachegenie.actions;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.actions.index.FileWritableResourceHandler;
import dev.gruff.hardstop.cachegenie.actions.index.HttpResourceHandler;
import dev.gruff.hardstop.cachegenie.graph.MetaRepository;
import dev.gruff.hardstop.cachegenie.utils.Progress;
import org.apache.maven.index.reader.ChunkReader;
import org.apache.maven.index.reader.IndexReader;
import org.apache.maven.index.reader.Record;
import org.apache.maven.index.reader.RecordExpander;
import org.apache.maven.index.reader.WritableResourceHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Bulk/incremental discovery via Maven Central's published index (the
 * maven-indexer format) instead of crawling HTML listings. Far fewer requests:
 * a first run downloads the full index; later runs pull only the incremental
 * chunks published since. Records are streamed straight into the DuckDB meta
 * tables, so the live HTML crawl ({@code scan}) is only needed for targeted
 * {@code --gav} lookups.
 *
 * <p>Local sync state (the index {@code .properties}) lives under
 * {@code ~/.m2/cachegenie/work/indexer}; deleting it (or {@code --full}) forces a
 * full re-sync.
 *
 * <p>The incremental path is <b>fully incremental</b>: each chunk is streamed
 * into the DuckDB staging table (constant memory), merged set-based, and the
 * local sync state is advanced past that chunk before the next one starts. A
 * killed or failed run resumes at the first unprocessed chunk instead of
 * starting the whole catch-up over. We therefore manage the state file
 * ourselves and only let {@link IndexReader#close()} run on full success — its
 * close() unconditionally stores the complete remote state, which on a failed
 * run would silently mark unprocessed chunks as done.
 */
public class IndexerSyncAction {
    private static final Logger log = LoggerFactory.getLogger(IndexerSyncAction.class);
    /** Name of the index properties file, both remote and as our local sync state. */
    private static final String INDEX_PROPERTIES = "nexus-maven-repository-index.properties";
    private final CacheGenie cg;
    /** Last incremental chunk checkpointed by this run, if any (for the failure hint). */
    private String lastCheckpoint;

    public IndexerSyncAction(CacheGenie cg) {
        this.cg = cg;
    }

    /**
     * @param full      if true, ignore local state and re-pull the full index.
     * @param limit     stop after this many records (0 = no limit). When &gt; 0, local
     *                  sync state is NOT persisted (so a smoke-test never advances the
     *                  incremental position), and the full path is always used.
     * @param memLimit   DuckDB memory cap for the staging merge (e.g. "8GB"; null =
     *                   DuckDB default ~80% RAM). A full-bootstrap merge over tens of
     *                   millions of staged records needs this to leave the JVM headroom.
     * @param dbThreads  DuckDB worker-thread cap for the merge (0 = default).
     * @param mergeBatch on the full path, merge into the meta tables every this many
     *                   staged records instead of once at the end (0 = single merge).
     *                   Bounds peak merge memory by batch size, not pull size — the
     *                   difference between finishing and an OOM-kill on small-RAM boxes.
     */
    public void sync(boolean full, long limit, String memLimit, int dbThreads, long mergeBatch) {
        URI indexBase = cg.base().resolve(".index/");
        File stateDir = new File(cg.work(), "indexer");
        if (full) {
            clearDir(stateDir);
            log.info("--full: cleared local index state, will re-pull the full index");
        }

        MetaRepository repo = new MetaRepository(cg.cacheGenieRoot());
        Progress progress = Progress.start("Index sync");

        boolean stateful = limit == 0;
        HttpResourceHandler remote = new HttpResourceHandler(indexBase);
        // A limited (smoke-test) run passes a null local handler: incremental
        // detection is then off, the run uses the full path, and no state is kept.
        WritableResourceHandler local = stateful ? new FileWritableResourceHandler(stateDir) : null;

        IndexReader reader = null;
        boolean completed = false;
        try {
            reader = new IndexReader(local, remote);

            log.info("Remote index '{}' published {} (incremental={}, {} chunk(s) to fetch)",
                    reader.getIndexId(), reader.getPublishedTimestamp(), reader.isIncremental(),
                    reader.getChunkNames().size());

            if (reader.getChunkNames().isEmpty()) {
                System.out.println("Index sync: already up to date (no new chunks).");
            } else if (reader.isIncremental()) {
                incrementalSync(reader, repo, progress, limit,
                        stateful ? stateDir : null, loadRemoteProperties(remote), memLimit, dbThreads);
            } else {
                fullSync(reader, repo, progress, limit, memLimit, dbThreads, mergeBatch);
            }
            progress.done();
            completed = true;
        } catch (Exception e) {
            System.err.println("Index sync failed: " + e.getMessage());
            if (lastCheckpoint != null) {
                System.err.printf("Progress is saved up to incremental chunk %s; re-run to resume from the next one.%n",
                        lastCheckpoint);
            }
            log.error("Index sync failed", e);
        } finally {
            // IndexReader.close() stores the FULL remote properties as our local
            // state, i.e. "everything is synced". Only true on a completed run: on
            // an aborted incremental run the per-chunk state write has already
            // recorded exactly how far we got, and close() would overwrite it,
            // silently skipping the unprocessed chunks on the next run. (Sanctioned
            // by the IndexReader javadoc: "if aborted ... this method should NOT be
            // invoked".) Both our ResourceHandlers are stateless (no-op close), so
            // skipping close() on failure leaks nothing.
            if (completed && reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    log.warn("closing index reader failed", e);
                }
            }
        }
    }

    /**
     * Full bootstrap: bulk-load ADD records into staging via the Appender and merge
     * set-based — every {@code mergeBatch} staged records (bounding peak merge memory
     * by batch size, not pull size), or once at the end when {@code mergeBatch} is 0.
     * Merging is idempotent and largest-file-wins across batches (the merge upgrades
     * an existing version row when the staged file is strictly larger), so batching
     * only changes memory shape, not the result. (A full pull never contains removals.)
     */
    private void fullSync(IndexReader reader, MetaRepository repo, Progress progress, long limit,
                          String memLimit, int dbThreads, long mergeBatch) throws Exception {
        RecordExpander expander = new RecordExpander();
        long records = 0;
        long inBatch = 0;
        int batch = 1;
        long newArtifacts = 0;
        long newVersions = 0;
        MetaRepository.IndexStageLoader loader = repo.openIndexStage(memLimit, dbThreads);
        try {
            outer:
            for (ChunkReader chunk : reader) {
                try (chunk) {
                    for (Map<String, String> raw : chunk) {
                        Record r = expander.apply(raw);
                        if (r.getType() != Record.Type.ARTIFACT_ADD) continue;
                        String g = r.getString(Record.GROUP_ID);
                        String a = r.getString(Record.ARTIFACT_ID);
                        String v = r.getString(Record.VERSION);
                        if (g == null || a == null || v == null) continue;
                        // Only main-artifact records (no classifier): the sources/javadoc/etc.
                        // files are redundant for the per-version model and the main record
                        // already carries the has_sources/has_javadoc flags.
                        String classifier = r.getString(Record.CLASSIFIER);
                        if (classifier != null && !classifier.isEmpty()) continue;
                        loader.append(g, a, v,
                                r.getString(Record.PACKAGING),
                                r.getString(Record.FILE_EXTENSION),
                                r.getLong(Record.FILE_SIZE),
                                r.getString(Record.SHA1),
                                r.getString(Record.SHA_256),
                                r.getBoolean(Record.HAS_SOURCES),
                                r.getBoolean(Record.HAS_JAVADOC),
                                r.getLong(Record.FILE_MODIFIED));
                        records++;
                        inBatch++;
                        progress.tick(g + ":" + a);
                        if (mergeBatch > 0 && inBatch >= mergeBatch) {
                            System.out.printf("Merging batch %d (%d staged, %d total so far)…%n",
                                    batch, inBatch, records);
                            long[] m = loader.merge();
                            loader.close();
                            newArtifacts += m[0];
                            newVersions += m[1];
                            batch++;
                            inBatch = 0;
                            loader = repo.openIndexStage(memLimit, dbThreads);
                        }
                        if (limit > 0 && records >= limit) {
                            System.out.printf("--limit %d reached; stopping the crawl.%n", limit);
                            break outer;
                        }
                    }
                }
            }
            if (inBatch > 0 || batch == 1) {
                System.out.printf("Staged %d records total, merging %s…%n",
                        records, batch == 1 ? "" : "final batch " + batch);
                long[] m = loader.merge();
                newArtifacts += m[0];
                newVersions += m[1];
            }
        } finally {
            loader.close();
        }
        System.out.printf(
                "Index sync (full) complete: %d records staged in %d merge batch(es), "
                        + "%d new artifacts, %d new/upgraded versions%n",
                records, batch, newArtifacts, newVersions);
        System.out.println("Tip: run 'db compact --rewrite' to reclaim the space the staging table used.");
    }

    /**
     * Incremental catch-up, one chunk at a time in constant memory. Each chunk's
     * ADD records are streamed straight into the DuckDB staging table (the same
     * set-based merge as the full bootstrap dedups per version, largest
     * main-artifact file wins), its few REMOVE records are applied afterwards,
     * and the local sync state is advanced past the chunk before the next one
     * starts, so a killed/failed run resumes rather than restarts.
     *
     * <p>The previous implementation buffered every ADD of every chunk in one
     * in-memory map before writing anything: a multi-month catch-up (millions of
     * records) blew the heap and got the process OOM-killed — and, with no state
     * persisted, the retry started from scratch and died the same way.
     *
     * <p>Chunks apply in publication order, so add/remove sequencing across
     * chunks is honoured (previously all adds were applied, then all removes,
     * regardless of chunk order).
     *
     * @param stateDir directory for the per-chunk sync-state checkpoint, or null
     *                 to not persist state (limited smoke-test runs).
     */
    private void incrementalSync(IndexReader reader, MetaRepository repo, Progress progress, long limit,
                                 File stateDir, Properties remoteProps,
                                 String memLimit, int dbThreads) throws Exception {
        RecordExpander expander = new RecordExpander();
        int totalChunks = reader.getChunkNames().size();
        long seen = 0;
        long totalAdds = 0;
        long totalNewVersions = 0;
        long totalRemovals = 0;
        int chunksDone = 0;
        boolean limitHit = false;

        for (ChunkReader chunk : reader) {
            try (chunk) {
                long chunkAdds = 0;
                long[] merged = {0, 0};
                List<String[]> removes = new ArrayList<>();
                try (MetaRepository.IndexStageLoader loader = repo.openIndexStage(memLimit, dbThreads)) {
                    for (Map<String, String> raw : chunk) {
                        Record r = expander.apply(raw);
                        Record.Type type = r.getType();
                        String g = r.getString(Record.GROUP_ID);
                        String a = r.getString(Record.ARTIFACT_ID);
                        String v = r.getString(Record.VERSION);
                        String classifier = r.getString(Record.CLASSIFIER);
                        boolean main = (classifier == null || classifier.isEmpty());
                        if (main && g != null && a != null && v != null) {
                            if (type == Record.Type.ARTIFACT_ADD) {
                                loader.append(g, a, v,
                                        r.getString(Record.PACKAGING),
                                        r.getString(Record.FILE_EXTENSION),
                                        r.getLong(Record.FILE_SIZE),
                                        r.getString(Record.SHA1),
                                        r.getString(Record.SHA_256),
                                        r.getBoolean(Record.HAS_SOURCES),
                                        r.getBoolean(Record.HAS_JAVADOC),
                                        r.getLong(Record.FILE_MODIFIED));
                                chunkAdds++;
                                progress.tick(g + ":" + a);
                            } else if (type == Record.Type.ARTIFACT_REMOVE) {
                                removes.add(new String[]{g, a, v});
                            }
                        }
                        if (limit > 0 && ++seen >= limit) {
                            limitHit = true;
                            break;
                        }
                    }
                    if (chunkAdds > 0) {
                        merged = loader.merge();
                    }
                }
                // Removals (ARTIFACT_REMOVE) are typically few; apply them row-by-row,
                // then refresh the affected artifacts' latest/release set-based.
                if (!removes.isEmpty()) {
                    try (MetaRepository.IndexSyncWriter w = repo.openIndexSync()) {
                        for (String[] rm : removes) {
                            w.removeVersion(rm[0], rm[1], rm[2]);
                        }
                        w.refreshSummaries();
                    }
                }
                totalAdds += chunkAdds;
                totalNewVersions += merged[1];
                totalRemovals += removes.size();
                chunksDone++;
                // Advance the local sync position past this chunk BEFORE starting the
                // next: this is the resume checkpoint. Skipped when unlimited state
                // keeping is off (stateDir null) or the limit tripped mid-chunk (the
                // chunk was only partially applied, so it must be re-pulled).
                if (stateDir != null && !limitHit) {
                    String counter = chunkCounter(chunk.getName());
                    writeSyncState(stateDir, remoteProps, counter);
                    lastCheckpoint = counter;
                }
                System.out.printf("  chunk %s [%d/%d]: %d adds (%d new versions), %d removals%n",
                        chunk.getName(), chunksDone, totalChunks, chunkAdds, merged[1], removes.size());
                if (limitHit) {
                    System.out.printf("--limit %d reached; stopping.%n", limit);
                    break;
                }
            }
        }
        System.out.printf(
                "Index sync (incremental) complete: %d chunk(s), %d adds, %d new versions added, %d removals%n",
                chunksDone, totalAdds, totalNewVersions, totalRemovals);
    }

    /** Fetch the remote index {@code .properties} (tiny; one extra GET). */
    private static Properties loadRemoteProperties(HttpResourceHandler remote) throws IOException {
        Properties props = new Properties();
        try (InputStream in = remote.locate(INDEX_PROPERTIES).read()) {
            if (in == null) {
                throw new IOException("remote index properties not found: " + INDEX_PROPERTIES);
            }
            props.load(in);
        }
        return props;
    }

    /**
     * Persist the local sync state as "synced up to incremental chunk
     * {@code lastIncremental}": the remote properties with
     * {@code nexus.index.last-incremental} overridden. That is exactly what
     * {@link IndexReader} reads back — incremental resume only needs
     * {@code nexus.index.id}, {@code nexus.index.chain-id} and
     * {@code nexus.index.last-incremental} to match up (indexer-reader 7.1.6,
     * {@code canRetrieveAllChunks}/{@code calculateChunkNames}). Written to a temp
     * file and atomically renamed so a kill mid-write can't corrupt the state.
     */
    private static void writeSyncState(File stateDir, Properties remoteProps, String lastIncremental)
            throws IOException {
        Properties state = new Properties();
        state.putAll(remoteProps);
        state.setProperty("nexus.index.last-incremental", lastIncremental);
        //noinspection ResultOfMethodCallIgnored
        stateDir.mkdirs();
        Path target = new File(stateDir, INDEX_PROPERTIES).toPath();
        Path tmp = new File(stateDir, INDEX_PROPERTIES + ".tmp").toPath();
        try (OutputStream out = Files.newOutputStream(tmp)) {
            state.store(out, "CacheGenie index-sync state (per-chunk checkpoint)");
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** {@code "nexus-maven-repository-index.545.gz"} → {@code "545"}. */
    private static String chunkCounter(String chunkName) {
        int first = chunkName.indexOf('.');
        int last = chunkName.lastIndexOf('.');
        if (first < 0 || last <= first) {
            throw new IllegalArgumentException("unexpected chunk name: " + chunkName);
        }
        return chunkName.substring(first + 1, last);
    }

    private static void clearDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
