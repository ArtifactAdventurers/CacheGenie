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
Generates dependency graphs.

-   **`graph artifact`**: Generates a dependency graph for a specific artifact.
    ```bash
    graph artifact --gav <gav>
    # OR
    graph artifact -g <group> -a <artifact> -v <version> [-f <format>]
    ```
    -   `-f, --format <format>`: Output format (defaults to `dot`).
-   **`graph cache`**: Generates a combined graph representing the contents of the local cache.
    ```bash
    graph cache
    ```

## Database Schema

CacheGenie persists graph data in a local DuckDB database. For detailed information on the tables and how to query them, see the [DuckDB Graph Database Schema](DB_SCHEMA.md).

## License

This project is licensed under the terms of the `LICENSE` file included in the repository.