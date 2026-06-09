# DuckDB Graph Database Schema

CacheGenie uses a local [DuckDB](https://duckdb.org/) database to persist artifact dependency graphs. This allows for incremental updates, space-efficient storage, and complex SQL-based analysis of the Maven ecosystem.

## Database Location
The database file is located at:
`~/.m2/cachegenie/graph.db`

## Where the data comes from

There are two independent pipelines that fill this database, and the tables
split along that line:

- **Discovery metadata** (`meta_artifacts`, `meta_versions`) — *what exists* in
  the remote repository. Two sources write it, both through `MetaRepository`:
  - **`index-sync`** reads the repository's published Maven index
    (maven-indexer format) and is the bulk/primary source. It populates the
    coordinates and the per-version *file facts* (size, checksums, packaging,
    sources/javadoc flags, publish date), stamps `generated`, and derives
    `latest`/`release` (the newest version by publish date).
  - **`scan`/`index`** crawls HTML directory listings and parses each
    `maven-metadata.xml`, so it is the only source for `uri`, `updated`, and
    `status` (and also sets `latest`/`release`/`generated`).
  - **`meta`/`fetch`** downloads POM files and sets `missing_pom` when a POM is
    absent upstream.
- **Dependency graph** (`artifacts`, `dependencies`) — *how things relate*.
  Populated by `graph artifact` / `graph cache`, which resolve POMs with Maven
  Resolver (Aether) and persist the resulting nodes and edges via
  `GraphRepository`. This data comes from the POM `<dependencies>` — it is **not**
  in the index, which is why `index-sync` alone can't build the graph.

The "Source" column in each table below names the command(s) that write each
field. A field can be null simply because the pipeline that fills it hasn't run
for that row (e.g. an artifact known only from `index-sync` has no `latest`,
because only the HTML `scan` parses `maven-metadata.xml`).

## Tables

### 1. `artifacts`
Maps Maven coordinates (GAV) to unique integer IDs. **Populated by** `graph artifact` / `graph cache` (every node in a resolved dependency graph), via `GraphRepository`.

| Column | Type | Description |
| :--- | :--- | :--- |
| `id` | `INTEGER` | Primary Key (from `seq_artifact_id`). |
| `gid` | `VARCHAR` | Maven Group ID (e.g., `org.slf4j`). |
| `aid` | `VARCHAR` | Maven Artifact ID (e.g., `slf4j-api`). |
| `version` | `VARCHAR` | Version string (e.g., `2.0.9`). |
| `classifier` | `VARCHAR` | Optional Maven classifier (e.g., `sources`, `tests`). |

**Constraints:**
- `UNIQUE (gid, aid, version, classifier)`: Ensures no duplicate GAV entries.

### 2. `dependencies`
Directed dependency edges. **Populated by** `graph artifact` / `graph cache` from the resolved POM `<dependencies>` (Maven Resolver / Aether).

| Column | Type | Description |
| :--- | :--- | :--- |
| `parent_id` | `INTEGER` | ID of the artifact that has the dependency (FK to `artifacts.id`). |
| `child_id` | `INTEGER` | ID of the artifact being depended upon (FK to `artifacts.id`). |
| `scope` | `VARCHAR` | Maven dependency scope (e.g., `compile`, `test`, `provided`, `runtime`). |

**Constraints:**
- `PRIMARY KEY (parent_id, child_id, scope)`: Ensures unique relationships per scope.

### 3. `meta_artifacts`
Discovery metadata, one row per group:artifact. (Replaces the legacy
`<gid>:<aid>.properties` / `meta/.../metadata.json` files; nothing on disk now.)

| Column | Type | Source | Description |
| :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | scan / index-sync | Primary key (from `seq_meta_artifact_id`). |
| `gid` | `VARCHAR` | scan / index-sync | Maven Group ID. |
| `aid` | `VARCHAR` | scan / index-sync | Maven Artifact ID. |
| `uri` | `VARCHAR` | **scan** | Source index URI the metadata was discovered from (crawl only). |
| `latest` | `VARCHAR` | scan / index-sync | scan: `<latest>` from `maven-metadata.xml`. index-sync: newest version by publish date. |
| `release` | `VARCHAR` | scan / index-sync | scan: `<release>`. index-sync: newest non-`-SNAPSHOT` version by publish date. |
| `updated` | `VARCHAR` | **scan** | `<lastUpdated>` from `maven-metadata.xml` (ISO-8601). |
| `generated` | `VARCHAR` | scan / index-sync | ISO-8601 timestamp this row was last written/refreshed; also read by scan's `--max-age` freshness gate. |
| `status` | `VARCHAR` | **scan** | `MavenMetaData.Status` name. |

> `index-sync` sets `id`/`gid`/`aid`, `generated`, and the derived
> `latest`/`release`. `uri`, `updated`, and `status` come only from the HTML
> `scan` (which parses `maven-metadata.xml`), so they are null for an artifact
> known only from `index-sync` until a `scan` covers it.

**Constraints:**
- `UNIQUE (gid, aid)`: One metadata record per group:artifact.

### 4. `meta_versions`
One row per discovered version of a `meta_artifacts` row.

| Column | Type | Source | Description |
| :--- | :--- | :--- | :--- |
| `ga_id` | `INTEGER` | scan / index-sync | FK to `meta_artifacts.id`. |
| `version` | `VARCHAR` | scan / index-sync | Version string. |
| `published` | `VARCHAR` | **index-sync** (`FILE_MODIFIED`) | ISO-8601 publish timestamp. Usually null from `scan` (`maven-metadata.xml` has no per-version date). |
| `missing_pom` | `BOOLEAN` | **fetch** | True once `fetch`/`meta` tries the POM and it is absent upstream; false otherwise. |
| `packaging` | `VARCHAR` | **index-sync** | Maven packaging of the main artifact (`jar`, `pom`, `war`, …). |
| `file_extension` | `VARCHAR` | **index-sync** | Extension of the main artifact file. |
| `file_size` | `BIGINT` | **index-sync** | Size in bytes of the main artifact file. |
| `sha1` | `VARCHAR` | **index-sync** | SHA-1 of the main artifact file. |
| `sha256` | `VARCHAR` | **index-sync** | SHA-256 of the main artifact file (null for older index entries). |
| `has_sources` | `BOOLEAN` | **index-sync** | A `-sources` jar exists for this version. |
| `has_javadoc` | `BOOLEAN` | **index-sync** | A `-javadoc` jar exists for this version. |

The seven file-fact columns come from the published index, per version using its
*main* artifact — the largest file among the version's classifier-less records
(the main jar over its pom). Per-classifier (sources/javadoc/pom) checksums are
not stored. These columns are null for versions discovered only via the HTML
`scan`/`fetch` path.

**Constraints:**
- `PRIMARY KEY (ga_id, version)`: One row per version per artifact.

> Timestamps in the meta tables are stored as ISO-8601 strings rather than
> native `TIMESTAMP` to avoid timezone conversion surprises; they round-trip
> through `Instant.toString()` / `Instant.parse()`.

## Views

Created on demand by `db views` (and ensured by `db export`). They are
convenience wrappers so `graph query` can use friendly names instead of
hand-written joins.

| View | Description |
| :--- | :--- |
| `gav` | `artifacts` with a single `gid:aid:version` string column. |
| `dependents` | Every dependency edge flattened to readable coordinates: `dep_*` (the depended-upon artifact), `by_*` (the artifact that depends on it), and `scope`. |
| `version_ranges` | Per group:artifact `version_count`, `first_published`, `last_published` over `meta_versions` (the successor to the old `range.db` CSV dump). |

## Sequences
- `seq_artifact_id`: Used to generate unique IDs for the `artifacts` table.
- `seq_meta_artifact_id`: Used to generate unique IDs for the `meta_artifacts` table.

## Database management (`db` command)

- `db compact` — `CHECKPOINT` to flush the WAL (does not shrink the file in place); `db compact --rewrite` rebuilds into a fresh, smaller file (keeps a `.bak`). Useful after a large `index-sync --full`, whose staging table inflates the file.
- `db optimize` — create secondary indexes (`artifacts(gid,aid)`, `dependencies(child_id)`, `meta_artifacts(gid,aid)`) and `ANALYZE`.
- `db views` — create the convenience views above.
- `db export [-f parquet|csv|json] [-o DIR]` — `COPY` each table plus `version_ranges` out for external analysis (default parquet, into `~/.m2/cachegenie/export`).

## Querying via CLI
You can query the database directly using the `graph query` command:

```bash
cachegenie graph query "SELECT * FROM artifacts LIMIT 10"
```

## Example Analysis Queries

### Top 10 Most Depended-Upon Artifacts (In-Degree)
```sql
SELECT a.gid, a.aid, COUNT(*) as dependent_count
FROM dependencies d
JOIN artifacts a ON d.child_id = a.id
GROUP BY a.gid, a.aid
ORDER BY dependent_count DESC
LIMIT 10;
```

### Finding All Transitive Dependencies (Recursive)
DuckDB supports Recursive Common Table Expressions (CTEs):
```sql
WITH RECURSIVE transitive_deps AS (
    -- Base case: direct dependencies
    SELECT child_id, scope, 1 as depth
    FROM dependencies
    WHERE parent_id = (SELECT id FROM artifacts WHERE gid = 'org.springframework' AND aid = 'spring-context' AND version = '6.2.1')
    
    UNION
    
    -- Recursive step
    SELECT d.child_id, d.scope, td.depth + 1
    FROM dependencies d
    JOIN transitive_deps td ON d.parent_id = td.child_id
    WHERE td.depth < 5 -- Safety limit
)
SELECT DISTINCT a.gid, a.aid, a.version, td.scope
FROM transitive_deps td
JOIN artifacts a ON td.child_id = a.id;
```
