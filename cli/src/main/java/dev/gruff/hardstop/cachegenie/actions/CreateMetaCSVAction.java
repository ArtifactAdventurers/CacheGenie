package dev.gruff.hardstop.cachegenie.actions;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.MavenMetaData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class CreateMetaCSVAction {
    private static final Logger log = LoggerFactory.getLogger(CreateMetaCSVAction.class);
    private final CacheGenie cg;

    public CreateMetaCSVAction(CacheGenie cg) {
        this.cg = cg;
    }

    public void create(File outputFile) throws IOException {
        File cacheGenieRoot = cg.cacheGenieRoot();
        File[] files = cacheGenieRoot.listFiles((dir, name) -> name.endsWith(".properties"));

        if (files == null || files.length == 0) {
            log.warn("No meta properties files found in {}", cacheGenieRoot.getAbsolutePath());
            return;
        }

        log.info("Creating CSV file: {}", outputFile.getAbsolutePath());
        try (PrintWriter pw = new PrintWriter(new FileWriter(outputFile))) {
            pw.println("gav,groupId,artifactId,version,published");

            for (File f : files) {
                MavenMetaData meta = MavenMetaData.load(f);
                if (meta == null || meta.gid == null || meta.aid == null) continue;

                for (MavenMetaData.Version v : meta.versions.values()) {
                    String gav = meta.gid + ":" + meta.aid + ":" + v.value();
                    pw.printf("%s,%s,%s,%s,%s%n",
                            gav,
                            meta.gid,
                            meta.aid,
                            v.value(),
                            v.date() != null ? v.date().toString() : "");
                }
            }
        }
        log.info("CSV creation complete.");
    }
}
