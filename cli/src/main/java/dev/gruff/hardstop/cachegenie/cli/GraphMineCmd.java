package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.entities.MinedPom;
import dev.gruff.hardstop.cachegenie.graph.GraphRepository;
import dev.gruff.hardstop.cachegenie.graph.MetaRepository;
import dev.gruff.hardstop.cachegenie.parsers.RawPomParser;
import dev.gruff.hardstop.cachegenie.utils.Progress;
import dev.gruff.hardstop.resolver.PomFetcher;
import dev.gruff.hardstop.resolver.Resolver;
import dev.gruff.hardstop.treestreamer.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mine the <em>raw</em> POM for each catalogue version that hasn't been mined yet.
 * For each selected version it fetches <b>only the {@code .pom} file</b> via
 * {@link dev.gruff.hardstop.resolver.PomFetcher} (local {@code ~/.m2} first, else
 * one plain HTTP GET saved into {@code ~/.m2} — no Aether, no checksum, no
 * descriptor read, so no parent/BOM fan-out), parses it with {@link RawPomParser},
 * and stores the as-declared contents
 * (dependencies, dependencyManagement, parent ref, properties, scm, developers,
 * licenses, …) into the POM-mining tables via {@code GraphRepository.MiningWriter}.
 *
 * <p>This is the cheap, polite alternative to {@code graph deps}: it never resolves
 * inheritance or managed versions over the network. The effective view (and the
 * concrete {@code dependencies} edges) is produced afterwards by {@code graph
 * resolve}, which walks the mined parent/BOM nodes in SQL/Java.
 *
 * <p>Selection, pacing, threading and the 429-abort mirror {@code graph deps}.
 */
@CommandLine.Command(name = "mine",
        description = "Mine raw POMs (one GET each, no fan-out) for un-mined catalogue versions into the pom_* tables; resolve later with 'graph resolve'")
