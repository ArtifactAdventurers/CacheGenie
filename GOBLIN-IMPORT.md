# Seeding the graph from the Goblin dataset

The fastest, most Maven-Central-friendly way to populate CacheGenie's dependency
graph is **not** to crawl it — it's to import the [Goblin Maven Central dependency
graph](https://zenodo.org/records/15481588), a pre-computed snapshot of the whole
ecosystem, then keep it current with incremental `graph mine`.

## Why this works

Goblin builds its graph the same way CacheGenie's `graph deps` does — Aether's
`readArtifactDescriptor` per release — so its edges are **effective/resolved direct
dependencies with scope**, equivalent to a full `graph deps` run, already done for
all of Central up to the dataset's snapshot date (2025-04-20 for the current
release). Importing it costs minutes and zero requests to Maven Central.

What Goblin does **not** carry: raw `<dependencyManagement>`, properties,
optional/type/classifier, and POM metadata (scm/developers/licenses). For those, and
for anything published after the snapshot, use `graph mine` + `graph resolve`.

One wrinkle: a Goblin edge's `targetVersion` is occasionally a version *range* rather
than a concrete version (Aether's descriptor read doesn't resolve ranges). Those edges
point at a non-existent concrete release and are **skipped by default** on import.

## Step 1 — Get the dump

From <https://zenodo.org/records/15481588> download `goblin_maven_20_04_2025.dump`
(6.2 GB; the plain graph — you don't need the `with_metrics` variant for edges).

## Step 2 — Load it into Neo4j 4.x

> **Version warning.** The dump is a **Neo4j 4.x** dump. It will **not** load into a
> Neo4j 5.x or 2025/2026 (CalVer, e.g. `2026.05.0`) server — Neo4j's supported path is
> 4.4 → 5.26 LTS → 2025/2026, so you can't hop a 4.x dump straight to a modern server.
> Check with `neo4j --version`.
>
> If your real server is 5.x/CalVer, **don't migrate the dump into it.** Use a
> throwaway Neo4j **4.4** (Docker is easiest: `neo4j:4.4`) purely to load the dump and
> export the edges to CSV (Step 3). CSV is version-neutral, so you then load that CSV
> into your real server with `neo4j-admin database import full` (fresh) or `LOAD CSV`
> + `MERGE` (into an existing graph) — see `HYBRID-NEO4J.md`. The transient 4.4 is just
> a converter; your canonical graph lives on your real server.

On a Neo4j **4.x** instance (native: run as the `neo4j` user, server stopped):

```bash
sudo systemctl stop neo4j                       # native; or 'neo4j stop'
sudo -u neo4j neo4j-admin load \
  --from=goblin_maven_20_04_2025.dump --database=neo4j --force
sudo systemctl start neo4j
```

Or transiently in Docker (the converter approach above):

```bash
docker run --rm -v ~/neo4j-data:/data -v "$PWD":/dumps neo4j:4.4 \
  neo4j-admin load --from=/dumps/goblin_maven_20_04_2025.dump --database=neo4j --force
```

## Step 3 — Export the dependency edges to CSV

CacheGenie wants one row per edge: `source,targetArtifact,targetVersion,scope`.
In Goblin's model that's `(Release)-[:dependency]->(Artifact)` with the version on
the relationship. Easiest is APOC (enable `apoc.export.file.enabled=true`):

```cypher
CALL apoc.export.csv.query(
  "MATCH (r:Release)-[d:dependency]->(a:Artifact)
   RETURN r.id AS source, a.id AS targetArtifact, d.targetVersion AS targetVersion, d.scope AS scope",
  "goblin-edges.csv",
  {}
);
```

The file lands in Neo4j's `import/` directory. (136M rows → a multi-GB CSV; make sure
there's disk.)

No APOC? Use `cypher-shell` piped to a file (slower, but no plugin):

```bash
cypher-shell -u neo4j -p <pw> --format plain \
  "MATCH (r:Release)-[d:dependency]->(a:Artifact) \
   RETURN r.id AS source, a.id AS targetArtifact, d.targetVersion AS targetVersion, d.scope AS scope" \
  > goblin-edges.csv
```

Either way the header row must be exactly `source,targetArtifact,targetVersion,scope`.

## Step 4 — Import into CacheGenie

```bash
java -jar target/cachegenie.jar graph import-goblin --edges /path/to/goblin-edges.csv
```

This bulk-loads (set-based in DuckDB): it inserts every distinct release as an
`artifacts` node and every concrete edge into `dependencies` (`INSERT OR IGNORE`, so
it's idempotent and can layer onto an existing graph). Add `--include-ranges` to also
load range/property-version edges (their child node won't be a real release).

Then index and verify:

```bash
java -jar target/cachegenie.jar db optimize
java -jar target/cachegenie.jar graph stats
```

## Step 5 — Keep it current

Goblin is a snapshot. Cover everything published after it with incremental mining:

```bash
java -jar target/cachegenie.jar index-sync                       # refresh the catalogue
java -jar target/cachegenie.jar graph mine   --since <snapshot>  # e.g. 14m for ~14 months since 2025-04-20
java -jar target/cachegenie.jar graph resolve                    # resolve the freshly mined POMs
```

Use `mine`/`resolve` (not `deps`) for the delta — they're the polite, low-traffic path.

## Caveats

- **Resolution provenance:** imported edges are Goblin's Aether resolution, not yours.
  They're arguably more complete than the first-cut `PomResolver` (Aether handles BOM
  context and inheritance fully), but if you need *your* resolution semantics end to
  end, mine + resolve instead of importing.
- **Licence:** the Goblin dataset is CC-BY-4.0 — attribute it (cite the MSR'24 paper,
  DOI `10.1145/3643991.3644879`) if you redistribute anything derived from it.
- **No metadata:** scm/developers/licenses/dependencyManagement are not in Goblin;
  only `graph mine` captures those.
