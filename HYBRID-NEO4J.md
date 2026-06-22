# Hybrid: DuckDB system-of-record + Neo4j read-side

CacheGenie keeps **DuckDB** as its system of record — it owns ingestion
(`index-sync`), the raw-POM mining tables, the relational discovery metadata, the
analytics (`graph query`, `db export`), and the dependency edges. That is the bulk
of the data and most of it is tabular, not graph-shaped, so DuckDB is the right home.

The canonical **dependency graph lives in Neo4j** — you load the
[Goblin](https://zenodo.org/records/15481588) dump once and keep it as-is.
CacheGenie does **not** rebuild or own it. DuckDB holds everything else (the
catalogue from `index-sync`, the raw-POM mining tables, and the edges CacheGenie
freshly resolves for releases that postdate the Goblin snapshot), and CacheGenie
**augments the live Neo4j graph in place** with that delta.

The primary path is `graph push-neo4j` — a direct Bolt push that MERGEs DuckDB's
delta into the running Neo4j. (`graph export-neo4j`, further down, is for the
different case of building a *fresh* Neo4j from DuckDB.)

This keeps the two real downsides of a Neo4j-primary design off the table: there's
no GPLv3 entanglement (CacheGenie uses only the Apache-2.0 Bolt *driver*, never the
GPL server), and the tabular meta/mining data stays in DuckDB where it fits.

## Augment the existing Neo4j graph (primary)

With your Goblin graph loaded and running, and DuckDB populated by CacheGenie
(`index-sync` → `graph mine` → `graph resolve`):

```bash
java -jar target/cachegenie.jar graph push-neo4j --uri bolt://localhost:7687 --user neo4j --password
# --password with no value prompts (no echo); or set NEO4J_PASSWORD; or omit entirely if auth is disabled
# optional: --since 30d   to push only releases published in a window
# optional: --batch-size  rows per write transaction (default 10000)
```

Auth is optional: pass `--password` with a value, pass `--password` alone to be
prompted, set the `NEO4J_PASSWORD` env var, or omit it entirely when the target
Neo4j runs with auth disabled (the push then connects with no credentials).

This connects over Bolt and `MERGE`s, in Goblin's schema, every release/edge in
DuckDB's graph into the existing Neo4j graph:

- `MERGE (:Artifact {id:"g:a"})` and `MERGE (:Release {id:"g:a:v"})` + `relationship_AR`,
- `MERGE (:Release)-[:dependency {targetVersion, scope}]->(:Artifact)`.

Because everything is `MERGE`, it's **idempotent** (re-running never duplicates) and
**additive** (it never rebuilds or disturbs the loaded Goblin baseline). Since
DuckDB holds only CacheGenie's freshly-resolved data — the Goblin baseline is in
Neo4j, not DuckDB — pushing "everything" is exactly the delta; `--since` just narrows
it by publish date. Re-run it whenever you've mined more.

> Uses the Apache-2.0 Neo4j Bolt driver bundled into the CacheGenie jar — no GPL
> obligation (that applies to the Neo4j *server*, which CacheGenie does not ship).
> Bolt `MERGE` is the right tool for an incremental top-up; for a one-shot bulk
> build of a brand-new graph, use the offline CSV path below instead.

## Build a fresh Neo4j from DuckDB (alternative)

If instead you want to construct a brand-new Neo4j database *from* DuckDB (rather
than top up an existing Goblin graph) — e.g. you've imported Goblin into DuckDB and
mined on top, and want one consolidated graph — export CSVs and bulk-load them
offline:

```bash
java -jar target/cachegenie.jar graph export-neo4j --out ~/neo4j-export
# add --with-metadata to also put name/url/scmUrl on Release nodes (run 'graph mine' first)
```

This writes four `neo4j-admin import` CSVs **in Goblin's schema**, so the result is
Weaver-compatible and conceptually the same shape as a loaded Goblin dump:

| File | Becomes |
| :--- | :--- |
| `releases.csv` | `:Release` nodes — `id` = `g:a:v`, `version`, `gid`, `aid` (+ `name`/`url`/`scmUrl` with `--with-metadata`) |
| `libraries.csv` | `:Artifact` nodes — `id` = `g:a` |
| `rel_ar.csv` | `(:Artifact)-[:relationship_AR]->(:Release)` versioning edges |
| `deps.csv` | `(:Release)-[:dependency {targetVersion, scope}]->(:Artifact)` dependency edges |

(The dependency edge targets the library and carries the concrete version on the
edge — exactly Goblin's model — so the same Cypher works against either graph.)

## Load into a fresh Neo4j 4.x database

The command prints the exact invocation; it is:

```bash
# Neo4j 5.x / 2025-2026 (CalVer) — database name is positional:
neo4j-admin database import full neo4j \
  --nodes=Release=~/neo4j-export/releases.csv \
  --nodes=Artifact=~/neo4j-export/libraries.csv \
  --relationships=relationship_AR=~/neo4j-export/rel_ar.csv \
  --relationships=dependency=~/neo4j-export/deps.csv \
  --id-type=string --overwrite-destination

# Neo4j 4.x uses the older form:
# neo4j-admin import --database=neo4j --nodes=... --relationships=... --id-type=STRING --skip-bad-relationships
```

`neo4j-admin database import full` builds a **fresh** database (fast, offline). Use it
for a full CacheGenie graph — e.g. after `graph import-goblin` + incremental
`graph mine` + `graph resolve`, export the whole thing and bulk-load it.

> With `--with-metadata`, add `--multiline-fields=true` if any property value
> contains newlines.

## Or merge the delta into an existing Goblin graph

If you already loaded a Goblin dump and just want to add CacheGenie's newer
(post-snapshot) releases, don't re-import — `LOAD CSV` with `MERGE` against the
running database (slower, but additive). Put the CSVs in Neo4j's `import/` dir:

```cypher
// new releases + their library grouping
LOAD CSV WITH HEADERS FROM 'file:///releases.csv' AS row
MERGE (a:Artifact {id: row.gid + ':' + row.aid})
MERGE (r:Release  {id: row.`id:ID(Release)`})
  ON CREATE SET r.version = row.version, r.gid = row.gid, r.aid = row.aid
MERGE (a)-[:relationship_AR]->(r);

// dependency edges
LOAD CSV WITH HEADERS FROM 'file:///deps.csv' AS row
MATCH (r:Release {id: row.`:START_ID(Release)`})
MERGE (a:Artifact {id: row.`:END_ID(Artifact)`})
CREATE (r)-[:dependency {targetVersion: row.targetVersion, scope: row.scope}]->(a);
```

(For very large deltas, batch with `CALL { ... } IN TRANSACTIONS`.)

## Querying

Once loaded, the graph traversals DuckDB does with recursive CTEs become native
Cypher, e.g. transitive dependents of a library:

```cypher
MATCH (a:Artifact {id: 'org.slf4j:slf4j-api'})<-[:dependency*1..]-(r:Release)
RETURN DISTINCT r.id;
```

…and you can point the Goblin Weaver at this database to reuse its CVE / freshness /
popularity enrichment.

## Refreshing

Keep DuckDB current (`index-sync` → `graph mine` → `graph resolve`) and run
`graph push-neo4j` again to top up the Neo4j graph with the newly-resolved delta.
The push is idempotent, so it's safe to run on every cycle (or on a schedule).
Nothing in CacheGenie depends on Neo4j being present — it's only touched when you
run `push-neo4j`/`export-neo4j`.
