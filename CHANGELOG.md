# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog (https://keepachangelog.com/en/1.1.0/),
and this project adheres to Semantic Versioning (https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Global `-P/--progress` option that emits throttled progress messages to stderr during long-running commands (index/scan, meta/fetch, graph cache, analyse, hydrate).
- `graph cache` now resolves and persists the dependency graph for every artifact in the local cache to the DuckDB graph (skipping artifacts already present), instead of only listing them.

### Changed
- Malformed POMs now log a WARN naming the file and are skipped, instead of emitting a raw `[Fatal Error] ...` line to stderr; processing continues. A custom SAX `ErrorHandler` replaces the JAXP default.
- Routed `CacheAction`'s stray `System.out`/`printStackTrace` output through SLF4J for consistent logging.

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