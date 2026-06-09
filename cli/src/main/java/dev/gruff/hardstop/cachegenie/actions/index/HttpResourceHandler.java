package dev.gruff.hardstop.cachegenie.actions.index;

import org.apache.maven.index.reader.ResourceHandler;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Read-only {@link ResourceHandler} that fetches index files over HTTP from a base
 * URI (e.g. {@code https://repo1.maven.org/maven2/.index/}). Used as the "remote"
 * side of {@link org.apache.maven.index.reader.IndexReader}. Single-threaded use.
 */
public final class HttpResourceHandler implements ResourceHandler {

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
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(120_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "CacheGenie-indexer");
        try {
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null; // resource absent
            return conn.getInputStream();
        } catch (FileNotFoundException e) {
            return null;
        }
    }
}
