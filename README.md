# CacheGenie

CacheGenie is a command-line tool designed to manage and analyze Maven artifact caches. It provides a structured workflow to discover artifacts, fetch metadata, analyze dependencies, and hydrate local storage.

### The "Genie" Workflow
To get the most out of CacheGenie, follow this natural progression:
1.  **`scan` (or `index`)**: Discover what versions of an artifact exist in remote repositories.
2.  **`fetch` (or `meta`)**: Download the "recipes" (POM files) for those discovered versions.
3.  **`map` (or `graph`)**: Analyze and visualize the dependency structures.
4.  **`hydrate` (or `cache`)**: Download the actual JAR files into your local repository.

## Key Features

-   **Remote Indexing**: Scan and index Maven artifacts from remote repositories (e.g., Maven Central).
-   **Artifact Caching**: Efficiently download and store artifacts in a local Maven cache.
-   **API Comparison**: Analyze and compare public API differences between different versions of the same artifact.
-   **Dependency Graphing**: Generate visualizations (DOT format) of artifact dependency trees.
-   **Local Cache Management**: Tools to inspect and manage your local Maven artifact storage.
-   **Metadata Management**: Fetch and sync POM files and other metadata for cached artifacts.

## Getting Started

### Prerequisites

-   **Java 22** or higher.
-   **Maven** (to build from source).

### Building

To build the project and create an executable shaded JAR:

```bash
mvn clean package
```

The resulting JAR will be located at `target/cachegenie-0.0.1.jar`.

## CLI Usage

Run CacheGenie using the `java -jar` command:

```bash
java -jar target/cachegenie-0.0.1.jar [GLOBAL-OPTIONS] COMMAND [COMMAND-OPTIONS] [ARGS...]
```

### Global Options

-   `-c, --cache=<cache>`: Path to the local Maven cache directory. Defaults to `~/.m2`.
-   `-r, --repo=<repo>`: URI of the remote repository to use. Defaults to Maven Central.
-   `-l, --log=<level>`: Set the logging level (`trace`, `debug`, `info`, `warn`, `error`). Defaults to `info`.
-   `-h, --help`: Show help information for the root command or a subcommand.
-   `-V, --version`: Print version information.

### Commands and Subcommands

#### `index` (alias: `scan`)
Indexes a remote repository for specific artifacts or performs a discovery scan.

```bash
index --gav <GAV-Selector>...
```
-   `-gav, --gav <GAV-Selector>`: One or more `group:artifact:version` strings to index.
-   **Discovery Scan**: Defaults to a "random walk" discovery if no GAV is provided:
    ```bash
    index
    ```
    This scans top-level groups and deep-scans for metadata, maintaining state in `cachegenie/work/index_build` for resumability.

#### `db`
Generates a local database from the previously indexed information.

```bash
db
```

#### `meta` (alias: `fetch`)
Scans cached metadata and downloads any missing POM files for the versions listed. It does not re-download existing POMs or JARs.

```bash
meta [-u] [-gav <gav>...]
```
-   `-gav, --gav <gav>`: One or more `group:artifact:version` strings to filter the meta-scan.
-   `-u, --update`: Force a retry for downloading POM files that were previously marked as missing.

#### `metadata` (alias: `gen-metadata`)
Synthesises `maven-metadata.xml` files into the local Maven repository (`~/.m2/repository`) straight from the discovery catalogue — **no network**. `graph mine` fetches only `.pom` files, so the local repo has POMs but no version metadata, which an *offline* resolve needs to handle version ranges and `LATEST`/`RELEASE`. This rebuilds that metadata from data `index-sync` already holds.

