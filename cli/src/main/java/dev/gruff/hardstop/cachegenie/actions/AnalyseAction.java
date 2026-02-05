package dev.gruff.hardstop.cachegenie.actions;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.entities.ArtifactRef;
import dev.gruff.hardstop.cachegenie.entities.POM;
import dev.gruff.hardstop.cachegenie.entities.POMStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public class AnalyseAction {
    private static final Logger log = LoggerFactory.getLogger(AnalyseAction.class);
    private final CacheGenie cg;

    public AnalyseAction(CacheGenie cg) {
        this.cg = cg;
    }

    public void analysePom() {
        File repoRoot = cg.repoRoot();
        log.info("Analysing POM files in {}", repoRoot.getAbsolutePath());
        if (!repoRoot.exists() || !repoRoot.isDirectory()) {
            log.warn("Maven repository root not found at {}", repoRoot.getAbsolutePath());
            return;
        }

        PomStats stats = new PomStats();
        walkPom(repoRoot, stats);

        System.out.println("\n--- Maven Repository POM Analysis ---");
        System.out.println("Location: " + repoRoot.getAbsolutePath());
        System.out.println("Total POM files found: " + stats.totalFiles);
        System.out.println("Successfully parsed: " + stats.parsedFiles);
        System.out.println("Malformed/Failed: " + stats.failedFiles);

        if (stats.failedFiles > 0) {
            System.out.println("\nFailure Reasons:");
            stats.failureReasons.forEach((k, v) -> System.out.println("  " + k + ": " + v));
        }

        System.out.println("\nArtifact Statistics:");
        System.out.println("Unique Artifacts (G:A): " + stats.uniqueArtifacts.size());
        
        System.out.println("\nTop 10 Most Common Dependencies:");
        stats.dependencyCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .forEach(e -> System.out.println("  " + e.getKey() + ": " + e.getValue()));

        System.out.println("\nPackaging Distribution:");
        stats.packagingDistribution.forEach((k, v) -> System.out.println("  " + k + ": " + v));

        System.out.println("\nJava Version Requirements (Source/Target):");
        System.out.println("  Source versions: " + stats.javaSourceVersions);
        System.out.println("  Target versions: " + stats.javaTargetVersions);
        System.out.println("  Release versions: " + stats.javaReleaseVersions);
        System.out.println("  POMs using 'release': " + stats.pomsUsingRelease);

        System.out.println("\nArtifacts with Most Versions:");
        stats.uniqueArtifacts.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().size(), e1.getValue().size()))
                .limit(5)
                .forEach(e -> System.out.println("  " + e.getKey() + ": " + e.getValue().size() ));
    }

    private void walkPom(File dir, PomStats stats) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    walkPom(f, stats);
                } else if (f.getName().endsWith(".pom")) {
                    stats.totalFiles++;
                    try {
                        POM pom = POM.create(f);
                        if (pom.status() == POMStatus.OK) {
                            stats.parsedFiles++;
                            ArtifactRef ref = pom.artifact();
                            String ga = ref.groupID().value() + ":" + ref.artifactID().value();
                            stats.uniqueArtifacts.computeIfAbsent(ga, k -> new TreeSet<>()).add(ref.version().value());

                            String pkg = pom.packaging();
                            stats.packagingDistribution.merge(pkg, 1L, Long::sum);

                            if (pom.javaSource() != null) stats.javaSourceVersions.add(pom.javaSource());
                            if (pom.javaTarget() != null) stats.javaTargetVersions.add(pom.javaTarget());
                            if (pom.javaRelease() != null) {
                                stats.javaReleaseVersions.add(pom.javaRelease());
                                stats.pomsUsingRelease++;
                            }

                            for (ArtifactRef dep : pom.dependencies()) {
                                String depGA = dep.groupID().value() + ":" + dep.artifactID().value();
                                stats.dependencyCounts.merge(depGA, 1L, Long::sum);
                            }
                        } else {
                            stats.failedFiles++;
                            stats.failureReasons.merge(pom.status().name(), 1L, Long::sum);
                        }
                    } catch (Exception e) {
                        stats.failedFiles++;
                        stats.failureReasons.merge("EXCEPTION: " + e.getMessage(), 1L, Long::sum);
                    }
                }
            }
        }
    }

    private static class PomStats {
        long totalFiles = 0;
        long parsedFiles = 0;
        long failedFiles = 0;
        Map<String, Long> failureReasons = new HashMap<>();
        Map<String, Set<String>> uniqueArtifacts = new HashMap<>();
        Map<String, Long> dependencyCounts = new HashMap<>();
        Map<String, Long> packagingDistribution = new HashMap<>();
        Set<String> javaSourceVersions = new TreeSet<>();
        Set<String> javaTargetVersions = new TreeSet<>();
        Set<String> javaReleaseVersions = new TreeSet<>();
        long pomsUsingRelease = 0;
    }

    public void analyseMeta() {
        File cacheGenieRoot = cg.cacheGenieRoot();
        log.info("Analysing meta properties files in {}", cacheGenieRoot.getAbsolutePath());
        if (!cacheGenieRoot.exists() || !cacheGenieRoot.isDirectory()) {
            log.warn("CacheGenie root not found at {}", cacheGenieRoot.getAbsolutePath());
            return;
        }

        long count = countFiles(cacheGenieRoot, ".properties");
        log.info("Found {} meta properties files.", count);
        System.out.println("Total meta properties files: " + count);
    }

    public void countPomsOnly() {
        File repoRoot = cg.repoRoot();
        log.info("Counting POM files in {}", repoRoot.getAbsolutePath());
        if (!repoRoot.exists() || !repoRoot.isDirectory()) {
            log.warn("Maven repository root not found at {}", repoRoot.getAbsolutePath());
            return;
        }
        long start=System.currentTimeMillis();
        long count = countFiles(repoRoot, ".pom");
        long end=System.currentTimeMillis();
        long duration=end-start;
        long mins=duration/1000/60;
        System.out.println("Total POM files: " + count+" in "+mins+" minutes");
    }

    private long countFiles(File dir, String extension) {
        AtomicLong count = new AtomicLong(0);
        try {
            Files.walkFileTree(dir.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().endsWith(extension)) {
                        count.incrementAndGet();
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
            return count.get();
        } catch (IOException e) {
            log.error("Failed to walk directory {}", dir.getAbsolutePath(), e);
            return 0;
        }
    }

    private void walk(File dir, String extension, AtomicLong count) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    walk(f, extension, count);
                } else if (f.getName().endsWith(extension)) {
                    count.incrementAndGet();
                }
            }
        }
    }
}
