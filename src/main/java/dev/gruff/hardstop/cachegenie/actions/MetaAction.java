package dev.gruff.hardstop.cachegenie.actions;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.MavenMetaData;
import dev.gruff.hardstop.resolver.Resolver;

import java.io.File;

public class MetaAction {
    private final CacheGenie cg;
    private final Resolver resolver;

    public MetaAction(CacheGenie cg) {
        this.cg = cg;
        this.resolver = Resolver.Builder(cg).build();
    }

    public void downloadMissingPoms() {

        File cacheGenieRoot = cg.cacheGenieRoot();
        File[] files = cacheGenieRoot.listFiles((dir, name) -> name.endsWith(".properties"));
        if (files == null) return;

        for (File f : files) {
            MavenMetaData meta = MavenMetaData.load(f);
            if (meta == null || meta.gid == null || meta.aid == null) continue;

            System.out.println("Checking poms for " + meta.gid + ":" + meta.aid);
            for (String version : meta.versions.keySet()) {
                if (isPomMissing(meta.gid, meta.aid, version)) {
                    System.out.println("  Downloading missing pom for " + meta.gid + ":" + meta.aid + ":" + version);
                    resolver.resolvePOM(meta.gid + ":" + meta.aid + ":" + version);
                }
            }
        }
    }

    private boolean isPomMissing(String gid, String aid, String version) {
        File repoRoot = cg.repoRoot();
        String relPath = gid.replace('.', '/') + "/" + aid + "/" + version + "/" + aid + "-" + version + ".pom";
        File pomFile = new File(repoRoot, relPath);
        return !pomFile.exists();
    }
}