```bash
metadata [-gav <group[:artifact]>] [--repo-id <id>] [--also-plain]
```
-   `-gav, --gav <selector>`: `group` or `group:artifact` to scope to; omit for the whole catalogue.
-   `--repo-id <id>`: id used in the filename `maven-metadata-<id>.xml` (default `central`, matching the resolver's remote repository so the offline `SimpleLocalRepositoryManager` finds it).
-   `--also-plain`: also write plain `maven-metadata.xml` alongside.

Functionally equivalent to Central's metadata for resolution, but not a byte-for-byte copy: `<lastUpdated>` is stamped now and `<versions>` is written in best-effort publish-date order (cosmetic — Aether re-sorts internally). See "Offline resolution from local POMs" in `MINING.md`.

#### `cache` (alias: `hydrate`, `fill`)
Downloads and caches specific artifacts into the local Maven cache.

```bash
cache --gav <gav>
# OR
cache -g <group> -a <artifact> -v <version>
```
-   `-gav, --gav <gav>`: The `group:artifact:version` of the artifact.
-   `-g, --group-id <group>`: The Group ID.
-   `-a, --artifact-id <artifact>`: The Artifact ID.
-   `-v, --version <version>`: The version to download.

#### `compare`
Performs an API comparison between versions of an artifact.

```bash
compare --gav <gav>
# OR
compare -g <group> -a <artifact> -v <version> [-v <version> ...]
```
-   If only one version is provided, it is compared against the immediately preceding version (if found in the index).
-   If multiple versions are provided, it performs sequential comparisons.

#### `graph` (alias: `map`)
Builds, resolves, and queries the dependency graph. Subcommands:

-   **`graph mine`**: Fetch each catalogue version's POM (one GET, no fan-out) and store its raw facts + direct dependencies.
-   **`graph resolve`**: Project mined POMs into concrete dependency edges (parent/BOM/property resolution). Resolves almost everything **set-based**: first the context-free majority (literal versions, same-POM `${property}`/managed), then the inherited residue via recursive CTEs — parent-chain effective properties + managed versions, and import BOMs resolved transitively (BOM-of-BOM, each distinct BOM's managed versions computed once and attributed to consumers). Only embedded `${...}`, profiles, version ranges, and exclusions fall through to the parallel per-node pass (`--threads <n>`, default 8). `--set-based-only` skips the per-node pass. No network, so no `--rate`. Run after `graph mine`; idempotent and resumable.
-   **`graph deps`**: Build the direct-dependency graph for targeted/recent versions via an Aether descriptor read.
-   **`graph query`**: Run SQL (including recursive CTEs for transitive deps) against the DuckDB graph.
-   **`graph stats`**: Print graph and discovery-metadata statistics.
-   **`graph import-goblin` / `graph export-neo4j` / `graph push-neo4j`**: Seed from a Goblin CSV, or sync to a Neo4j read-side (see `GOBLIN-IMPORT.md`, `HYBRID-NEO4J.md`).

To get a dependency graph for a GAV out of the database, use `graph query` with a recursive CTE (or `graph stats` for summaries).

#### `insights` (alias: `insight`)
Ecosystem-evolution analysis over the DuckDB database — the "how does software arrive, evolve, update its dependencies, and get abandoned" reports. A richer companion to the quick `graph stats` snapshot; opens the database read-only. Subcommands:

-   **`insights arrivals`**: Catalogue coverage (how many versions actually have a publish date) and arrival rate over time — versions, new artifacts, and new groups per year.
-   **`insights lifecycle`**: Versions per artifact (mean/median/p90/p99), the release-count distribution, the single-release ("one and done") share, lifespan first→last, and update frequency (releases/year and the gap between releases).
-   **`insights abandonment`**: How many artifacts have gone quiet (no release in 2y/5y), a last-release-age survival curve, and whether single-release artifacts skew older.
-   **`insights churn`**: How often consecutive versions of an artifact **bump the version of a dependency they already declare** (new/removed deps excluded). Uses resolved `dependencies` edges by default (`graph resolve`); `--raw` uses as-declared `direct_dep` literals (`graph mine` only). `--top N` also lists the most-frequently-bumped dependencies.
-   **`insights resolution`** (alias `coverage`): How much of the catalogue has been mined and resolved — the catalogued→mined→resolved funnel (via `pom_meta.deps_resolved`), the un-mined backlog, and POMs that resolve ran on but whose declared deps produced no edge (ranges/properties/profiles the first-cut resolver skips). Use this to answer "does everything have resolved dependencies yet?".
-   **`insights report`**: Runs arrivals + lifecycle + abandonment + churn (summary) + resolution in one pass.

All subcommands accept `-g/--gav <group[:artifact]>` (group matches its subgroups too), `--since`/`--until <year>`, and `-f/--format table|csv|json`. Time-based metrics parse `meta_versions.published` (an ISO-8601 string) to a timestamp; check `insights arrivals` coverage before trusting the rates. The `churn` reports are window-function passes over the dependency tables — scope them with `-g`/`--since` and run `db optimize` first at full-Central scale.

## Database Schema

CacheGenie persists graph data in a local DuckDB database. For detailed information on the tables and how to query them, see the [DuckDB Graph Database Schema](DB_SCHEMA.md).

## License

This project is licensed under the terms of the `LICENSE` file included in the repository.