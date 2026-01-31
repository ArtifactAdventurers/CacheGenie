# CacheGenie

CacheGenie is a command-line tool designed to manage and analyze Maven artifact caches. It provides a suite of tools to index remote repositories, cache artifacts locally, analyze API differences between versions, and visualize dependency graphs. It is particularly useful for developers and DevOps engineers who need to understand artifact evolution and dependency structures within the Maven ecosystem.

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

#### `index`
Indexes a remote repository for specific artifacts or performs a discovery scan.

```bash
index -gav=<GAV-Selector>...
```
-   `-gav, --group-artifact-version=<GAV-Selector>`: One or more `group:artifact:version` strings to index.
-   **Discovery Scan**: Use `?` to perform a "random walk" discovery of the remote repository:
    ```bash
    index -gav="?"
    ```
    This scans top-level groups and deep-scans for metadata, maintaining state in `cachegenie/work/index_build` for resumability.

#### `db`
Generates a local database from the previously indexed information.

```bash
db
```

#### `meta`
Scans cached metadata and downloads any missing POM files for the versions listed. It does not re-download existing POMs or JARs.

```bash
meta [-u] [-gav=<GAV-Selector>...]
```
-   `-gav, --group-artifact-version=<GAV-Selector>`: One or more `group:artifact:version` strings to filter the meta-scan.
-   `-u, --update`: Force a retry for downloading POM files that were previously marked as missing.

#### `cache`
Downloads and caches specific artifacts into the local Maven cache.

```bash
cache -g=<groupID> -a=<artifactID> -v=<version>...
```
-   `-g, --gid=<groupID>`: The Group ID of the artifact.
-   `-a, --aid=<artifactID>`: The Artifact ID.
-   `-v, --versions=<version>`: List of versions to download.

#### `compare`
Performs an API comparison between versions of an artifact.

```bash
compare -g=<groupID> -a=<artifactID> -v=<version1> [-v=<version2> ...]
```
-   If only one version is provided, it is compared against the immediately preceding version (if found in the index).
-   If multiple versions are provided, it performs sequential comparisons.

#### `graph`
Generates dependency graphs.

-   **`graph artifact`**: Generates a dependency graph for a specific artifact.
    ```bash
    graph artifact -g=<groupID> -a=<artifactID> -v=<version> [-f=<format>]
    ```
    -   `-f, --format=<format>`: Output format (defaults to `dot`).
-   **`graph cache`**: Generates a combined graph representing the contents of the local cache.
    ```bash
    graph cache
    ```

## License

This project is licensed under the terms of the `LICENSE` file included in the repository.