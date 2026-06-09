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
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 */
public class IndexerSyncAction {
    private static final Logger log = LoggerFactory.getLogger(IndexerSyncAction.class);
    private final CacheGenie cg;

    public IndexerSyncAction(CacheGenie cg) {
        this.cg = cg;
    }

    /**
     * @param full  if true, ignore local state and re-pull the full index.
     * @param limit stop after this many records (0 = no limit). When &gt; 0, local
     *              sync state is NOT persisted (so a smoke-test never advances the
     *              incremental position), and the full path is always used.
     */
    public void sync(boolean full, long limit) {
        URI indexBase = cg.base().resolve(".index/");
        File stateDir = new File(cg.work(), "indexer");
        if (full) {
            clearDir(stateDir);
            log.info("--full: cleared local index state, will re-pull the full index");
        }

        MetaRepository repo = new MetaRepository(cg.cacheGenieRoot());
        Progress progress = Progress.start("Index sync");

        // A limited (smoke-test) run passes a null local handler: IndexReader.close()
        // then won't advance our incremental position, and the run uses the full path.
        WritableResourceHandler local = (limit > 0) ? null : new FileWritableResourceHandler(stateDir);
        try (IndexReader reader = new IndexReader(local, new HttpResourceHandler(indexBase))) {

            log.info("Remote index '{}' published {} (incremental={}, {} chunk(s) to fetch)",
                    reader.getIndexId(), reader.getPublishedTimestamp(), reader.isIncremental(),
                    reader.getChunkNames().size());

            if (reader.getChunkNames().isEmpty()) {
                System.out.println("Index sync: already up to date (no new chunks).");
                progress.done();
                return;
            }

            if (reader.isIncremental()) {
                incrementalSync(reader, repo, progress, limit);
            } else {
                fullSync(reader, repo, progress, limit);
            }
        } catch (Exception e) {
            System.err.println("Index sync failed: " + e.getMessage());
            log.error("Index sync failed", e);
            return;
        }

        progress.done();
    }

    /**
     * Full bootstrap: bulk-load every ADD record into staging via the Appender,
     * then merge set-based. (A full pull never contains removals.)
     */
    private void fullSync(IndexReader reader, MetaRepository repo, Progress progress, long limit) throws Exception {
        RecordExpander expander = new RecordExpander();
        long records = 0;
        long[] merged;
        try (MetaRepository.IndexStageLoader loader = repo.openIndexStage()) {
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
                        progress.tick(g + ":" + a);
                        if (limit > 0 && records >= limit) {
                            System.out.printf("--limit %d reached; stopping the crawl.%n", limit);
                            break outer;
                        }
                    }
                }
            }
            System.out.printf("Staged %d records, merging…%n", records);
            merged = loader.merge();
        }
        System.out.printf(
                "Index sync (full) complete: %d records staged, %d new artifacts, %d new versions added%n",
                records, merged[0], merged[1]);
        System.out.println("Tip: run 'db compact --rewrite' to reclaim the space the staging table used.");
    }

    /**
     * Incremental update: a small diff. Records are grouped in memory by version
     * (keeping the largest main-artifact file, like the full path's merge) and then
     * applied; ARTIFACT_REMOVE records are honoured.
     */
    private void incrementalSync(IndexReader reader, MetaRepository repo, Progress progress, long limit) throws Exception {
        RecordExpander expander = new RecordExpander();
        Map<String, Main> mains = new HashMap<>();
        List<String[]> removes = new ArrayList<>();
        long seen = 0;
        outer:
        for (ChunkReader chunk : reader) {
            try (chunk) {
                for (Map<String, String> raw : chunk) {
                    Record r = expander.apply(raw);
                    Record.Type type = r.getType();
                    String g = r.getString(Record.GROUP_ID);
                    String a = r.getString(Record.ARTIFACT_ID);
                    String v = r.getString(Record.VERSION);
                    String classifier = r.getString(Record.CLASSIFIER);
                    boolean main = (classifier == null || classifier.isEmpty());
                    if (type == Record.Type.ARTIFACT_ADD) {
                        if (g == null || a == null || v == null || !main) continue;
                        Long size = r.getLong(Record.FILE_SIZE);
                        String key = g + ":" + a + ":" + v;
                        Main cur = mains.get(key);
                        long sz = size != null ? size : 0;
                        if (cur == null || sz > cur.sizeOrZero()) {
                            mains.put(key, new Main(g, a, v,
                                    r.getString(Record.PACKAGING), r.getString(Record.FILE_EXTENSION), size,
                                    r.getString(Record.SHA1), r.getString(Record.SHA_256),
                                    r.getBoolean(Record.HAS_SOURCES), r.getBoolean(Record.HAS_JAVADOC),
                                    r.getLong(Record.FILE_MODIFIED)));
                        }
                        progress.tick(g + ":" + a);
                    } else if (type == Record.Type.ARTIFACT_REMOVE) {
                        if (main && g != null && a != null && v != null) removes.add(new String[]{g, a, v});
                    }
                    if (limit > 0 && ++seen >= limit) {
                        System.out.printf("--limit %d reached; stopping.%n", limit);
                        break outer;
                    }
                }
            }
        }

        long newVersions = 0;
        java.util.Set<String> touched = new java.util.HashSet<>();
        try (MetaRepository.IndexSyncWriter w = repo.openIndexSync()) {
            for (Main m : mains.values()) {
                if (w.addVersion(m.g, m.a, m.v, m.published, m.packaging, m.ext, m.size, m.sha1, m.sha256, m.hasSources, m.hasJavadoc)) {
                    newVersions++;
                }
                touched.add(m.g + " " + m.a);
            }
            for (String[] rm : removes) {
                w.removeVersion(rm[0], rm[1], rm[2]);
                touched.add(rm[0] + " " + rm[1]);
            }
            // Refresh generated/latest/release for each artifact we touched.
            for (String key : touched) {
                int nul = key.indexOf(' ');
                w.refreshSummary(key.substring(0, nul), key.substring(nul + 1));
            }
        }
        System.out.printf(
                "Index sync (incremental) complete: %d versions, %d new versions added, %d removals%n",
                mains.size(), newVersions, removes.size());
    }

    /** Holder for a version's chosen main-artifact fields during incremental grouping. */
    private static final class Main {
        final String g, a, v, packaging, ext, sha1, sha256;
        final Long size, published;
        final Boolean hasSources, hasJavadoc;

        Main(String g, String a, String v, String packaging, String ext, Long size, String sha1, String sha256,
             Boolean hasSources, Boolean hasJavadoc, Long published) {
            this.g = g; this.a = a; this.v = v; this.packaging = packaging; this.ext = ext; this.size = size;
            this.sha1 = sha1; this.sha256 = sha256; this.hasSources = hasSources; this.hasJavadoc = hasJavadoc;
            this.published = published;
        }

        long sizeOrZero() {
            return size != null ? size : 0;
        }
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
