package dev.gruff.hardstop.cachegenie.viewer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Browser-based dependency viewer, served entirely by the JDK's built-in HTTP
 * server ({@link com.sun.net.httpserver}, module {@code jdk.httpserver}).
 *
 * <p>Static assets (the single-page UI) are streamed from the classpath under
 * {@code /webui}, so they ship inside the shaded jar. A small set of read-only
 * {@code /api} endpoints returns dependency data as JSON; all the dependency
 * logic lives in {@link GraphQueryService}.</p>
 */
public class DependencyViewerServer {

    private static final Logger log = LoggerFactory.getLogger(DependencyViewerServer.class);
    private static final String UI_ROOT = "/webui";

    private final String host;
    private final int port;
    private final GraphQueryService service;

    private HttpServer server;
    private ExecutorService executor;

    public DependencyViewerServer(String dbPath, String host, int port) {
        this.host = host;
        this.port = port;
        this.service = new GraphQueryService(dbPath);
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);

        server.createContext("/api/search", wrap(this::handleSearch));
        server.createContext("/api/artifact", wrap(this::handleArtifact));
        server.createContext("/api/transitive", wrap(this::handleTransitive));
        server.createContext("/api/graph", wrap(this::handleGraph));
        server.createContext("/api/stats", wrap(this::handleStats));
        server.createContext("/", wrap(this::handleStatic));

        server.start();
        log.info("Dependency viewer listening on http://{}:{}", host, boundPort());
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public int boundPort() {
        return server == null ? port : server.getAddress().getPort();
    }

    public String url() {
        return "http://" + host + ":" + boundPort() + "/";
    }

    // ----------------------------------------------------------- API handlers

    private void handleSearch(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        int limit = parseInt(q.get("limit"), 50);
        sendJson(ex, 200, Json.write(service.search(q.getOrDefault("q", ""), limit)));
    }

    private void handleArtifact(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        Integer id = parseId(q.get("id"));
        if (id == null) {
            sendError(ex, 400, "missing or invalid 'id'");
            return;
        }
        Map<String, Object> artifact = service.artifact(id);
        if (artifact == null) {
            sendError(ex, 404, "artifact not found");
            return;
        }
        String scope = scope(q.get("scope"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("artifact", artifact);
        out.put("dependencies", service.forwardDependencies(id, scope));
        out.put("dependents", service.reverseDependencies(id, scope));
        sendJson(ex, 200, Json.write(out));
    }

    private void handleTransitive(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        Integer id = parseId(q.get("id"));
        if (id == null) {
            sendError(ex, 400, "missing or invalid 'id'");
            return;
        }
        boolean forward = !"reverse".equalsIgnoreCase(q.getOrDefault("dir", "forward"));
        int depth = clamp(parseInt(q.get("depth"), 6), 1, 25);
        sendJson(ex, 200, Json.write(service.transitive(id, forward, depth, scope(q.get("scope")))));
    }

    private void handleGraph(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        Integer id = parseId(q.get("id"));
        if (id == null) {
            sendError(ex, 400, "missing or invalid 'id'");
            return;
        }
        int up = clamp(parseInt(q.get("up"), 1), 0, 6);
        int down = clamp(parseInt(q.get("down"), 2), 0, 6);
        Map<String, Object> graph = service.neighbourhood(id, up, down, scope(q.get("scope")));
        if (graph == null) {
            sendError(ex, 404, "artifact not found");
            return;
        }
        sendJson(ex, 200, Json.write(graph));
    }

    private void handleStats(HttpExchange ex) throws IOException {
        sendJson(ex, 200, Json.write(service.stats()));
    }

    // --------------------------------------------------------- static assets

    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path == null || path.equals("/")) {
            path = "/index.html";
        }
        if (path.contains("..")) {
            sendError(ex, 400, "bad path");
            return;
        }
        String resource = UI_ROOT + path;
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                sendError(ex, 404, "not found");
                return;
            }
            byte[] body = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", contentType(path));
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    // ----------------------------------------------------------------- plumbing

    private HttpHandler wrap(HttpHandler delegate) {
        return ex -> {
            try {
                delegate.handle(ex);
            } catch (RuntimeException e) {
                log.error("handler error for {}", ex.getRequestURI(), e);
                sendError(ex, 500, "internal error");
            } finally {
                ex.close();
            }
        };
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.put(decode(pair), "");
            } else {
                out.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return out;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String scope(String s) {
        if (s == null || s.isBlank() || s.equalsIgnoreCase("all")) {
            return null;
        }
        return s;
    }

    private static Integer parseId(String s) {
        try {
            return s == null ? null : Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseInt(String s, int dflt) {
        try {
            return s == null ? dflt : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        return "application/octet-stream";
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static void sendError(HttpExchange ex, int status, String message) throws IOException {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", message);
        sendJson(ex, status, Json.write(err));
    }
}
