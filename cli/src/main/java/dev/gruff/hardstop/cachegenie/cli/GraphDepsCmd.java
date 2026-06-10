package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.graph.GraphRepository;
import dev.gruff.hardstop.cachegenie.graph.MetaRepository;
import dev.gruff.hardstop.cachegenie.utils.Progress;
import dev.gruff.hardstop.resolver.Resolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Build the <em>direct</em>-dependency graph for a targeted/recent set of versions,
 * driven from the discovery catalogue ({@code meta_*} tables). Select by coordinate
 * ({@code --gav}), by recency ({@code --since}), or both. For each selected version
 * it reads the effective direct dependencies (one descriptor read, no transitive
 * collection) and persists the edges; transitive trees are then a recursive-CTE
 * query.
 *
 * <p>The worklist (not-missing, not-already-graphed, matching the filters) is built
 * by one SQL query. Descriptor reads (network) run on a worker pool with a per-thread
 * {@link Resolver} (Aether sessions aren't shareable); DB writes are funnelled
 * through one lock (DuckDB single-writer). A version whose descriptor can't be read
 * is marked {@code missing_pom} so re-runs skip it.
 */
@CommandLine.Command(name = "deps",
        description = "Build the direct-dependency graph for targeted/recent versions from the catalogue (transitive trees are then queryable via recursive SQL)")
public class GraphDepsCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(GraphDepsCmd.class);

    @CommandLine.ParentCommand
    GraphCmd parent;

    @CommandLine.Option(names = {"-gav", "--gav"}, arity = "0..*", paramLabel = "<selector>",
            description = "group, group:artifact, or group:artifact:version selectors (artifact/version may be '*' or omitted for all). Omit to select across the whole catalogue (use with --since).")
    List<String> gavs;

    @CommandLine.Option(names = {"--since"}, paramLabel = "<dur>",
            description = "Only versions published within this window, e.g. 30d, 24h, 12w. Requires index-sync to have populated publish dates.")
    String since;

    @CommandLine.Option(names = {"--threads"}, paramLabel = "<n>",
            description = "Concurrent descriptor-read workers (default ${DEFAULT-VALUE}).")
    int threads = 8;

    @CommandLine.Option(names = {"--list", "--dry-run"},
            description = "List the selected versions and count, then exit without building their graphs.")
    boolean list;

    @Override
    public void run() {
        boolean hasGav = gavs != null && !gavs.isEmpty();
        Instant cutoff = (since != null) ? Instant.now().minus(parseDuration(since)) : null;
        if (!hasGav && cutoff == null) {
            System.err.println("Specify --gav <selector>... and/or --since <duration>.");
            System.exit(2);
        }

        CacheGenie cg = parent.parent.genie();
        MetaRepository metaRepo = new MetaRepository(cg.cacheGenieRoot());
        GraphRepository gr = new GraphRepository(cg.cacheGenieRoot());
        Progress progress = Progress.start("Graph deps");

        // 1. Build the worklist via SQL (excludes missing-POM and already-graphed).
        List<String[]> work = new ArrayList<>();
        if (hasGav) {
            for (String sel : gavs) {
                String[] p = sel.trim().split(":");
                String gid = p[0];
                String aid = (p.length > 1 && !p[1].equals("*")) ? p[1] : null;
                String ver = (p.length > 2 && !p[2].equals("*")) ? p[2] : null;
                work.addAll(metaRepo.selectVersionsToGraph(gid, aid, ver, cutoff));
            }
        } else {
            work.addAll(metaRepo.selectVersionsToGraph(null, null, null, cutoff));
        }
        System.out.printf("Selected %d version(s) to graph%s.%n", work.size(),
                cutoff != null ? " (published since " + cutoff + ")" : "");

        // Preview only: list the worklist and stop (no resolving/graphing).
        if (list) {
            for (String[] w : work) {
                System.out.println(w[0] + ":" + w[1] + ":" + w[2]);
            }
            System.out.println("(" + work.size() + " versions; --list, nothing graphed)");
            System.exit(0);
        }

        // 2. Resolve in parallel; serialise all DB writes under one lock.
        ThreadLocal<Resolver> resolver = ThreadLocal.withInitial(() -> Resolver.Builder(cg).build());
        Object writeLock = new Object();
        AtomicLong graphed = new AtomicLong(), edges = new AtomicLong(), notFound = new AtomicLong(), transientErr = new AtomicLong();
        AtomicBoolean rateLimited = new AtomicBoolean(false);

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        List<Future<?>> futures = new ArrayList<>();
        try (GraphRepository.DirectWriter writer = gr.openDirectWriter()) {
            for (String[] w : work) {
                futures.add(pool.submit(() -> {
                    if (rateLimited.get()) return; // throttled — bail fast, don't keep hammering
                    String gav = w[0] + ":" + w[1] + ":" + w[2];
                    progress.tick(gav);
                    Resolver.DirectDepsResult r = resolver.get().directDependencies(gav);
                    switch (r.outcome()) {
                        case RATE_LIMITED -> {
                            if (rateLimited.compareAndSet(false, true)) {
                                log.error("Maven Central returned 429 (rate limited) on {} — stopping to avoid overloading it.", gav);
                            }
                        }
                        case OK -> {
                            synchronized (writeLock) { writer.persistDirect(w[0], w[1], w[2], r.deps()); }
                            graphed.incrementAndGet();
                            edges.addAndGet(r.deps().size());
                        }
                        case NOT_FOUND -> {
                            synchronized (writeLock) { writer.markMissing(w[0], w[1], w[2]); }
                            notFound.incrementAndGet();
                        }
                        case TRANSIENT -> transientErr.incrementAndGet(); // don't mark missing; retry next run
                    }
                }));
            }
            pool.shutdown();
            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception e) { log.warn("graph deps task failed: {}", e.getMessage()); }
            }
            try { pool.awaitTermination(1, TimeUnit.MINUTES); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        } catch (Exception e) {
            log.error("graph deps writer failed: {}", e.getMessage(), e);
        }

        progress.done();
        if (rateLimited.get()) {
            System.err.println("ABORTED: Maven Central rate-limited us (HTTP 429). Stopped to avoid overloading it — progress so far is saved; re-run later to continue.");
            System.out.printf(
                    "Before stopping: %d graphed (%d edges), %d not-found (marked missing), %d transient (will retry)%n",
                    graphed.get(), edges.get(), notFound.get(), transientErr.get());
            System.exit(1);
        }
        System.out.printf(
                "Graph deps complete: %d artifacts graphed (%d direct edges); %d not-found (marked missing), %d transient (skipped, will retry)%n",
                graphed.get(), edges.get(), notFound.get(), transientErr.get());
        System.exit(0);
    }

    /** Parse "30d"/"24h"/"12w"/"45m"/"90s" (bare number = days). Falls back to 30 days. */
    private static Duration parseDuration(String s) {
        if (s == null) return Duration.ofDays(30);
        s = s.trim().toLowerCase();
        if (s.isEmpty()) return Duration.ofDays(30);
        try {
            char unit = s.charAt(s.length() - 1);
            if (Character.isDigit(unit)) return Duration.ofDays(Long.parseLong(s));
            long n = Long.parseLong(s.substring(0, s.length() - 1).trim());
            return switch (unit) {
                case 'w' -> Duration.ofDays(n * 7);
                case 'd' -> Duration.ofDays(n);
                case 'h' -> Duration.ofHours(n);
                case 'm' -> Duration.ofMinutes(n);
                case 's' -> Duration.ofSeconds(n);
                default -> Duration.ofDays(30);
            };
        } catch (NumberFormatException e) {
            return Duration.ofDays(30);
        }
    }
}
