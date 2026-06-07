package dev.gruff.hardstop.cachegenie.actions;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.MavenMetaData;
import dev.gruff.hardstop.cachegenie.graph.MetaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

public class CreateMetaCSVAction {
    private static final Logger log = LoggerFactory.getLogger(CreateMetaCSVAction.class);
    private final CacheGenie cg;

    public CreateMetaCSVAction(CacheGenie cg) {
        this.cg = cg;
    }

    public void create(File outputFile) throws IOException {
        MetaRepository metaRepo = new MetaRepository(cg.cacheGenieRoot());
        List<MavenMetaData> metas = metaRepo.loadAll();

        if (metas.isEmpty()) {
            log.warn("No meta records found in the DuckDB meta tables");
            return;
        }

        log.info("Creating CSV file: {}", outputFile.getAbsolutePath());
        try (PrintWriter pw = new PrintWriter(new FileWriter(outputFile))) {
            pw.println("groupId,artifactId,version,published");

            for (MavenMetaData meta : metas) {
                if (meta == null || meta.gid == null || meta.aid == null) continue;
                for (MavenMetaData.Version v : meta.versions.values()) {
                    pw.printf("%s,%s,%s,%s%n",
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
