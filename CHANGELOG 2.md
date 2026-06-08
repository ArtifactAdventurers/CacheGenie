# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog (https://keepachangelog.com/en/1.1.0/),
and this project adheres to Semantic Versioning (https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Global `-P/--progress` option that emits throttled progress messages to stderr during long-running commands (index/scan, meta/fetch, graph cache, analyse, hydrate).
- `graph cache` now resolves and persists the dependency graph for every artifact in the local cache to the DuckDB graph (skipping artifacts already present), instead of only listing them.
- Discovery metadata is now stored in the DuckDB database (`meta_artifacts` and `meta_versions` tables) via a new `MetaRepository`, alongside the dependency graph, so meta and graph can be queried together. See `DB_SCHEMA.md`.
- `graph stats` now reports discovery-metadata statistics (tracked group:artifacts, discovered versions, versions with missing POMs, average versions per artifact, top artifacts by version count and by missing POMs) and ensures both the graph and meta schemas exist so it works after a `scan`-only or `graph`-only run.

### Changed
- `index`, `meta`, `graph` (pattern resolve), `meta-csv`, and `update` now read/write discovery metadata through `MetaRepository` (DuckDB) instead of `.properties`/`.json` files.
- `migrate-meta` is repurposed as a one-time importer: it loads existing `.properties` and `.json` meta files into the DuckDB meta tables (previously it converted `.properties` to `.json`).
- Malformed POMs now log a WARN naming the file and are skipped, instead of emitting a raw `[Fatal Error] ...` line to stderr; processing continues. A custom SAX `ErrorHandler` replaces the JAXP default.
- Routed `CacheAction`'s stray `System.out`/`printStackTrace` output through SLF4J for consistent logging.

### Fixed
- Logging was silent in the shaded jar: `logback.xml` lived only in the pom-packaged reactor root (which ships no jar), so `-l/--log` reset the logger context to zero appenders. `logback.xml` now ships in the `cli` module, and `RootCmd` falls back to a basic console configuration if it is ever absent.
- `migrate-meta` was extremely slow on large caches because `MetaRepository.save` opened a new DuckDB connection per record. Added `MetaRepository.saveAll`, which reuses a single connection and commits in batches; the importer now uses it and reports progress under `-P`.

### Deprecated
- `MavenMetaData.save(File)` (legacy `.properties` writer) — no production path calls it now that metadata lives in DuckDB.

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