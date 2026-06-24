package dev.gruff.hardstop.resolver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Fetches a single {@code .pom} for the raw-mining pipeline <em>without</em> Aether:
 * if the file is already in the local Maven repository it is used as-is (no
 * network); otherwise exactly one HTTP GET pulls it from the remote and it is
 * written into the local repo in standard Maven layout. No checksum request, no
 * descriptor read, no parent/BOM fan-out — one request per artifact on a cold cache.
 *
 * <p>Stateless and thread-safe: a single instance can be shared across mining
 * workers (each artifact maps to a distinct path, so there is no write contention).
 * The instance holds <b>one shared {@link HttpClient}</b> which pools and reuses
 * keep-alive connections (HTTP/2 where the remote supports it). At scale this is
 * the difference between a fresh TCP+TLS handshake per artifact and amortising one
 * connection across thousands of fetches, so steady-state throughput is bounded by
 * the worker count rather than by per-request handshake latency.
 */
public final class PomFetcher {

    private static final Logger log = LoggerFactory.getLogger(PomFetcher.class);

    /** Max redirect hops to follow before giving up (guards against loops). */
    private static final int MAX_REDIRECTS = 5;

    private final File localRoot;
    private final String base; // remote repo base, always ends with '/'
    /** Shared, connection-pooling client. Thread-safe; one per fetcher serves all workers. */
    private final HttpClient http;

