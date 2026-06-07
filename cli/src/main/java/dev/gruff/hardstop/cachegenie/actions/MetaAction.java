package dev.gruff.hardstop.cachegenie.actions;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.MavenMetaData;
import dev.gruff.hardstop.cachegenie.graph.MetaRepository;
import dev.gruff.hardstop.cachegenie.utils.Progress;
import dev.gruff.hardstop.resolver.Resolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MetaAction {
    private static final Logger log = LoggerFactory.getLogger(MetaAction.class);
    private final CacheGenie cg;
    private final Resolver resolver;
    private final MetaRepository metaRepo;

    public MetaAction(CacheGenie cg) {
        this.cg = cg;
        this.resolver = Resolver.Builder(cg).build();
        this.metaRepo = new MetaRepository(cg.cacheGenieRoot());
    }

    public void downloadMissingPoms(List<String> gavs, boolean retryMissing) {

        List<MavenMetaData> metas = new ArrayList<>();
        if (gavs != null && !gavs.isEmpty()) {
            for (String gav : gavs) {
                String[] parts = gav.split(":");
                if (parts.length >= 2) {
                    MavenMetaData m = metaRepo.load(parts[0], parts[1]);
                    if (m != null) metas.add(m);
                }
            }
        } else {
            metas = metaRepo.loadAll();
        }

        if (metas.isEmpty()) return;

        Progress progress = Progress.start("Fetch POMs");

        // Resolution (network) runs in parallel; MetaRepository write methods are
        // synchronized, so the missing/found updates serialise safely.
        metas.parallelStream().forEach(meta -> {
            if (meta == null || meta.gid == null || meta.aid == null) return;

            log.info("Checking poms for {}:{}", meta.gid, meta.aid);
            progress.tick(meta.gid + ":" + meta.aid);

            meta.versions.keySet().forEach(version -> {
                if (!retryMissing && meta.isMissingPom(version)) {
                    log.debug("  Skipping known missing pom for {}:{}:{}", meta.gid, meta.aid, version);
                    return;
                }

                if (isPomMissing(meta.gid, meta.aid, version)) {
                    log.info("  Downloading missing pom for {}:{}:{}", meta.gid, meta.aid, version);
                    boolean found = resolver.resolvePOM(meta.gid + ":" + meta.aid + ":" + version);
                    if (!found) {
                        log.warn("  Could not find pom for {}:{}:{}", meta.gid, meta.aid, version);
                        metaRepo.markPomMissing(meta.gid, meta.aid, version);
                    } else if (meta.isMissingPom(version)) {
                        log.info("  Found previously missing pom for {}:{}:{}", meta.gid, meta.aid, version);
                        metaRepo.markPomFound(meta.gid, meta.aid, version);
                    }
                }
            });
        });

        progress.done();
    }

    private boolean isPomMissing(String gid, String aid, String version) {
        File repoRoot = cg.repoRoot();
        String relPath = gid.replace('.', '/') + "/" + aid + "/" + version + "/" + aid + "-" + version + ".pom";
        File pomFile = new File(repoRoot, relPath);
        return !pomFile.exists();
    }
}