public class GraphMineCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(GraphMineCmd.class);

    @CommandLine.ParentCommand
    GraphCmd parent;

    @CommandLine.Option(names = {"-gav", "--gav"}, arity = "0..*", paramLabel = "<selector>",
            description = "group, group:artifact, or group:artifact:version selectors. Omit to mine across the whole catalogue (use with --since).")
    List<String> gavs;

    @CommandLine.Option(names = {"--since"}, paramLabel = "<dur>",
            description = "Only versions published within this window, e.g. 30d, 24h, 12w. Requires index-sync to have populated publish dates.")
    String since;

    @CommandLine.Option(names = {"--threads"}, paramLabel = "<n>",
            description = "Concurrent POM-fetch workers (default ${DEFAULT-VALUE}).")
    int threads = 8;

    /** Default total POM fetches per minute across all workers. */
    static final int DEFAULT_RATE_PER_MINUTE = 100;

    @CommandLine.Option(names = {"--rate"}, paramLabel = "<req/min>",
            description = "Total POM fetches per minute across all workers (default ${DEFAULT-VALUE}); 0 = unlimited. "
                    + "Unlike 'graph deps', this is ~1 real request per artifact (no parent/BOM fan-out), so the budget maps closely to actual load on the remote.")
    int rate = DEFAULT_RATE_PER_MINUTE;

    @CommandLine.Option(names = {"--all"},
            description = "Mine the ENTIRE un-mined catalogue (no --gav/--since filter). Required to run unfiltered — a guard against accidentally launching a full-Central crawl.")
    boolean all;

    @CommandLine.Option(names = {"--limit"}, paramLabel = "<n>",
            description = "Stop after selecting N versions (0 = no limit). For polite, resumable chunks — re-running picks up where you left off since mined versions are excluded.")
    int limit = 0;

    @CommandLine.Option(names = {"--list", "--dry-run"},
            description = "List the selected versions and count, then exit without mining.")
    boolean list;

    @Override
    public void run() {
        boolean hasGav = gavs != null && !gavs.isEmpty();
        Instant cutoff = (since != null) ? Instant.now().minus(parseDuration(since)) : null;
        if (!hasGav && cutoff == null && !all) {
            System.err.println("Specify --gav <selector>..., --since <duration>, or --all to mine the whole un-mined catalogue.");
            System.exit(2);
        }

        CacheGenie cg = parent.parent.genie();
        System.out.println("graph mine: opening the database and computing the worklist "
                + "(an anti-join over the catalogue — can take a while on a large DB, and emits nothing until it finishes)...");
        MetaRepository metaRepo = new MetaRepository(cg.cacheGenieRoot());
        GraphRepository gr = new GraphRepository(cg.cacheGenieRoot());
        Progress progress = Progress.start("Graph mine");
        String windowNote = cutoff != null ? " (published since " + cutoff + ")" : "";

        // Preview: count cheaply (no 6M-row materialisation / no flooding the terminal).
        if (list) {
            long count = 0;
            List<String[]> sample = new ArrayList<>();
            if (hasGav) {
                for (String sel : gavs) {
                    String[] p = sel.trim().split(":");
                    String gid = p[0];
                    String aid = (p.length > 1 && !p[1].equals("*")) ? p[1] : null;
                    String ver = (p.length > 2 && !p[2].equals("*")) ? p[2] : null;
                    count += metaRepo.countVersionsToMine(gid, aid, ver, cutoff);
                    if (sample.size() < 20) sample.addAll(metaRepo.selectVersionsToMine(gid, aid, ver, cutoff, 20 - sample.size()));
                }
            } else {
                count = metaRepo.countVersionsToMine(null, null, null, cutoff);
                sample = metaRepo.selectVersionsToMine(null, null, null, cutoff, 20);
            }
            System.out.printf("Selected %d version(s) to mine%s.%n", count, windowNote);
            if (!sample.isEmpty()) {
                System.out.println("Sample:");
                for (String[] w : sample) System.out.println("  " + w[0] + ":" + w[1] + ":" + w[2]);
            }
            System.out.println("(--list / --dry-run; nothing mined" + (limit > 0 ? "; a real run is capped at --limit " + limit : "") + ")");
            System.exit(0);
        }

        // Real run: stream the worklist in bounded keyset PAGES so a multi-million-row backlog
        // runs in CONSTANT memory regardless of catalogue size. (The old path materialised the
        // entire worklist into one List AND pre-submitted every task to an unbounded executor
        // queue while retaining every Future — that OOM'd on a full-Central run.) Per page we
        // read it (closing the read connection), then mine it through the worker pool with all
        // DB writes funnelled to one drainer thread. The reader and writer are never open at the
        // same time — the invariant graph mine/deps have always relied on.
        final int PAGE_SIZE = 50_000;

        // Selector filters: each is {gid, aid, version} (aid/version null = wildcard).
        List<String[]> selectors = new ArrayList<>();
        if (hasGav) {
            for (String sel : gavs) {
                String[] p = sel.trim().split(":");
                String gid = p[0];
                String aid = (p.length > 1 && !p[1].equals("*")) ? p[1] : null;
                String ver = (p.length > 2 && !p[2].equals("*")) ? p[2] : null;
                selectors.add(new String[]{gid, aid, ver});
            }
        } else {
            selectors.add(new String[]{null, null, null});
        }

        // Total for n/total + ETA — a cheap COUNT(*), capped by --limit when set.
        long total = 0;
        for (String[] s : selectors) {
            long c = metaRepo.countVersionsToMine(s[0], s[1], s[2], cutoff);
            if (c > 0) total += c;
        }
        if (limit > 0) total = Math.min(total, limit);
        System.out.printf("Selected %d version(s) to mine%s.%n", total, windowNote);
        progress.total(total);

        RateLimiter limiter = (rate > 0) ? new RateLimiter(rate, Duration.ofMinutes(1)) : null;
        if (limiter != null) {
            System.out.printf("Throttling POM fetches to ~%d/min across %d worker(s).%n", rate, Math.max(1, threads));
        }
        // Plain HTTP + local-cache fetcher (no Aether, no checksum request); stateless
        // and thread-safe, so one shared instance serves all workers.
        PomFetcher fetcher = new PomFetcher(cg.repoRoot(), cg.base());
        AtomicLong mined = new AtomicLong(), notFound = new AtomicLong(),
                transientErr = new AtomicLong(), parseFailed = new AtomicLong();
        AtomicBoolean rateLimited = new AtomicBoolean(false);

        // One worker pool, reused across all pages (it touches no DB — only fetch+parse).
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));

        long budget = (limit > 0) ? limit : Long.MAX_VALUE;
        long processed = 0;
        pages:
        for (String[] sel : selectors) {
            String[] cursor = null; // keyset over (gid, aid, version)
            while (!rateLimited.get() && processed < budget) {
                int pageLimit = (int) Math.min(PAGE_SIZE, budget - processed);
                List<String[]> page =
                        metaRepo.selectVersionsToMineAfter(sel[0], sel[1], sel[2], cutoff, cursor, pageLimit);
                if (page.isEmpty()) break; // selector exhausted
                cursor = page.get(page.size() - 1);
                processed += page.size();

                // All DB writes for THIS page funnel to ONE drainer thread (DuckDB single-writer);
                // fetch workers only fetch+parse and hand results to the bounded queue, which
                // back-pressures them if the writer falls behind. The writer is opened here (after
                // the page read closed its connection) and flushed/closed when the page completes —
                // so a reader and a writer are never open against graph.db at the same time.
                BlockingQueue<WriteItem> writeQ = new ArrayBlockingQueue<>(10_000);
                final WriteItem poison = new WriteItem(null, null);
                AtomicBoolean writerDead = new AtomicBoolean(false);
                Thread drainer = new Thread(() -> {
                    try (GraphRepository.MiningWriter writer = gr.openMiningWriter()) {
                        for (;;) {
                            WriteItem it = writeQ.take();
                            if (it == poison) break;
                            if (it.pom() != null) { writer.appendMined(it.pom()); mined.incrementAndGet(); }
                            else { writer.appendMissing(it.missing()[0], it.missing()[1], it.missing()[2]); notFound.incrementAndGet(); }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        log.error("graph mine writer failed: {}", e.getMessage(), e);
                    } finally {
                        writerDead.set(true);
                    }
                }, "mine-writer");
                drainer.start();

                List<Future<?>> futures = new ArrayList<>(page.size());
                for (String[] w : page) {
                    futures.add(pool.submit(() -> {
                        if (rateLimited.get() || writerDead.get()) return;
                        if (limiter != null) {
                            try { limiter.waitForPermission(); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                        }
                        if (rateLimited.get() || writerDead.get()) return;
                        String gav = w[0] + ":" + w[1] + ":" + w[2];
                        progress.tick(gav);
                        Resolver.PomFetch fetch = fetcher.fetch(gav);
                        switch (fetch.outcome()) {
                            case RATE_LIMITED -> {
                                if (rateLimited.compareAndSet(false, true)) {
                                    log.error("Maven Central returned 429 (rate limited) on {} — stopping to avoid overloading it.", gav);
                                }
                            }
                            case OK -> {
                                MinedPom mp = RawPomParser.parse(fetch.file());
                                if (mp.ok()) {
                                    // Use the catalogue coordinates as the node identity (the POM may
                                    // inherit g/v from its parent; the worklist coordinates are exact).
                                    enqueue(writeQ, new WriteItem(withCoordinates(mp, w[0], w[1], w[2]), null), writerDead);
                                } else {
                                    parseFailed.incrementAndGet(); // fetched but malformed — not 'missing'
                                }
                            }
                            case NOT_FOUND -> enqueue(writeQ, new WriteItem(null, new String[]{w[0], w[1], w[2]}), writerDead);
                            case TRANSIENT -> transientErr.incrementAndGet();
                        }
                    }));
                }
                // Wait for this page's fetches, then signal the drainer and wait for its final
                // flush/close before the next page opens a read connection.
                for (Future<?> f : futures) {
                    try { f.get(); } catch (Exception e) { log.warn("graph mine task failed: {}", e.getMessage()); }
                }
                try {
                    while (drainer.isAlive() && !writeQ.offer(poison, 1, TimeUnit.SECONDS)) { /* writer busy; retry */ }
                    drainer.join();
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

                if (rateLimited.get()) break pages;
            }
        }
        pool.shutdown();
        try { pool.awaitTermination(1, TimeUnit.MINUTES); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        progress.done();
        if (rateLimited.get()) {
            System.err.println("ABORTED: Maven Central rate-limited us (HTTP 429). Stopped to avoid overloading it — progress so far is saved; re-run later to continue.");
            System.out.printf("Before stopping: %d mined, %d not-found (marked missing), %d transient (will retry), %d malformed%n",
                    mined.get(), notFound.get(), transientErr.get(), parseFailed.get());
            System.exit(1);
        }
        System.out.printf("Graph mine complete: %d POMs mined; %d not-found (marked missing), %d transient (skipped, will retry), %d malformed%n",
                mined.get(), notFound.get(), transientErr.get(), parseFailed.get());
        System.out.println("Mined into " + new File(cg.cacheGenieRoot(), "graph.db").getAbsolutePath() + " — run 'graph resolve' to build the effective graph.");
        System.exit(0);
    }

    /** One unit of work for the writer drainer: either a parsed POM to persist, or a missing GAV to mark. */
    private record WriteItem(MinedPom pom, String[] missing) {}

    /**
     * Hand an item to the writer drainer, blocking (back-pressure) until there's room.
     * Bails if the writer thread has died so a fetch worker never blocks forever; a
     * dropped item just leaves that version un-mined to be picked up on the next run.
     */
    private static void enqueue(BlockingQueue<WriteItem> q, WriteItem item, AtomicBoolean writerDead) {
        try {
            while (!writerDead.get()) {
                if (q.offer(item, 1, TimeUnit.SECONDS)) return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Return a copy of {@code mp} with the node identity set to the catalogue coordinates. */
    private static MinedPom withCoordinates(MinedPom mp, String gid, String aid, String version) {
        if (gid.equals(mp.gid()) && aid.equals(mp.aid()) && version.equals(mp.version())) return mp;
        return new MinedPom(gid, aid, version, mp.status(), mp.packaging(),
                mp.parentGid(), mp.parentAid(), mp.parentVersion(), mp.parentRelPath(),
                mp.name(), mp.description(), mp.url(), mp.inceptionYear(),
                mp.organizationName(), mp.organizationUrl(),
                mp.scmUrl(), mp.scmConnection(), mp.scmDevConnection(), mp.scmTag(),
                mp.issueSystem(), mp.issueUrl(), mp.ciSystem(), mp.ciUrl(),
                mp.dependencies(), mp.dependencyManagement(), mp.properties(), mp.developers(), mp.licenses());
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
