package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.viewer.DependencyViewerServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CountDownLatch;

/**
 * Launches the browser-based dependency viewer over the local DuckDB graph.
 *
 * <p>The heavy lifting (HTTP server, query layer, web assets) lives in the
 * {@code viewer} module; this command only resolves the {@code graph.db}
 * location from the global {@code --cache} option and starts the server on the
 * requested address/port.</p>
 */
@CommandLine.Command(name = "view", aliases = {"web", "ui"},
        description = "Launch a browser-based viewer of the dependency graph")
public class ViewCmd implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ViewCmd.class);

    @CommandLine.ParentCommand
    RootCmd parent;

    @CommandLine.Option(names = {"-a", "--address", "--host"},
            description = "Address to bind to (default: ${DEFAULT-VALUE}). Use 0.0.0.0 to expose on the network.")
    String host = "127.0.0.1";

    @CommandLine.Option(names = {"-p", "--port"},
            description = "Port to listen on (default: ${DEFAULT-VALUE}). Use 0 to pick a free port.")
    int port = 8080;

    @CommandLine.Option(names = {"--no-open"},
            description = "Do not attempt to open a browser automatically.")
    boolean noOpen;

    @Override
    public void run() {
        CacheGenie cg = parent.genie();
        File dbFile = new File(cg.cacheGenieRoot(), "graph.db");
        if (!dbFile.exists()) {
            System.out.println("Graph database not found at " + dbFile.getAbsolutePath());
            System.out.println("Run 'graph mine' + 'graph resolve' first to populate the database.");
            return;
        }

        DependencyViewerServer server = new DependencyViewerServer(dbFile.getAbsolutePath(), host, port);
        try {
            server.start();
        } catch (IOException e) {
            System.err.println("Failed to start viewer: " + e.getMessage());
            log.error("viewer startup failed", e);
            return;
        }

        String url = server.url();
        System.out.println("Dependency viewer running at " + url);
        System.out.println("Press Ctrl+C to stop.");

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        if (!noOpen) {
            openBrowser(url);
        }

        // Block until the JVM is interrupted / killed.
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.stop();
        }
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception e) {
            log.debug("Could not open browser automatically", e);
        }
    }
}
