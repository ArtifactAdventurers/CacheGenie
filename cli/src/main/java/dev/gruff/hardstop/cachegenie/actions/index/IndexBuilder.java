package dev.gruff.hardstop.cachegenie.actions.index;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.MavenMetaData;
import dev.gruff.hardstop.cachegenie.MavenMetaDataFactory;
import dev.gruff.hardstop.cachegenie.graph.MetaRepository;
import dev.gruff.hardstop.cachegenie.utils.Progress;
import dev.gruff.hardstop.treestreamer.ContentType;
import dev.gruff.hardstop.treestreamer.RateLimiter;
import dev.gruff.hardstop.treestreamer.URIHelper;
import dev.gruff.hardstop.treestreamer.navigators.*;
import dev.gruff.hardstop.treestreamer.streamers.URITreeSteamVisitorBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static dev.gruff.hardstop.treestreamer.navigators.NavigatorPolicyBuilder.builder;

public class IndexBuilder {
    private static final Logger log = LoggerFactory.getLogger(IndexBuilder.class);

    CacheGenie genie;
    final MavenMetaDataFactory mb;
    final MetaRepository metaRepo;
    private final Progress progress = Progress.start("Index", 50, 2000L);

    /** Default crawl rate (remote requests per minute) when none is specified. */
    public static final int DEFAULT_RATE_PER_MINUTE = 100;
    /** Default number of concurrent crawl workers. */
    public static final int DEFAULT_THREADS = 8;
    /** Default staleness window: skip re-checking an artifact seen within this long. */
    public static final Duration DEFAULT_MAX_AGE = Duration.ofDays(7);

    private final int threads;

    /**
     * Freshness window for undirected scans. If an artifact's metadata was last
     * written within this window, the crawl skips re-fetching its
     * {@code maven-metadata.xml}. {@link Duration#ZERO} (or negative) = always
     * check. Only applied during the random-walk (undirected) crawl, never for an
     * explicit {@code --gav} request.
     */
    private final Duration maxAge;
    private volatile boolean applyFreshnessGate = false;
    private volatile Map<String, Instant> lastChecked = Collections.emptyMap();
    private final AtomicLong skippedFresh = new AtomicLong();
    /**
     * One shared limiter so {@code ratePerMinute} is the TOTAL crawl budget no
     * matter how many worker threads run. {@link RateLimiter#waitForPermission()}
     * is synchronized, so sharing it is safe.
     */
    private final RateLimiter sharedLimiter;

    /**
     * Each worker builds its OWN crawl policy: {@link MavenMetaDataFactory} wraps a
     * {@code DocumentBuilder}, which is NOT thread-safe, so parsers must not be
     * shared. Only {@link #sharedLimiter} is shared (for the aggregate budget).
     */
    private final ThreadLocal<NavigatorPolicy> crawlPolicy =
            ThreadLocal.withInitial(this::newCrawlPolicy);

    // Scan stats (thread-safe — handleMeta runs on worker threads).
    private final AtomicLong metaFound = new AtomicLong();
    private final AtomicLong newArtifacts = new AtomicLong();
    private final AtomicLong newVersions = new AtomicLong();

    public IndexBuilder(CacheGenie cg) {
        this(cg, DEFAULT_RATE_PER_MINUTE, DEFAULT_THREADS, DEFAULT_MAX_AGE);
    }

    public IndexBuilder(CacheGenie cg, int ratePerMinute) {
        this(cg, ratePerMinute, DEFAULT_THREADS, DEFAULT_MAX_AGE);
    }

    public IndexBuilder(CacheGenie cg, int ratePerMinute, int threads) {
        this(cg, ratePerMinute, threads, DEFAULT_MAX_AGE);
    }

    /**
     * @param ratePerMinute total remote requests per minute across the whole crawl
     *                      (clamped to at least 1). Lower it to be gentler on the
     *                      remote repository.
     * @param threads       number of concurrent crawl workers (clamped to at least 1).
     * @param maxAge        freshness window for the undirected random-walk; ZERO = always check.
     */
    public IndexBuilder(CacheGenie cg, int ratePerMinute, int threads, Duration maxAge) {
        genie = cg;
        mb = MavenMetaDataFactory.newInstance();
        metaRepo = new MetaRepository(cg.cacheGenieRoot());
        this.threads = Math.max(1, threads);
        this.sharedLimiter = new RateLimiter(Math.max(1, ratePerMinute), Duration.ofMinutes(1));
        this.maxAge = (maxAge == null) ? Duration.ZERO : maxAge;
    }