    /**
     * @param localRoot  local Maven repository root (e.g. {@code ~/.m2/repository}).
     * @param remoteBase remote repository base URI (e.g. {@code https://repo1.maven.org/maven2}).
     */
    public PomFetcher(File localRoot, URI remoteBase) {
        this.localRoot = localRoot;
        String b = remoteBase.toASCIIString();
        this.base = b.endsWith("/") ? b : b + "/";
        this.http = HttpClient.newBuilder()
                // Force HTTP/1.1, NOT HTTP/2. The JDK HttpClient multiplexes all HTTP/2
                // requests onto a SINGLE connection per origin and will not open a second
                // one; once in-flight requests exceed the server's advertised
                // SETTINGS_MAX_CONCURRENT_STREAMS it throws "too many concurrent streams"
                // rather than queueing. With a large --threads pool against one origin
                // (Maven Central / a mirror) that fails the vast majority of fetches —
                // which we then mis-count as TRANSIENT. HTTP/1.1 instead pools multiple
                // keep-alive connections (one request each), so throughput scales with
                // the worker count and there is no per-connection stream cap.
                .version(HttpClient.Version.HTTP_1_1)
                // Follow redirects manually below: the JDK client won't downgrade
                // https->http, and we want to honour cross-scheme mirror redirects.
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /** Relative Maven path of a GAV's POM: {@code g/i/d/aid/ver/aid-ver.pom}. */
    private static String pomPath(String gid, String aid, String ver) {
        return gid.replace('.', '/') + "/" + aid + "/" + ver + "/" + aid + "-" + ver + ".pom";
    }

    /**
     * Resolve the {@code .pom} for {@code gav} ({@code group:artifact:version}),
     * serving from the local repo when present and otherwise downloading it once.
     *
     * @return outcome + the local file (non-null only when {@code OK}).
     */
    public Resolver.PomFetch fetch(String gav) {
        String[] p = gav.split(":");
        if (p.length < 3) return new Resolver.PomFetch(Resolver.ResolveOutcome.NOT_FOUND, null);
        // Unresolved Maven property placeholders (e.g. ${revision}, ${VERSION}, ${project.version})
        // leak into the catalogue as literal coordinate text. They are not real artifacts, and the
        // characters $ { } are illegal in a URL path — URI.create() would throw an unchecked
        // IllegalArgumentException that escapes download()'s IOException-only catch and kills the
        // worker task uncounted (visited ticks up, no outcome recorded). Treat them as permanently
        // NOT_FOUND so they're marked missing_pom and skipped on future runs instead of retried forever.
        if (hasUnresolvedPlaceholder(p[0]) || hasUnresolvedPlaceholder(p[1]) || hasUnresolvedPlaceholder(p[2])) {
            log.debug("POM fetch {} skipped — unresolved property placeholder in coordinates", gav);
            return new Resolver.PomFetch(Resolver.ResolveOutcome.NOT_FOUND, null);
        }
        String rel = pomPath(p[0], p[1], p[2]);

        File local = new File(localRoot, rel);
        if (local.isFile() && local.length() > 0) {
            return new Resolver.PomFetch(Resolver.ResolveOutcome.OK, local);
        }
        return download(base + rel, local, gav);
    }

    private Resolver.PomFetch download(String startUrl, File dest, String gav) {
        String url = startUrl;
        try {
            // Follow redirects manually: the JDK HttpClient (like HttpURLConnection)
            // won't cross schemes (https<->http), which is exactly the mirror case we
            // want to handle. We resolve Location ourselves (relative or absolute,
            // any scheme). The shared client keeps connections pooled across hops and
            // across artifacts, so we don't pay a handshake per request.
            for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .GET()
                        .timeout(Duration.ofSeconds(120))
                        .header("User-Agent", "CacheGenie-miner")
                        .build();
                HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                int code = resp.statusCode();
                try (InputStream body = resp.body()) {
                    if (code == 200) {
                        save(body, dest);
                        return new Resolver.PomFetch(Resolver.ResolveOutcome.OK, dest);
                    }
                    if (isRedirect(code)) {
                        String loc = resp.headers().firstValue("Location").orElse(null);
                        if (loc == null || loc.isBlank()) {
                            log.debug("POM fetch {} -> HTTP {} with no Location", gav, code);
                            return new Resolver.PomFetch(Resolver.ResolveOutcome.TRANSIENT, null);
                        }
                        // resolve() handles relative redirects and absolute cross-scheme ones.
                        url = URI.create(url).resolve(loc.trim()).toString();
                        continue;
                    }
                    if (code == 404 || code == 410) {
                        return new Resolver.PomFetch(Resolver.ResolveOutcome.NOT_FOUND, null);
                    }
                    if (code == 429) {
                        return new Resolver.PomFetch(Resolver.ResolveOutcome.RATE_LIMITED, null);
                    }
                    log.debug("POM fetch {} -> HTTP {}", gav, code);
                    return new Resolver.PomFetch(Resolver.ResolveOutcome.TRANSIENT, null); // 5xx and other transient
                }
            }
            log.debug("POM fetch {} exceeded {} redirects", gav, MAX_REDIRECTS);
            return new Resolver.PomFetch(Resolver.ResolveOutcome.TRANSIENT, null);
        } catch (IOException e) {
            String m = e.getMessage();
            if (m != null && (m.contains("429") || m.toLowerCase().contains("too many requests"))) {
                return new Resolver.PomFetch(Resolver.ResolveOutcome.RATE_LIMITED, null);
            }
            log.debug("POM fetch {} failed: {}", gav, m);
            return new Resolver.PomFetch(Resolver.ResolveOutcome.TRANSIENT, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("POM fetch {} interrupted", gav);
            return new Resolver.PomFetch(Resolver.ResolveOutcome.TRANSIENT, null);
        } catch (IllegalArgumentException e) {
            // Malformed URL/coordinate (e.g. illegal path characters) — not a real, fetchable
            // artifact and never will be. Classify NOT_FOUND so it's marked missing and not
            // retried, and so an unchecked exception can never kill a worker task uncounted.
            log.debug("POM fetch {} skipped — malformed URL: {}", gav, e.getMessage());
            return new Resolver.PomFetch(Resolver.ResolveOutcome.NOT_FOUND, null);
        }
    }

    /** True if a coordinate still contains an unresolved Maven property placeholder ({@code ${...}}). */
    private static boolean hasUnresolvedPlaceholder(String s) {
        return s != null && (s.indexOf('$') >= 0 || s.indexOf('{') >= 0 || s.indexOf('}') >= 0);
    }

    /** 301/302/303/307/308 — all the redirect statuses we follow (any → GET is fine for a POM). */
    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    /** Stream the 200 response body to {@code dest} via a {@code .part} temp file then move. */
    private static void save(InputStream in, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(dest.getAbsolutePath() + ".part");
        Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
