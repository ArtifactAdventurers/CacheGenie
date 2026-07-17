package dev.gruff.hardstop.cachegenie.actions.index;

import org.apache.maven.index.reader.ResourceHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Read-only {@link ResourceHandler} that fetches index files over HTTP from a base
 * URI (e.g. {@code https://repo1.maven.org/maven2/.index/}). Used as the "remote"
 * side of {@link org.apache.maven.index.reader.IndexReader}. Single-threaded use.
 *
 * <p><b>Mid-stream resume.</b> The full index is a multi-GB gzip streamed over one
 * HTTPS socket held open for the better part of an hour; CDNs and middleboxes reset
 * such connections routinely, and without resume a full bootstrap restarts from
 * byte&nbsp;0 every time (and on a flaky link may never finish). So the returned
 * stream is self-healing: on a mid-stream {@link IOException} it reconnects with
 * {@code Range: bytes=<offset>-} and continues where it left off, transparently to
 * the {@code ChunkReader} above it. {@code If-Range} pins the first response's
 * {@code ETag} so a resource republished mid-download can never be silently mixed
 * with the old bytes — that case fails hard with a "re-run" message instead.
 * Retries are bounded per <i>consecutive</i> failure streak (the budget resets
 * whenever bytes flow again), with exponential backoff.
 */
public final class HttpResourceHandler implements ResourceHandler {

    private static final Logger log = LoggerFactory.getLogger(HttpResourceHandler.class);

    /** Consecutive failed resume attempts tolerated before giving up (resets on progress). */
    private static final int MAX_CONSECUTIVE_RESUMES = 5;
    private static final long INITIAL_BACKOFF_MS = 1_000;
    private static final long MAX_BACKOFF_MS = 30_000;

    private final URI base;

    /** @param base index base URI; MUST end with '/' so names resolve under it. */
    public HttpResourceHandler(URI base) {
        this.base = base.toString().endsWith("/") ? base : URI.create(base + "/");
    }

    @Override
    public Resource locate(String name) {
        URI uri = base.resolve(name);
        return () -> open(uri);
    }

    private static InputStream open(URI uri) throws IOException {
        HttpURLConnection conn = connect(uri, 0, null);
        try {
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null; // resource absent
            if (code / 100 != 2) throw new IOException("GET " + uri + " failed: HTTP " + code);
            String etag = conn.getHeaderField("ETag");
            boolean resumable = "bytes".equalsIgnoreCase(conn.getHeaderField("Accept-Ranges"));
            return new ResumingInputStream(uri, conn.getInputStream(), etag, resumable);
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    /**
     * Build a connection; when {@code offset > 0} it is a resume request
     * ({@code Range} + {@code If-Range} on the original ETag, if the server sent one).
     */
    private static HttpURLConnection connect(URI uri, long offset, String etag) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(120_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "CacheGenie-indexer");
        // Byte offsets must refer to the raw stored bytes, never a transfer encoding.
        conn.setRequestProperty("Accept-Encoding", "identity");
        if (offset > 0) {
            conn.setRequestProperty("Range", "bytes=" + offset + "-");
            if (etag != null) conn.setRequestProperty("If-Range", etag);
        }
        return conn;
    }

    /**
     * Delegating stream that, on a mid-stream read failure, reopens the connection
     * with a byte-range at the exact number of bytes already handed to the caller.
     */
    private static final class ResumingInputStream extends InputStream {
        private final URI uri;
        private final String etag;
        private final boolean resumable;
        private InputStream in;
        /** Bytes successfully delivered to the caller == resume offset. */
        private long position;
        /** Consecutive failed resume attempts since bytes last flowed. */
        private int failures;
        private boolean eof;

        ResumingInputStream(URI uri, InputStream in, String etag, boolean resumable) {
            this.uri = uri;
            this.in = in;
            this.etag = etag;
            this.resumable = resumable;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (eof) return -1;
            while (true) {
                try {
                    int n = in.read(b, off, len);
                    if (n > 0) {
                        position += n;
                        failures = 0;
                    } else if (n < 0) {
                        eof = true;
                    }
                    return n;
                } catch (IOException e) {
                    resume(e); // returns with a fresh stream (or at EOF), or throws
                    if (eof) return -1; // connection died exactly at end-of-file
                }
            }
        }

        /**
         * Reconnect at {@link #position}. Loops with backoff over transient connect
         * failures; throws on a non-resumable server, an exhausted retry budget, or
         * a resource that changed since the download started.
         */
        private void resume(IOException cause) throws IOException {
            if (!resumable) {
                throw new IOException("read failed " + position + " bytes into " + uri
                        + " and the server does not accept byte ranges; cannot resume", cause);
            }
            closeQuietly();
            while (true) {
                if (++failures > MAX_CONSECUTIVE_RESUMES) {
                    throw new IOException("giving up on " + uri + " after " + MAX_CONSECUTIVE_RESUMES
                            + " consecutive failed resume attempts at offset " + position, cause);
                }
                backoff();
                try {
                    HttpURLConnection conn = connect(uri, position, etag);
                    int code = conn.getResponseCode();
                    if (code == HttpURLConnection.HTTP_PARTIAL
                            || (position == 0 && code == HttpURLConnection.HTTP_OK)) {
                        in = conn.getInputStream();
                        log.warn("resumed {} at offset {} after read failure ({})",
                                uri, position, cause.getMessage());
                        return;
                    }
                    if (code == HttpURLConnection.HTTP_OK) {
                        // If-Range mismatch (resource republished mid-download) or the
                        // server ignored Range. Continuing would splice two different
                        // files together; restarting from 0 would silently mix decoder
                        // state. Fail hard — a re-run streams the new file cleanly.
                        conn.getInputStream().close();
                        throw new FatalResumeException("remote resource " + uri
                                + " changed while it was being downloaded (or the server ignored"
                                + " Range); re-run the sync to pull the new file");
                    }
                    if (code == 416) {
                        // Requested range not satisfiable. If the complete length equals
                        // what we already delivered, the connection died exactly at EOF:
                        // nothing is missing, report end-of-stream to the caller.
                        long complete = completeLength(conn.getHeaderField("Content-Range"));
                        if (complete == position) {
                            eof = true;
                            return;
                        }
                        throw new FatalResumeException("resume of " + uri + " at offset " + position
                                + " not satisfiable (complete length " + complete + ")");
                    }
                    throw new IOException("resume GET " + uri + " at offset " + position
                            + " failed: HTTP " + code);
                } catch (FatalResumeException e) {
                    throw new IOException(e.getMessage(), cause);
                } catch (IOException e) {
                    log.warn("resume attempt {}/{} for {} at offset {} failed: {}",
                            failures, MAX_CONSECUTIVE_RESUMES, uri, position, e.getMessage());
                    // loop and retry
                }
            }
        }

        /** Complete length from a 416 {@code Content-Range} ("bytes &#42;&#47;123" → 123); -1 when absent/unparseable. */
        private static long completeLength(String contentRange) {
            if (contentRange == null) return -1;
            int slash = contentRange.lastIndexOf('/');
            if (slash < 0) return -1;
            try {
                return Long.parseLong(contentRange.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        private void backoff() throws InterruptedIOException {
            long ms = Math.min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS << (failures - 1));
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted while waiting to resume " + uri);
            }
        }

        private void closeQuietly() {
            try {
                if (in != null) in.close();
            } catch (IOException ignored) {
                // the connection is already broken
            }
            in = null;
        }

        @Override
        public int available() {
            // 0 is always a safe "no estimate" answer; never let a broken socket
            // throw from here and bypass the resume path (only read() resumes).
            try {
                return (in == null || eof) ? 0 : in.available();
            } catch (IOException e) {
                return 0;
            }
        }

        @Override
        public void close() throws IOException {
            eof = true;
            if (in != null) {
                InputStream s = in;
                in = null;
                s.close();
            }
        }

        /** Marker for resume failures that must not be retried. */
        private static final class FatalResumeException extends IOException {
            FatalResumeException(String message) {
                super(message);
            }
        }
    }
}
