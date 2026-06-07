# DuckDB Graph Database Schema

CacheGenie uses a local [DuckDB](https://duckdb.org/) database to persist artifact dependency graphs. This allows for incremental updates, space-efficient storage, and complex SQL-based analysis of the Maven ecosystem.

## Database Location
The database file is located at:
`~/.m2/cachegenie/graph.db`

## Tables

### 1. `artifacts`
Maps Maven coordinates (GAV) to unique integer IDs to save space and improve query performance.

| Column | Type | Description |
| :--- | :--- | :--- |
| `id` | `INTEGER` | Primary Key. Unique identifier for the artifact. |
| `gid` | `VARCHAR` | Maven Group ID (e.g., `org.slf4j`). |
| `aid` | `VARCHAR` | Maven Artifact ID (e.g., `slf4j-api`). |
| `version` | `VARCHAR` | Version string (e.g., `2.0.9`). |
| `classifier` | `VARCHAR` | Optional Maven classifier (e.g., `sources`, `tests`). |

**Constraints:**
- `UNIQUE (gid, aid, version, classifier)`: Ensures no duplicate GAV entries.

### 2. `dependencies`
Stores the directed links between artifacts representing dependency relationships.

| Column | Type | Description |
| :--- | :--- | :--- |
| `parent_id` | `INTEGER` | ID of the artifact that has the dependency (FK to `artifacts.id`). |
| `child_id` | `INTEGER` | ID of the artifact being depended upon (FK to `artifacts.id`). |
| `scope` | `VARCHAR` | Maven dependency scope (e.g., `compile`, `test`, `provided`, `runtime`). |

**Constraints:**
- `PRIMARY KEY (parent_id, child_id, scope)`: Ensures unique relationships per scope.

### 3. `meta_artifacts`
Discovery metadata, one row per group:artifact. Replaces the legacy
`<gid>:<aid>.properties` / `meta/.../metadata.json` files. Written by `index`
and read by `meta`, `graph`, `meta-csv`, and `update` via `MetaRepository`.

| Column | Type | Description |
| :--- | :--- | :--- |
| `id` | `INTEGER` | Primary key (from `seq_meta_artifact_id`). |
| `gid` | `VARCHAR` | Maven Group ID. |
| `aid` | `VARCHAR` | Maven Artifact ID. |
| `uri` | `VARCHAR` | Source index URI the metadata was discovered from. |
| `latest` | `VARCHAR` | Latest version reported by Maven metadata. |
| `release` | `VARCHAR` | Release version reported by Maven metadata. |
| `updated` | `VARCHAR` | ISO-8601 timestamp of the upstream metadata's last update. |
| `generated` | `VARCHAR` | ISO-8601 timestamp this record was last written. |
| `status` | `VARCHAR` | `MavenMetaData.Status` name. |

**Constraints:**
- `UNIQUE (gid, aid)`: One metadata record per group:artifact.

### 4. `meta_versions`
One row per discovered version of a `meta_artifacts` row.

| Column | Type | Description |
| :--- | :--- | :--- |
| `ga_id` | `INTEGER` | FK to `meta_artifacts.id`. |
| `version` | `VARCHAR` | Version string. |
| `published` | `VARCHAR` | ISO-8601 publish timestamp (nullable). |
| `missing_pom` | `BOOLEAN` | True if the POM was sought but not found upstream. |

**Constraints:**
- `PRIMARY KEY (ga_id, version)`: One row per version per artifact.

> Timestamps in the meta tables are stored as ISO-8601 strings rather than
> native `TIMESTAMP` to avoid timezone conversion surprises; they round-trip
> through `Instant.toString()` / `Instant.parse()`.

## Sequences
- `seq_artifact_id`: Used to generate unique IDs for the `artifacts` table.
- `seq_meta_artifact_id`: Used to generate unique IDs for the `meta_artifacts` table.

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
