package dev.gruff.hardstop.cachegenie.actions;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.MavenMetaData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.stream.Collectors;

public class MigrateMetaAction {
    private static final Logger log = LoggerFactory.getLogger(MigrateMetaAction.class);
    private final CacheGenie cg;

    public MigrateMetaAction(CacheGenie cg) {
        this.cg = cg;
    }

    public void migrate() throws IOException {
        File cacheGenieRoot = cg.cacheGenieRoot();
        File[] files = cacheGenieRoot.listFiles((dir, name) -> name.endsWith(".properties"));

        if (files == null || files.length == 0) {
            log.warn("No meta properties files found in {}", cacheGenieRoot.getAbsolutePath());
            return;
        }

        File metaDir = new File(cacheGenieRoot, "meta");
        if (!metaDir.exists()) {
            metaDir.mkdirs();
        }

        log.info("Migrating {} files to JSON in {}", files.length, metaDir.getAbsolutePath());

        for (File f : files) {
            MavenMetaData meta = MavenMetaData.load(f);
            if (meta == null || meta.gid == null || meta.aid == null) continue;

            File targetDir = new File(metaDir, meta.gid.replace('.', '/') + "/" + meta.aid);
            targetDir.mkdirs();

            File targetFile = new File(targetDir, "metadata.json");
            log.info("Migrating {}:{} to {}", meta.gid, meta.aid, targetFile.getAbsolutePath());

            try (PrintWriter pw = new PrintWriter(new FileWriter(targetFile))) {
                pw.println("{");
                pw.printf("  \"groupId\": \"%s\",%n", meta.gid);
                pw.printf("  \"artifactId\": \"%s\",%n", meta.aid);
                pw.println("  \"versions\": [");

                String versionsJson = meta.versions.values().stream()
                        .map(v -> {
                            StringBuilder sb = new StringBuilder();
                            sb.append("    {\n");
                            sb.append("      \"version\": \"").append(v.value()).append("\",\n");
                            sb.append("      \"published\": ").append(v.date() != null ? "\"" + v.date().toString() + "\"" : "null").append(",\n");
                            sb.append("      \"missingPom\": ").append(meta.isMissingPom(v.value())).append("\n");
                            sb.append("    }");
                            return sb.toString();
                        })
                        .collect(Collectors.joining(",\n"));

                pw.println(versionsJson);
                pw.println("  ]");
                pw.println("}");
            }
        }
        log.info("Migration complete.");
    }
}
