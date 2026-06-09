# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog (https://keepachangelog.com/en/1.1.0/),
and this project adheres to Semantic Versioning (https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- New `index-sync` (`central-sync`) command: discover artifacts and versions from the repository's **published Maven index** (the maven-indexer format Central publishes under `.index/`) instead of crawling HTML directory listings. The first run downloads the full index; subsequent runs pull only the incremental chunks published since, tracked via local state under `~/.m2/cachegenie/work/indexer`. `--full` forces a complete re-pull. Records stream straight into the DuckDB meta tables. Uses the dependency-light `org.apache.maven.indexer:indexer-reader` (7.1.6); `HttpResourceHandler`/`FileWritableResourceHandler` provide the remote/local resource access, `IndexerSyncAction` drives it, and `MetaRepository.IndexSyncWriter` does the streaming upsert. The HTML `scan` remains for targeted `--gav` lookups and immediacy.
- `index`/`scan` undirected discovery now applies a freshness gate: `--max-age <dur>` (e.g. `7d`, `24h`, `30m`; `0` = always check; default `7d`) skips re-fetching `maven-metadata.xml` for any artifact whose metadata was last written within the window. The check happens before the fetch (no request, no descent) using `meta_artifacts.generated` loaded once into an in-memory map. Explicit `--gav` requests always check (gate bypassed). The scan summary reports how many were skipped as fresh.
- `index`/`scan` random-walk discovery now crawls top-level groups concurrently. `--threads <n>` (default 8) sets the worker count; `--rate <req/min>` (default 100) is now the **total** request budget shared across all workers via a single `RateLimiter`, so politeness is preserved while hiding per-request latency (previously the crawl was single-threaded and stuck at roughly one request per second regardless of `--rate`). Each worker uses its own `MavenMetaDataFactory`/parser since `DocumentBuilder` is not thread-safe. Added `NavigatorPolicyBuilder.rateLimit(RateLimiter)` so policies can share one limiter.
- `index`/`scan` gains a `--rate <req/min>` option to set the crawl's rate limit (default 100 requests/minute), so the remote-repository politeness throttle is configurable from the CLI instead of hardcoded in `IndexBuilder`.
- Global `-P/--progress` option that emits throttled progress messages to stderr during long-running commands (index/scan, meta/fetch, graph cache, analyse, hydrate).
- `graph cache` now resolves and persists the dependency graph for every artifact in the local cache to the DuckDB graph (skipping artifacts already present), instead of only listing them.
- Discovery metadata is now stored in the DuckDB database (`meta_artifacts` and `meta_versions` tables) via a new `MetaRepository`, alongside the dependency graph, so meta and graph can be queried together. See `DB_SCHEMA.md`.
- `graph stats` now reports discovery-metadata statistics (tracked group:artifacts, discovered versions, versions with missing POMs, average versions per artifact, top artifacts by version count and by missing POMs) and ensures both the graph and meta schemas exist so it works after a `scan`-only or `graph`-only run.

### Changed
- `index`/`scan` now **merges** discovered metadata instead of mirroring it: it adds versions missing since the last scan and refreshes the artifact's top-level fields, but no longer deletes versions or overwrites a version's `missing_pom`/`published` (so flags set by `fetch` survive a re-scan). Implemented as `MetaRepository.mergeDiscovered`; `handleMeta` uses it in place of the full-mirror `save`.
- `index`/`scan` prints a summary at the end: metadata files found, new artifacts vs. already-known, and total new versions added (across new and updated artifacts).
- The `db` command is repurposed from a legacy `.properties`→CSV dumper into a DuckDB management command with subcommands: `compact` (CHECKPOINT + VACUUM to reclaim space), `optimize` (secondary indexes + ANALYZE), `views` (create the `gav`, `dependents`, and `version_ranges` convenience views), and `export` (COPY tables + `version_ranges` to parquet/csv/json). The old per-artifact version date-range dump (`range.db`) is now the `version_ranges` view.
- `index`, `meta`, `graph` (pattern resolve), `meta-csv`, and `update` now read/write discovery metadata through `MetaRepository` (DuckDB) instead of `.properties`/`.json` files.
- `migrate-meta` is repurposed as a one-time importer: it loads existing `.properties` and `.json` meta files into the DuckDB meta tables (previously it converted `.properties` to `.json`).
- Malformed POMs now log a WARN naming the file and are skipped, instead of emitting a raw `[Fatal Error] ...` line to stderr; processing continues. A custom SAX `ErrorHandler` replaces the JAXP default.
- Routed `CacheAction`'s stray `System.out`/`printStackTrace` output through SLF4J for consistent logging.

- `migrate-meta` is now resumable. It streams records straight into DuckDB (no longer building an in-memory map of every record, which exhausted the heap on large caches with hundreds of thousands of meta files), and skips coordinates already imported so an interrupted run can be restarted without redoing work. For `metadata.json` the coordinate is derived from its Maven-style path, so already-imported files are skipped without being re-read. A new `--fresh` flag clears the meta tables for a full re-import.

### Fixed
- `migrate-meta` discarded a leading `null`/literal value when hand-parsing `metadata.json`, so versions with no publish date (e.g. missing-POM versions) read the next key name as their date and logged `Failed to parse published date 'missingPom'`. `extractJSON` now distinguishes quoted strings from unquoted literals.
- `migrate-meta` hit `Duplicate key ... violates primary key constraint` writing versions, because it deleted and re-inserted the same key within one transaction (a DuckDB ART-index limitation). Version writes now use `INSERT ... ON CONFLICT DO UPDATE`, which also makes re-running the import idempotent.
- Logging was silent in the shaded jar: `logback.xml` lived only in the pom-packaged reactor root (which ships no jar), so `-l/--log` reset the logger context to zero appenders. `logback.xml` now ships in the `cli` module, and `RootCmd` falls back to a basic console configuration if it is ever absent.
- `migrate-meta` was extremely slow on large caches because `MetaRepository.save` opened a new DuckDB connection per record. Added `MetaRepository.saveAll`, which reuses a single connection and commits in batches; the importer now uses it and reports progress under `-P`.

### Removed
- The `migrate-meta` command (and `MigrateMetaAction`), the one-time importer that loaded legacy `.properties`/`metadata.json` files into the DuckDB meta tables. The migration is complete; metadata now lives solely in DuckDB. (The `MetaRepository` bulk-writer and `MavenMetaData.loadJSON` that only served it are now unused.)
- `CreateDBAction`, the legacy `db` command implementation that read `.properties` files (no longer produced) and dumped CSV `index.db`/`range.db` files. Replaced by `DBAction` and the new `db` subcommands.
- `MavenMetaData.save(File)`, the only code that wrote a legacy meta file (the `.properties` form). It had no callers — metadata is now written exclusively to the DuckDB meta tables — so nothing in the tool writes `.properties` or `metadata.json` meta files any more; those formats are read-only import sources for `migrate-meta`.

## [0.1.0] - 2025-10-11

Inferred SemVer bump: Minor (new features added without known breaking changes).

### Added
- Reintroduced Maven resolver components to enable artifact/version resolution (Resolver, VersionResolver, RepositoryListener). ([ad212d4](https://github.com/ArtifactAdventurers/CacheGenie/commit/ad212d4))
- Began graphing support with a new CLI command and resolver integration (GraphCmd, DependencyTree). ([a96422b](https://github.com/ArtifactAdventurers/CacheGenie/commit/a96422b))
- Expanded graph capabilities with builders and visualization utilities (GraphBuilder, GraphNode, DotViz); added tests. ([d79c01b](https://github.com/ArtifactAdventurers/CacheGenie/commit/d79c01b))
- Linked in Hardstop modules and introduced new CLI/actions and utilities: CacheCmd, CompareAction/CompareCmd, DepOps; entity and parsing helpers (POMFileParser, ArtifactRef/GroupId/Version, FileChecks, etc.). ([5f144eb](https://github.com/ArtifactAdventurers/CacheGenie/commit/5f144eb))

### Changed
- Updated CLI commands and stats to support graphing and new resolver pieces (GraphCmd, RootCmd, StatsAction). ([d79c01b](https://github.com/ArtifactAdventurers/CacheGenie/commit/d79c01b), [a96422b](https://github.com/ArtifactAdventurers/CacheGenie/commit/a96422b))
- Enhanced caching and comparison flows (CacheAction, CompareCmd). ([5f144eb](https://github.com/ArtifactAdventurers/CacheGenie/commit/5f144eb))
- Adjusted Resolver internals alongside new features. ([5f144eb](https://github.com/ArtifactAdventurers/CacheGenie/commit/5f144eb), [d79c01b](https://github.com/ArtifactAdventurers/CacheGenie/commit/d79c01b), [a96422b](https://github.com/ArtifactAdventurers/CacheGenie/commit/a96422b))

### Removed
- Replaced the initial DependencyTree with newer graph structures. ([d79c01b](https://github.com/ArtifactAdventurers/CacheGenie/commit/d79c01b))

[Unreleased]: https://github.com/ArtifactAdventurers/CacheGenie/compare/0.1.0...HEAD
[0.1.0]: https://github.com/ArtifactAdventurers/CacheGenie/compare/0.0.1...0.1.0