    /** Build a fresh crawl policy with its own parser, sharing the aggregate limiter. */
    private NavigatorPolicy newCrawlPolicy() {
        return builder()
                .rateLimit(sharedLimiter)                                   // shared aggregate budget
                .defaultReader(new HTMLRefNavigator())                      // html docs -> linksets
                .onMatch(ContentType.HTML)
                .and(IndexBuilder::checkPath)
                .useReader(new MavenStyleHTMLRefNavigator(MavenMetaDataFactory.newInstance(), this::shouldFetchMeta)) // per-thread parser + freshness gate
                .build();
    }

    /**
     * Freshness gate consulted (per maven-metadata.xml URI) before fetching it.
     * Skips the fetch when the artifact was checked within {@link #maxAge}. Only
     * active during the undirected random-walk; directed/explicit scans always
     * fetch. Records skips for the summary. Thread-safe (volatile + atomic).
     */
    private boolean shouldFetchMeta(URI metadataUri) {
        if (!applyFreshnessGate || maxAge.isZero() || maxAge.isNegative()) return true;
        String coord = coordFromMetaUri(metadataUri);
        if (coord == null) return true;
        Instant checked = lastChecked.get(coord);
        if (checked != null && Duration.between(checked, Instant.now()).compareTo(maxAge) < 0) {
            skippedFresh.incrementAndGet();
            return false;
        }
        return true;
    }

