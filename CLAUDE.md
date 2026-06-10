# CLAUDE.md

Guidance for Claude Code (and other AI agents) working in this repository.

## What this is

CacheGenie is a command-line tool for managing and analyzing Maven artifact
caches. It discovers artifact versions in remote repositories, fetches their
POM "recipes", analyzes/visualizes dependency graphs, and hydrates the local
Maven cache with JARs. Dependency graphs are persisted to a local DuckDB
database for SQL analysis.

The user-facing workflow (the "Genie" workflow):
`scan/index` → `fetch/meta` → `map/graph` → `hydrate/cache`.

## Build & run

Requires **Java 22** and Maven. The build is a multi-module Maven reactor.

```bash
mvn clean package          # build all modules + produce the shaded jar
mvn test                   # run unit tests
```

The shaded, runnable jar is produced by the `release` module and written to
**`target/cachegenie.jar`** (see `release/pom.xml`'s shade `outputFile`).

> Note: the README refers to `target/cachegenie-0.0.1.jar`; the actual shade
> output is `target/cachegenie.jar`. Trust the pom.

Run it:

```bash
java -jar target/cachegenie.jar [GLOBAL-OPTIONS] COMMAND [ARGS...]
```

Main class: `dev.gruff.hardstop.cachegenie.cli.Main`.

## Module layout

The reactor root (`pom.xml`, groupId `dev.gruff.hardstop`, artifactId
`cachegenie`, packaging `pom`) aggregates five modules:

- **`streamer`** — standalone web/tree-crawling library
  (`dev.gruff.hardstop.treestreamer`). Streams and parses HTML directory
  listings (Maven Central-style index pages) with rate limiting and pluggable
  navigator policies. Uses jsoup. No dependency on other modules.
- **`core`** (`cachegenie-core`) — domain logic. Key packages under
  `dev.gruff.hardstop`:
  - `cachegenie` — the `CacheGenie` facade (resolves all on-disk paths),
    `MavenMetaData`, `WalkList`.
  - `cachegenie.entities` — Maven coordinate model: `ArtifactRef`, `GroupId`,
    `ArtifactId`, `Version`, `POM`, `POMStatus`.
  - `cachegenie.graph` — `GraphBuilder`, `GraphNode`, `DotViz` (DOT output),
    `GraphRepository` (DuckDB persistence).
  - `cachegenie.parsers` — `POMFileParser`, `ParserHelper`.
  - `cachegenie.utils` — `FileChecks`, `ObjectChecks`, `StringChecks`.
  - `resolver` — wrappers around Maven Resolver (Aether): `Resolver`,
    `VersionResolver`, `DependencyBuilder`, `RepositoryListener`.
- **`viewer`** — browser-based dependency viewer
  (`dev.gruff.hardstop.cachegenie.viewer`). A self-contained `HttpServer`
  (`com.sun.net.httpserver`, module `jdk.httpserver` — **no** web framework
  dependency) that serves a single-page UI from the classpath (`/webui`) plus
  read-only `/api` JSON endpoints. `GraphQueryService` is the read-only DuckDB
  query layer (forward/reverse/transitive deps + neighbourhood graph, all
  scope-filterable); `DependencyViewerServer` is the HTTP layer; `Json` is a
  tiny hand-rolled writer so the module needs no JSON library. Depends only on
  `core` (everything else is inherited from the root pom).
- **`cli`** — picocli command layer. Depends on `core`, `streamer`, `viewer`.
  `cli/.../cli/*Cmd.java` are the picocli command definitions;
  `cli/.../actions/*Action.java` hold the implementation each command invokes.
  `ViewCmd` is the thin wrapper that launches the `viewer` server (the command
  lives here, not in `viewer`, because it needs `RootCmd`'s global options).
- **`release`** — packaging-only module. Uses maven-shade-plugin to assemble
  `cli` and its dependencies into the single runnable jar.

Dependency direction: `release` → `cli` → (`core`, `streamer`, `viewer`);
`viewer` → `core`; `core` → maven-resolver. Don't introduce cycles — in
particular `viewer` must not depend on `cli`.

## CLI command map

Commands live in `cli/.../cli/`, dispatched from `RootCmd`. Aliases in parens.

- `index` (`scan`) — discover artifact versions in remote repos (or random-walk
  discovery if no `--gav`). State is kept under `~/.m2/cachegenie/work` for
  resumability. The random-walk crawls top-level groups concurrently on a worker
  pool: `--threads <n>` (default 8) sets the worker count, `--rate <req/min>`
  (default 100) is the **total** crawl budget shared across workers via one
  `RateLimiter`. Each worker uses its own `MavenMetaDataFactory`/parser
  (`DocumentBuilder` is not thread-safe); only the limiter is shared. Threaded
  `IndexCmd` → `IndexAction.index(args, rate, threads)` →
  `IndexBuilder(cg, rate, threads)`. On each metadata file `handleMeta` calls
  `MetaRepository.mergeDiscovered` (adds versions missing since last scan,
  preserves existing rows incl. `fetch`'s `missing_pom`, no deletes) and the
  scan prints a summary (files read, new artifacts, new versions, skipped-fresh).
  Undirected scans apply a freshness gate: `--max-age <dur>` (e.g. `7d`/`24h`,
  `0` = always; default 7d) skips re-fetching `maven-metadata.xml` for artifacts
  whose `meta_artifacts.generated` is within the window. The gate
  (`IndexBuilder.shouldFetchMeta`) runs in `MavenStyleHTMLRefNavigator` *before*
  the fetch and returns an empty link set (no fetch, no descent). It is bypassed
  for explicit `--gav` requests.
- `index-sync` (`central-sync`) — discover artifacts/versions from the
  repository's **published Maven index** (maven-indexer format) instead of
  crawling HTML. First run pulls the full index; later runs pull only incremental
  chunks. `--full` ignores local state and re-pulls everything. Local sync state
  lives in `~/.m2/cachegenie/work/indexer`. Uses `org.apache.maven.indexer:indexer-reader`.
  `IndexerSyncAction` branches on `reader.isIncremental()`: a **full** pull bulk-loads
  every ADD record into a staging table via DuckDB's Appender then merges set-based
  (`MetaRepository.IndexStageLoader` — collapses ~100M file-records to distinct
  versions once, builds the index once; the earlier per-row path took ~24h);
  **incremental** pulls apply the small diff row-by-row via
  `MetaRepository.IndexSyncWriter` (honouring ARTIFACT_REMOVE).
  `HttpResourceHandler`/`FileWritableResourceHandler` (in `actions/index`) are the
  remote/local `ResourceHandler`s. The full load grows `graph.db` with the staging
  table (run `db compact --rewrite` after). `--limit <n>` stops after N records and
  does NOT persist sync state (smoke-test the full path quickly; pair with
  `-c <scratch>`). Far fewer requests than `scan`; `scan` remains for targeted
  `--gav` lookups and immediacy.
- `meta` (`fetch`) — download missing POMs for indexed versions.
- `graph` (`map`) — subcommands: `artifact`, `cache`, `deps`, `query` (SQL
  against the DuckDB graph), `stats`. `deps` (`GraphDepsCmd`) builds the
  **direct**-edge graph for a worklist selected from the meta catalogue by
  `--gav` selectors and/or `--since <dur>` (versions published within a window,
  e.g. `30d`/`12w`); `MetaRepository.selectVersionsToGraph` does the selection in
  SQL (excludes `missing_pom` and already-graphed). `--list`/`--dry-run` prints
  the selected worklist + count and exits without graphing. Per version it reads
  the effective direct dependencies via
  `Resolver.directDependencies` (Aether `readArtifactDescriptor` — no transitive
  collection) and persists edges via `GraphRepository.persistDirect`; transitive
  trees are then recursive-CTE queries. Skips already-graphed versions
  (`GraphRepository.loadPresentKeys`) and `missing_pom` ones. `directDependencies`
  returns a `ResolveOutcome` (`OK`/`NOT_FOUND`/`RATE_LIMITED`/`TRANSIENT`,
  classified from the Aether exception chain): only `NOT_FOUND` marks the version
  `missing_pom`; `TRANSIENT` (5xx/timeout/connection) is left for retry; the first
  `RATE_LIMITED` (HTTP 429) **aborts the whole run** (sets a shared flag, queued
  workers bail) so a single overload of Maven Central stops us rather than
  poisoning data. Descriptor
  reads run on a worker pool (`--threads`, default 8) with a per-thread `Resolver`
  (Aether sessions aren't shareable); all DB writes are funnelled through one lock
  (DuckDB single-writer). (`artifact`/`cache` still use the older Aether transitive
  `resolveGraph`/`persist` path.)
- `cache` (`hydrate`, `fill`) — download JARs into the local repository.
- `compare` — API comparison between artifact versions.
- `db` — manage the DuckDB graph database. Subcommands: `compact` (CHECKPOINT +
  VACUUM to reclaim space), `optimize` (secondary indexes on `artifacts(gid,aid)`,
  `dependencies(child_id)`, `meta_artifacts(gid,aid)` + `ANALYZE`), `views`
  (create the `gav`, `dependents`, `version_ranges` convenience views), and
  `export` (`COPY` tables + `version_ranges` to parquet/csv/json via
  `-f/--format`, `-o/--out`). Implemented in `actions/DBAction.java`. (Replaced
  the old `.properties`→CSV dumper.)
- `meta-csv` — dump all discovery metadata to a CSV (reads `MetaRepository`).
- `analyse` — inspect the cache; subcommands `pom` (analyse local POMs in
  `~/.m2/repository`) and `meta` (report discovery-metadata counts from the
  DuckDB meta tables — no longer reads on-disk `.properties`).
- `view` (`web`, `ui`) — launch the browser-based dependency viewer over the
  DuckDB graph. `-a/--address/--host` (default `127.0.0.1`), `-p/--port`
  (default `8080`, `0` = free port), `--no-open` to skip auto-launching a
  browser. Opens the DB read-only; requires the graph to be populated first.

Global options (`RootCmd`): `-c/--cache` (local Maven cache, default `~/.m2`),
`-r/--repo` (remote repo, default Maven Central), `-l/--log`
(trace|debug|info|warn|error).

## On-disk data locations

Resolved by the `CacheGenie` facade in `core` (relative to `--cache`, default
`~/.m2`):

- `~/.m2/repository` — actual artifacts (JARs/POMs), standard Maven layout.
- `~/.m2/cachegenie/` — CacheGenie's own root.
  - `~/.m2/cachegenie/work` — index/discovery working state.
  - `~/.m2/cachegenie/meta` — discovery metadata and analysis results.
  - `~/.m2/cachegenie/graph.db` — DuckDB dependency graph (schema in
    `DB_SCHEMA.md`: `artifacts` and `dependencies` tables).

## Tests

JUnit 4. Tests live in two places:

- `core/src/test/java/...` — module-level tests (e.g. `POMFileParserTest`).
- `src/test/java/...` at the reactor root — additional integration-style tests
  (`MetaFactoryTest`, `TestGraphBuilder`, `TestIndexStatus`,
  `TestResolverBuilder`, `URIHelperTest`, etc.).

Run with `mvn test`.

## Conventions & gotchas

- **Java 22** language level across all modules; `release` is set in the
  compiler plugin. Don't use APIs newer than 22.
- Logging is **SLF4J + Logback**. `RootCmd` reconfigures Logback at runtime from
  `/logback.xml` on the classpath and sets the `LOG_LEVEL` system property from
  `-l`. The `logback.xml` / `application.properties` under root
  `src/main/resources` are the project resources; `application.properties`
  references Quarkus but the app is a plain picocli program — treat that file as
  vestigial, not a sign Quarkus is in use.
- Dependency versions are **not** fully centralized — module poms pin their own
  maven-resolver / jsoup / logback versions, which drift slightly from the root
  pom's `dependencyManagement`. When bumping a dependency, check each module pom,
  not just the root.
- `streamer` is intentionally decoupled from the rest of the project; keep it
  free of `cachegenie`/`resolver` imports so it stays reusable.
- New CLI features: add a `*Cmd` (picocli) in `cli/.../cli/`, register it in
  `RootCmd`'s `subcommands`, and put the logic in a matching `*Action` in
  `cli/.../actions/`.
- Viewer web assets live in `viewer/src/main/resources/webui` (a real,
  jar-packaged module) and are served from the classpath, so they end up in the
  shaded jar. Note that the root `src/main/resources` (logback.xml,
  application.properties) belongs to the `pom`-packaged reactor root, which
  produces no jar — don't rely on it being on the runtime classpath. Put
  anything that must ship in a real module's `resources`.

## Reference docs

- `README.md` — user-facing usage and full command/option reference.
- `DB_SCHEMA.md` — DuckDB graph schema and example analysis queries.
- `CHANGELOG.md` — Keep a Changelog format, SemVer.
