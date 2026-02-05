package dev.gruff.hardstop.cachegenie.actions;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.MavenMetaData;
import dev.gruff.hardstop.resolver.Resolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MetaAction {
    private static final Logger log = LoggerFactory.getLogger(MetaAction.class);
    private final CacheGenie cg;
    private final Resolver resolver;

    public MetaAction(CacheGenie cg) {
        this.cg = cg;
        this.resolver = Resolver.Builder(cg).build();
    }

    public void downloadMissingPoms(List<String> gavs, boolean retryMissing) {

        File cacheGenieRoot = cg.cacheGenieRoot();
        File[] files;

        Set<File> gaFiles=new HashSet<>();
        if (gavs != null && !gavs.isEmpty()) {
            for (String gav : gavs) {
                String[] parts = gav.split(":");
                if (parts.length >= 2) {
                    ///home/spoole/.m2/cachegenie/com.ibm.cusp:cusp.properties
                    gaFiles.add(new File(cacheGenieRoot, parts[0]+":"+parts[1]+".properties"));
                }
            }
            files=gaFiles.toArray(new File[0]);
        } else {
            files = cacheGenieRoot.listFiles((dir, name) -> name.endsWith(".properties"));

        }


        if (files == null || files.length == 0) return;
        
        Arrays.stream(files).parallel().forEach(f -> {
            MavenMetaData meta = MavenMetaData.load(f);
            if (meta == null || meta.gid == null || meta.aid == null) return;

            log.info("Checking poms for {}:{}", meta.gid, meta.aid);
            final boolean[] changed = {false};

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
                        synchronized (meta) {
                            meta.markPomAsMissing(version);
                            changed[0] = true;
                        }
                    } else if (meta.isMissingPom(version)) {
                        log.info("  Found previously missing pom for {}:{}:{}", meta.gid, meta.aid, version);
                        synchronized (meta) {
                            meta.markPomAsFound(version);
                            changed[0] = true;
                        }
                    }
                }
            });

            if (changed[0]) {
                try {
                    synchronized (meta) {
                        meta.save(f);
                    }
                } catch (IOException e) {
                    log.error("Failed to save updated meta properties for {}:{}: {}", meta.gid, meta.aid, e.getMessage());
                }
            }
        });
    }

    private boolean isPomMissing(String gid, String aid, String version) {
        File repoRoot = cg.repoRoot();
        String relPath = gid.replace('.', '/') + "/" + aid + "/" + version + "/" + aid + "-" + version + ".pom";
        File pomFile = new File(repoRoot, relPath);
        return !pomFile.exists();
    }
}
