package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import org.neo4j.driver.AuthToken;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Push CacheGenie's DuckDB graph delta into an <em>existing</em> Neo4j graph (e.g. a
 * loaded Goblin dump) over Bolt, augmenting it in place. DuckDB stays the system of
 * record; Neo4j is the canonical graph that this command tops up with whatever
 * CacheGenie has freshly mined/resolved.
 *
 * <p>Writes in Goblin's schema and uses {@code MERGE} throughout, so it is idempotent
 * (re-running never duplicates) and additive (it never touches or rebuilds the
 * existing graph). Since CacheGenie's DuckDB holds only the newly-resolved data (the
 * Goblin baseline lives in Neo4j, not DuckDB), pushing "everything" is the delta;
 * {@code --since} narrows it further by publish date.
 *
 * <p>Uses the Apache-2.0 Neo4j Bolt driver — no GPL entanglement (that applies to the
 * Neo4j <em>server</em>, which CacheGenie does not bundle).
 */
@CommandLine.Command(name = "push-neo4j",
        description = "MERGE CacheGenie's DuckDB graph delta into an existing Neo4j graph over Bolt (idempotent, additive). See HYBRID-NEO4J.md.")
public class GraphPushNeo4jCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(GraphPushNeo4jCmd.class);

    private static final String NODE_CYPHER =
            "UNWIND $rows AS row "
            + "MERGE (a:Artifact {id: row.ga}) "
            + "MERGE (r:Release {id: row.gav}) ON CREATE SET r.version = row.version, r.gid = row.gid, r.aid = row.aid "
            + "MERGE (a)-[:relationship_AR]->(r)";

    private static final String EDGE_CYPHER =
            "UNWIND $rows AS row "
            + "MERGE (r:Release {id: row.src}) "
            + "MERGE (a:Artifact {id: row.tgt}) "
            + "MERGE (r)-[:dependency {targetVersion: row.tv, scope: row.scope}]->(a)";

    @CommandLine.ParentCommand
    GraphCmd parent;

    @CommandLine.Option(names = {"--uri"}, paramLabel = "<bolt-uri>",
            description = "Neo4j Bolt URI (default ${DEFAULT-VALUE}).")
    String uri = "bolt://localhost:7687";

    @CommandLine.Option(names = {"--user"}, paramLabel = "<user>",
            description = "Neo4j user (default ${DEFAULT-VALUE}).")
    String user = "neo4j";

    @CommandLine.Option(names = {"--password"}, arity = "0..1", interactive = true, paramLabel = "<password>",
            description = "Neo4j password. Omit if auth is disabled. Pass --password with no value to be prompted "
                    + "(no echo); or set the NEO4J_PASSWORD env var to keep it out of shell history.")
    String password;

    @CommandLine.Option(names = {"--since"}, paramLabel = "<dur>",
            description = "Only push releases published within this window (e.g. 30d, 12w). Requires index-sync publish dates. Omit to push all of DuckDB's graph.")
    String since;

    @CommandLine.Option(names = {"--batch-size"}, paramLabel = "<n>",
            description = "Rows per write transaction (default ${DEFAULT-VALUE}).")
    int batchSize = 10000;

    @Override
    public void run() {
        CacheGenie cg = parent.parent.genie();
        File dbFile = new File(cg.cacheGenieRoot(), "graph.db");
        if (!dbFile.exists()) {
            System.out.println("Graph database not found at " + dbFile.getAbsolutePath());
            System.out.println("Populate DuckDB first (index-sync + graph mine + graph resolve).");
            System.exit(1);
        }
        Instant cutoff = (since != null) ? Instant.now().minus(parseDuration(since)) : null;

        String nodeSql;
        String edgeSql;
        if (cutoff != null) {
            nodeSql = "SELECT a.gid, a.aid, a.version FROM artifacts a "
                    + "JOIN meta_artifacts ma ON ma.gid = a.gid AND ma.aid = a.aid "
                    + "JOIN meta_versions mv ON mv.ga_id = ma.id AND mv.version = a.version "
                    + "WHERE a.classifier = '' AND mv.published IS NOT NULL AND mv.published >= ?";
            edgeSql = "SELECT p.gid, p.aid, p.version, c.gid, c.aid, c.version, COALESCE(d.scope, '') "
                    + "FROM dependencies d JOIN artifacts p ON p.id = d.parent_id JOIN artifacts c ON c.id = d.child_id "
                    + "JOIN meta_artifacts ma ON ma.gid = p.gid AND ma.aid = p.aid "
                    + "JOIN meta_versions mv ON mv.ga_id = ma.id AND mv.version = p.version "
                    + "WHERE mv.published IS NOT NULL AND mv.published >= ?";
        } else {
            nodeSql = "SELECT gid, aid, version FROM artifacts WHERE classifier = ''";
            edgeSql = "SELECT p.gid, p.aid, p.version, c.gid, c.aid, c.version, COALESCE(d.scope, '') "
                    + "FROM dependencies d JOIN artifacts p ON p.id = d.parent_id JOIN artifacts c ON c.id = d.child_id";
        }

        // Password precedence: --password (or interactive prompt) > NEO4J_PASSWORD env > none.
        // No password => connect with no auth (for Neo4j running with auth disabled).
        String pw = (password != null) ? password : System.getenv("NEO4J_PASSWORD");
        AuthToken auth = (pw == null || pw.isEmpty()) ? AuthTokens.none() : AuthTokens.basic(user, pw);

        Properties props = new Properties();
        props.setProperty("duckdb.read_only", "true");

        long nodes = 0, edges = 0;
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:" + dbFile.getAbsolutePath(), props);
             Driver driver = GraphDatabase.driver(uri, auth)) {
            driver.verifyConnectivity();

            // Goblin's uniqueness constraints (no-op if the loaded dump already has them).
            try (Session s = driver.session()) {
                s.run("CREATE CONSTRAINT artifactConstraint IF NOT EXISTS FOR (n:Artifact) REQUIRE n.id IS UNIQUE").consume();
                s.run("CREATE CONSTRAINT releaseConstraint IF NOT EXISTS FOR (n:Release) REQUIRE n.id IS UNIQUE").consume();
            }

            System.out.println("Pushing release nodes" + (cutoff != null ? " (published since " + cutoff + ")" : "") + " ...");
            try (Session s = driver.session();
                 PreparedStatement ps = duck.prepareStatement(nodeSql)) {
                if (cutoff != null) ps.setString(1, cutoff.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    List<Map<String, Object>> batch = new ArrayList<>();
                    while (rs.next()) {
                        String gid = rs.getString(1), aid = rs.getString(2), ver = rs.getString(3);
                        Map<String, Object> m = new HashMap<>();
                        m.put("ga", gid + ":" + aid);
                        m.put("gav", gid + ":" + aid + ":" + ver);
                        m.put("gid", gid);
                        m.put("aid", aid);
                        m.put("version", ver);
                        batch.add(m);
                        if (batch.size() >= batchSize) nodes += flush(s, NODE_CYPHER, batch);
                    }
                    if (!batch.isEmpty()) nodes += flush(s, NODE_CYPHER, batch);
                }
            }

            System.out.println("Pushing dependency edges ...");
            try (Session s = driver.session();
                 PreparedStatement ps = duck.prepareStatement(edgeSql)) {
                if (cutoff != null) ps.setString(1, cutoff.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    List<Map<String, Object>> batch = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> m = new HashMap<>();
                        m.put("src", rs.getString(1) + ":" + rs.getString(2) + ":" + rs.getString(3));
                        m.put("tgt", rs.getString(4) + ":" + rs.getString(5));
                        m.put("tv", rs.getString(6));
                        m.put("scope", rs.getString(7));
                        batch.add(m);
                        if (batch.size() >= batchSize) edges += flush(s, EDGE_CYPHER, batch);
                    }
                    if (!batch.isEmpty()) edges += flush(s, EDGE_CYPHER, batch);
                }
            }
        } catch (Exception e) {
            System.err.println("Push failed: " + e.getMessage());
            log.error("push-neo4j failed", e);
            System.exit(1);
        }

        System.out.printf("Pushed %d release node(s) and %d dependency edge(s) into %s (idempotent MERGE).%n",
                nodes, edges, uri);
        System.exit(0);
    }

    /** Run one batch in a write transaction, then clear it; returns the batch size. */
    private static long flush(Session session, String cypher, List<Map<String, Object>> batch) {
        int n = batch.size();
        // executeWrite is the 5.x driver API (writeTransaction is deprecated there).
        session.executeWrite(tx -> {
            tx.run(cypher, Values.parameters("rows", batch)).consume();
            return null;
        });
        batch.clear();
        return n;
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
