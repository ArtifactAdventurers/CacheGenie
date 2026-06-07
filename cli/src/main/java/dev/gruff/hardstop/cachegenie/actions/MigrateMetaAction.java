package dev.gruff.hardstop.cachegenie.actions;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.MavenMetaData;
import dev.gruff.hardstop.cachegenie.graph.MetaRepository;
import dev.gruff.hardstop.cachegenie.utils.Progress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * One-time importer: loads the legacy on-disk meta files
 * ({@code <gid>:<aid>.properties} at the cachegenie root, and
 * {@code meta/<group>/<aid>/metadata.json}) and writes them into the DuckDB
 * meta tables via {@link MetaRepository}.
 *
 * <p>Coordinates seen in both forms are merged with the JSON form (the newer
 * representation) taking precedence.
 */
public class MigrateMetaAction {
    private static final Logger log = LoggerFactory.getLogger(MigrateMetaAction.class);
    private final CacheGenie cg;

    public MigrateMetaAction(CacheGenie cg) {
        this.cg = cg;
    }

    public void migrate() throws IOException {
        migrate(false);
    }

    /**
     * @param fresh if true, clear the meta tables first for a full clean import;
     *              if false (default), resume — coordinates already committed to
     *              the DB are skipped so an interrupted run doesn't redo work.
     */
    public void migrate(boolean fresh) throws IOException {
        File cacheGenieRoot = cg.cacheGenieRoot();
        MetaRepository repo = new MetaRepository(cacheGenieRoot);

        if (fresh) {
            log.info("--fresh: clearing existing meta tables before import");
            repo.truncateMeta();
        }

        // Coordinates already imported (committed). Used both to resume after an
        // interrupted run and to de-duplicate within this run. A coordinate is in
        // here only once its write committed, so skipping it loses no data.
        Set<String> done = fresh ? new java.util.HashSet<>() : repo.loadCoordinateKeys();
        if (!done.isEmpty()) {
            log.info("Resuming: {} already-imported coordinates will be skipped", done.size());
        }

        // Stream records straight into the DB rather than materialising every
        // record first: there can be hundreds of thousands of meta files.
        // metadata.json is processed FIRST (it's the authoritative form — "JSON
        // wins"); .properties only fills coordinates with no JSON counterpart.
        // This ordering keeps skip-on-resume correct: once a coordinate is in the
        // DB it is final, so it can always be skipped.
        Progress progress = Progress.start("Import meta", 50, 2000L);
        File metaDir = new File(cacheGenieRoot, "meta");
        File[] props = cacheGenieRoot.listFiles((dir, name) -> name.endsWith(".properties"));

        if ((props == null || props.length == 0) && !metaDir.exists()) {
            log.warn("No legacy meta files found under {} — nothing to import", cacheGenieRoot.getAbsolutePath());
            return;
        }

        AtomicInteger skippedExisting = new AtomicInteger();

        try (MetaRepository.BatchWriter writer = repo.openBatch()) {
            // 1. metadata.json under meta/ (authoritative).
            if (metaDir.exists()) {
                final Path metaRoot = metaDir.toPath();
                try (Stream<Path> walk = Files.walk(metaRoot)) {
                    walk.filter(p -> p.getFileName().toString().equals("metadata.json"))
                            .forEach(p -> {
                                // The tree mirrors Maven layout
                                // (meta/<group-as-dirs>/<aid>/metadata.json), so we
                                // can read the coordinate straight off the path and
                                // skip already-imported files WITHOUT opening them.
                                String pathKey = coordFromJsonPath(metaRoot, p);
                                if (pathKey != null && done.contains(pathKey)) {
                                    skippedExisting.incrementAndGet();
                                    return;
                                }
                                MavenMetaData m = MavenMetaData.loadJSON(p.toFile());
                                if (m == null || m.gid == null || m.aid == null) return;
                                String key = m.gid + ":" + m.aid;
                                if (!done.add(key)) {            // already imported or seen this run
                                    skippedExisting.incrementAndGet();
                                    return;
                                }
                                if (writer.writeNew(m)) progress.tick(key);
                            });
                }
            }

            // 2. Legacy .properties at the cachegenie root (fills the gaps).
            if (props != null) {
                for (File f : props) {
                    // Cheap skip: the filename is "<gid>:<aid>.properties", so we
                    // can avoid even reading the file if it's already imported.
                    String nameKey = coordFromPropsName(f.getName());
                    if (nameKey != null && done.contains(nameKey)) {
                        skippedExisting.incrementAndGet();
                        continue;
                    }
                    MavenMetaData m = MavenMetaData.load(f);
                    if (m == null || m.gid == null || m.aid == null) continue;
                    String key = m.gid + ":" + m.aid;
                    if (!done.add(key)) {
                        skippedExisting.incrementAndGet();
                        continue;
                    }
                    if (writer.writeNew(m)) progress.tick(key);
                }
            }

            progress.done();
            log.info("Meta import complete: {} written this run, {} skipped (already imported), {} failed/no coordinates.",
                    writer.written(), skippedExisting.get(), writer.skipped());
        } catch (SQLException e) {
            throw new IOException("Meta migration failed: " + e.getMessage(), e);
        }
    }

    /** Derive "gid:aid" from a "<gid>:<aid>.properties" filename, or null if it doesn't fit. */
    private static String coordFromPropsName(String name) {
        if (!name.endsWith(".properties")) return null;
        String base = name.substring(0, name.length() - ".properties".length());
        return base.contains(":") ? base : null;
    }

    /**
     * Derive "gid:aid" from a Maven-style metadata path under {@code meta/}:
     * {@code meta/org/openlr/openlr/metadata.json} → {@code org.openlr:openlr}.
     * The artifactId is the file's parent directory; the groupId is the dot-joined
     * path segments above it. Returns null if the path is too shallow to split.
     */
    private static String coordFromJsonPath(Path metaRoot, Path jsonFile) {
        Path parent = jsonFile.getParent();
        if (parent == null) return null;
        Path rel = metaRoot.relativize(parent);   // e.g. org/openlr/openlr
        int n = rel.getNameCount();
        if (n < 2) return null;                    // need at least one group segment + artifact
        String aid = rel.getName(n - 1).toString();
        StringBuilder gid = new StringBuilder();
        for (int i = 0; i < n - 1; i++) {
            if (i > 0) gid.append('.');
            gid.append(rel.getName(i).toString());
        }
        return gid + ":" + aid;
    }
}
