package dev.gruff.hardstop.cachegenie.actions;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.graph.MetaRepository;
import dev.gruff.hardstop.cachegenie.utils.Progress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Synthesises {@code maven-metadata.xml} files into the local Maven repository
 * ({@code ~/.m2/repository}) straight from the discovery catalogue
 * ({@code meta_artifacts}/{@code meta_versions}) — no network. CacheGenie already
 * knows every version of every artifact from {@code index-sync}, so it can rebuild
 * the version metadata that {@code graph mine} never fetched (mine pulls only the
 * {@code .pom}). With these files present, an <b>offline</b> Aether resolve can
 * handle version ranges and {@code LATEST}/{@code RELEASE} that otherwise fail.
 *
 * <p>The {@code <versions>} list is written in publish-date order (best-effort
 * deployment order); this is cosmetic for resolution since Aether re-sorts with its
 * own comparator. {@code <latest>}/{@code <release>} come from the catalogue
 * (newest by publish date), matching Central's "last deployed" semantics. The file
 * is therefore functionally equivalent to Central's for resolution, but not a
 * byte-for-byte copy ({@code <lastUpdated>} is stamped now; exact ordering may
 * differ).
 *
 * <p>The file is named {@code maven-metadata-<repoId>.xml} to match how Aether's
 * {@code SimpleLocalRepositoryManager} (the manager the {@code Resolver} installs)
 * looks up cached remote metadata; {@code repoId} defaults to the remote's id
 * ({@code central}). {@code alsoPlain} additionally writes plain
 * {@code maven-metadata.xml} for tools that expect that name.
 */
public class MetadataAction {
    private static final Logger log = LoggerFactory.getLogger(MetadataAction.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final CacheGenie cg;

    public MetadataAction(CacheGenie cg) {
        this.cg = cg;
    }

    /**
     * @param gidFilter group id to scope to, or null for the whole catalogue.
     * @param aidFilter artifact id to scope to (requires gidFilter), or null.
     * @param repoId    repository id used in the filename ({@code maven-metadata-<repoId>.xml}).
     * @param alsoPlain also write plain {@code maven-metadata.xml}.
     */
    public void generate(String gidFilter, String aidFilter, String repoId, boolean alsoPlain) {
        MetaRepository repo = new MetaRepository(cg.cacheGenieRoot());
        File repoRoot = cg.repoRoot();
        String lastUpdated = ZonedDateTime.now(ZoneOffset.UTC).format(STAMP);

        Progress progress = Progress.start("Metadata");
        AtomicLong written = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        progress.stats(() -> String.format("[written %d, failed %d]", written.get(), failed.get()));

        repo.streamArtifactMetadata(gidFilter, aidFilter, am -> {
            try {
                String xml = buildXml(am, lastUpdated);
                File dir = new File(repoRoot, am.gid().replace('.', '/') + "/" + am.aid());
                writeAtomic(new File(dir, "maven-metadata-" + repoId + ".xml"), xml);
                if (alsoPlain) writeAtomic(new File(dir, "maven-metadata.xml"), xml);
                written.incrementAndGet();
                progress.tick(am.gid() + ":" + am.aid());
            } catch (IOException e) {
                failed.incrementAndGet();
                log.warn("metadata write failed for {}:{}: {}", am.gid(), am.aid(), e.getMessage());
            }
        });

        progress.done();
        System.out.printf("Metadata generation complete: %d artifact metadata file(s) written, %d failed.%n",
                written.get(), failed.get());
        System.out.println("Wrote maven-metadata-" + repoId + ".xml under " + repoRoot.getAbsolutePath()
                + " — offline resolution can now use version ranges / LATEST / RELEASE.");
    }

    /** Build a maven-metadata.xml document for one artifact. */
    private static String buildXml(MetaRepository.ArtifactMetadata am, String lastUpdated) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<metadata>\n");
        sb.append("  <groupId>").append(esc(am.gid())).append("</groupId>\n");
        sb.append("  <artifactId>").append(esc(am.aid())).append("</artifactId>\n");
        sb.append("  <versioning>\n");
        if (am.latest() != null)  sb.append("    <latest>").append(esc(am.latest())).append("</latest>\n");
        if (am.release() != null) sb.append("    <release>").append(esc(am.release())).append("</release>\n");
        sb.append("    <versions>\n");
        for (String v : am.versions()) {
            sb.append("      <version>").append(esc(v)).append("</version>\n");
        }
        sb.append("    </versions>\n");
        sb.append("    <lastUpdated>").append(lastUpdated).append("</lastUpdated>\n");
        sb.append("  </versioning>\n");
        sb.append("</metadata>\n");
        return sb.toString();
    }

    /** Write via a temp file + atomic move so a reader never sees a half-written file. */
    private static void writeAtomic(File dest, String content) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null) Files.createDirectories(parent.toPath());
        File tmp = new File(dest.getAbsolutePath() + ".part");
        Files.write(tmp.toPath(), content.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Minimal XML text escaping (versions/coords can in rare cases contain &, <, >). */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