    /** Derive "gid:aid" from a .../maven2/&lt;group&gt;/&lt;aid&gt;/maven-metadata.xml URI. */
    private String coordFromMetaUri(URI metadataUri) {
        String rel = URIHelper.relative(genie.base(), metadataUri); // group/path/aid/maven-metadata.xml
        if (rel == null) return null;
        String suffix = "maven-metadata.xml";
        if (rel.endsWith(suffix)) rel = rel.substring(0, rel.length() - suffix.length());
        while (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
        int slash = rel.lastIndexOf('/');
        if (slash < 1) return null;
        String aid = rel.substring(slash + 1);
        String gid = rel.substring(0, slash).replace('/', '.');
        return gid + ":" + aid;
    }

    private static boolean checkPath(Link l) {

        return l.path().toASCIIString().endsWith("/");// and it looks like a directory
    }


    private boolean noSuffix(Link u) {
        return !u.path().getPath().contains(".");
    }

    /**
     * List entries of full or partial GAVs
     *
     * so   XXX   XXX:XXX  XXX:XXX:XXX
     *
     * @param args
     */
    public void index(List<String> args) {
        // traverse website

        if (args.isEmpty()) {
            args.add("");
        }

        if (args.get(0).equalsIgnoreCase("?")) {

            try {
                randomWalk();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            for (String arg : args) {
                arg = arg.trim();
                arg=arg.replace(":","/");
                arg=arg.replace(".","/");
                index(arg);
            }
        }

        progress.done();

        long found = metaFound.get();
        long created = newArtifacts.get();
        System.out.printf(
                "Scan complete: %d metadata files read (%d new artifacts, %d already known), %d new versions added; %d skipped as fresh (within %s)%n",
                found, created, found - created, newVersions.get(), skippedFresh.get(), maxAge);
    }

    private void randomWalk() throws IOException {

        // Undirected scan: apply the freshness gate so we skip re-fetching
        // metadata for artifacts checked within maxAge. Load last-checked times
        // once (read-only during the crawl).
        if (!maxAge.isZero() && !maxAge.isNegative()) {
            lastChecked = metaRepo.loadLastChecked();
            applyFreshnessGate = true;
            log.info("Freshness gate on: skipping artifacts checked within {} ({} known)", maxAge, lastChecked.size());
        }

        // randomise...
        File list = new File(genie.work(), "index_build");
        List<String> lines;
        if (list.exists()) {
            lines = Files.readAllLines(list.toPath());
        } else {
            lines = new LinkedList<>();
        }
        Set<String> toDo = new HashSet<>();
        toDo.addAll(lines);

        // todo shows what's leftto do.
        if (toDo.isEmpty()) {


            NavigatorPolicy shortPolicy = builder()
                    .rateLimit(100, Duration.ofMinutes(1))  // play nice
                    .defaultReader(new HTMLRefNavigator())
                    .maxDepth(2)
                    .build();

            URITreeSteamVisitorBuilder.newInstance(genie.base())
                    .policy(shortPolicy)
                    .select()
                    .on(Link.class)
                    .consume(l -> {
                        String f = URIHelper.relative(genie.base(), l.path());
                        String[] bits = f.split("/");
                        if (bits.length > 1) {
                            toDo.add(f);
                            System.out.println("group " + f);
                        }
                    })
                    .visit();

        }

        System.out.println("=== IB TODO " + toDo.size());

        // Each top-level group is an independent subtree, so crawl them on a
        // worker pool. The shared RateLimiter keeps the TOTAL request rate within
        // --rate regardless of thread count; per-thread parsers keep it safe.
        // 'toDo' (the resume set) is mutated under its own lock as each completes.
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (String key : new ArrayList<>(toDo)) {
            futures.add(pool.submit(() -> {
                System.out.println("=== IB ==== > " + key);
                try {
                    index(key);
                } catch (RuntimeException e) {
                    log.warn("indexing {} failed: {}", key, e.getMessage());
                }
                synchronized (toDo) {
                    toDo.remove(key);
                    writeToDo(list, toDo);
                }
            }));
        }
        pool.shutdown();
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                log.warn("crawl task failed: {}", e.getMessage());
            }
        }
        try {
            pool.awaitTermination(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

    }

    private void writeToDo(File list, Set<String> toDo) {

        try (FileWriter fw = new FileWriter(list)) {
            PrintWriter pw = new PrintWriter(fw);
            for (String s : toDo) {
                pw.println(s);
                pw.flush();
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }

    }

    private void index(String arg) {

        log.info("indexing {}", arg);
        if (arg.startsWith("/")) arg = arg.substring(1);
        String url = "repo1.maven.org/maven2/" + arg + "/";
        url = url.replace("//", "/");
        URI root = URI.create("https://" + url);
        log.debug("root {}", root);

        URITreeSteamVisitorBuilder.newInstance(root)
                .policy(crawlPolicy.get())
                .select()
                .on(MavenMetaData.class)
                .consume(m -> handleMeta(m))
                .on(LinkSetImpl.class)
                .consume(s -> {
                    // if (log.isTraceEnabled()) s.stream().forEach(k -> log.trace("ls: {}", k.path()));
                })
                .on(Link.class)
                .consume(l -> log.debug("file link: {}", l.path().toASCIIString()))
                .otherwise()
                .consume(o -> {
                    // log.trace("other: {}", o);
                })
                .visit();

    }

    private void handleMeta(MavenMetaData m) {

        if (m.uri == null) {
            log.warn("meta has no uri");
            return;
        }
        progress.tick(m.gid + ":" + m.aid);
        log.debug("meta link: {}", m.uri);
        // Merge: add any versions missing since last time without clobbering
        // existing rows (e.g. fetch's missing-POM flags) or deleting versions.
        MetaRepository.MergeStats s = metaRepo.mergeDiscovered(m);
        metaFound.incrementAndGet();
        if (s.newArtifact()) newArtifacts.incrementAndGet();
        newVersions.addAndGet(s.newVersions());
    }

    public MavenMetaData meta(String gid, String aid) {

        if (metaRepo.exists(gid, aid)) {
            MavenMetaData cached = metaRepo.load(gid, aid);
            if (cached != null) return cached;
        }

        URI file = URIHelper.subDirURI(genie.base(), gid.replace(".", "/") + "/" + aid);

        NavigatorPolicy shortPolicy = builder()
                .rateLimit(100, Duration.ofMinutes(1))  // play nice
                .defaultReader(new MavenStyleHTMLRefNavigator(mb))
                .maxDepth(1)
                .build();

        final List<MavenMetaData> metas = new LinkedList<>();
        URITreeSteamVisitorBuilder.newInstance(file)
                .policy(shortPolicy)
                .select()
                .on(MavenMetaData.class)
                .consume(m -> {
                    log.debug("read {}", m);
                    handleMeta(m); // save it
                    metas.add(m);
                })
                .otherwise()
                  .consume(o -> {
                      // ignore other things like links found during meta discovery
                  })
                .visit();

        if (metas.isEmpty()) return null;
        return metas.get(0);
    }
}

