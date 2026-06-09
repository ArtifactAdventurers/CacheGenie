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

    /** @param full if true, ignore local state and re-pull the full index. */
    public void sync(boolean full) {
        URI indexBase = cg.base().resolve(".index/");
        File stateDir = new File(cg.work(), "indexer");
        if (full) {
            clearDir(stateDir);
            log.info("--full: cleared local index state, will re-pull the full index");
        }

        MetaRepository repo = new MetaRepository(cg.cacheGenieRoot());
        RecordExpander expander = new RecordExpander();
        Progress progress = Progress.start("Index sync");
        long[] c = {0, 0, 0}; // [0]=ADD records, [1]=new versions, [2]=REMOVE records

        WritableResourceHandler local = new FileWritableResourceHandler(stateDir);
        try (MetaRepository.IndexSyncWriter w = repo.openIndexSync();
             IndexReader reader = new IndexReader(local, new HttpResourceHandler(indexBase))) {

            log.info("Remote index '{}' published {} (incremental={}, {} chunk(s) to fetch)",
                    reader.getIndexId(), reader.getPublishedTimestamp(), reader.isIncremental(),
                    reader.getChunkNames().size());

            if (reader.getChunkNames().isEmpty()) {
                System.out.println("Index sync: already up to date (no new chunks).");
                progress.done();
                return;
            }

            for (ChunkReader chunk : reader) {
                try (chunk) {
                    for (Map<String, String> raw : chunk) {
                        Record r = expander.apply(raw);
                        Record.Type type = r.getType();
                        if (type == Record.Type.ARTIFACT_ADD) {
                            String g = r.getString(Record.GROUP_ID);
                            String a = r.getString(Record.ARTIFACT_ID);
                            String v = r.getString(Record.VERSION);
                            if (g == null || a == null || v == null) continue;
                            Long fileModified = r.getLong(Record.FILE_MODIFIED);
                            if (w.addVersion(g, a, v, fileModified)) c[1]++;
                            c[0]++;
                            progress.tick(g + ":" + a);
                        } else if (type == Record.Type.ARTIFACT_REMOVE) {
                            String g = r.getString(Record.GROUP_ID);
                            String a = r.getString(Record.ARTIFACT_ID);
                            String v = r.getString(Record.VERSION);
                            w.removeVersion(g, a, v);
                            c[2]++;
                        }
                        // DESCRIPTOR / ALL_GROUPS / ROOT_GROUPS are ignored.
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Index sync failed: " + e.getMessage());
            log.error("Index sync failed", e);
            return;
        }

        progress.done();
        System.out.printf(
                "Index sync complete: %d artifact records processed, %d new versions added, %d removals%n",
                c[0], c[1], c[2]);
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
