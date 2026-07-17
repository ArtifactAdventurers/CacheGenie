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
  - `cachegenie.graph` — `GraphBuilder`, `GraphNode`, `GraphRepository`
    (DuckDB persistence), `MetaRepository` (discovery catalogue), `PomResolver`
    (deferred resolution), and `EcosystemStats` (read-only evolution analytics
    backing the `insights` command).
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
  **incremental** pulls are processed **one chunk at a time in constant memory**:
  each chunk's ADDs stream into the staging table and merge set-based (the merge's
  largest-main-file-wins dedup replaces any in-memory buffering), its few
  ARTIFACT_REMOVEs apply via `MetaRepository.IndexSyncWriter`, and the local sync
  state is advanced past the chunk (atomic write of the index `.properties` with
  `nexus.index.last-incremental` bumped) before the next starts — so a killed or
  failed catch-up **resumes at the first unprocessed chunk**. `IndexerSyncAction`
  owns that state file; `IndexReader.close()` (which stores the full remote state,
  i.e. "all synced") is only called on complete success — never on failure, where
  it would silently skip the unprocessed chunks on the next run.
  `HttpResourceHandler`/`FileWritableResourceHandler` (in `actions/index`) are the
  remote/local `ResourceHandler`s; `HttpResourceHandler` streams are self-healing —
  a mid-stream reset reconnects with `Range: bytes=<offset>-` (`If-Range`-pinned to
  the original ETag, bounded consecutive retries + backoff) so the hour-long full
  pull survives CDN connection resets instead of restarting from byte 0. The staging merge applies DuckDB memory guards
  (spill `temp_directory` next to graph.db, `preserve_insertion_order=false`;
  `MetaRepository.applyMemoryGuards`) — without them the full merge OOM-killed at
  ~20M staged records. `--mem-limit <size>` (recommended for a full bootstrap; set
  below RAM minus JVM heap) and `--db-threads <n>` cap DuckDB further. Full pulls
  merge every `--merge-batch <n>` staged records (default 5M; `0` = one merge at
  the end) so peak merge memory is bounded by batch size, not pull size — safe
  because the staging merge upserts largest-file-wins (`ON CONFLICT DO UPDATE ...
  WHERE` strictly-larger file) and is idempotent across batches. The full
  load grows `graph.db` with the staging table (run `db compact --rewrite` after). `--limit <n>` stops after N records and
  does NOT persist sync state (smoke-test the full path quickly; pair with
  `-c <scratch>`). Far fewer requests than `scan`; `scan` remains for targeted
  `--gav` lookups and immediacy.
- `meta` (`fetch`) — download missing POMs for indexed versions.
- `graph` (`map`) — subcommands: `deps`, `mine`, `resolve`,
  `import-goblin`, `export-neo4j`, `push-neo4j`, `query` (SQL against the DuckDB graph), `stats`. (The legacy Aether `artifact`/`cache` subcommands were removed — use `mine`+`resolve`.) `deps` (`GraphDepsCmd`) builds the
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
  (DuckDB single-writer). `deps` also takes `--rate <req/min>` (shared
  `RateLimiter`, one permit per artifact; `0`=unlimited) to proactively throttle
  descriptor reads across workers.
  `mine` (`GraphMineCmd`) is the polite alternative to `deps`: it fetches **only the
  `.pom`** per version via `PomFetcher` (local `~/.m2` first, else one plain HTTP GET
  saved into `~/.m2` — no Aether, no checksum request, no descriptor read, so no
  parent/BOM fan-out), parses it with the thread-safe `RawPomParser` (per-thread
  `DocumentBuilder`), and stores the **raw, as-declared** POM (deps,
  dependencyManagement, parent ref, properties, scm, developers, licenses) via
  `GraphRepository.MiningWriter` into the `pom_meta`/`direct_dep`/
  `dependency_management`/`pom_properties`/`pom_developers`/`pom_licenses` tables.
  Worklist is un-mined versions (no `pom_meta` row). `--list` counts/previews via
  `selectVersionsToMine`; a real run **streams** the worklist in bounded keyset pages
  (`MetaRepository.selectVersionsToMineAfter`, ordered by `(gid,aid,version)`, cursor-paged
  — `PAGE_SIZE=50_000`) so it runs in constant memory even on a full-Central backlog
  (~14.8M versions) instead of materialising the whole list + every `Future` (the old path
  OOM'd at task submission). One reused worker pool; per page a fresh drainer/`MiningWriter`
  is opened *after* the page read closes its connection, so a reader and the writer are never
  open against `graph.db` at once. `--limit` is a total budget across pages/selectors (still
  useful for polite, resumable drips, not just to bound memory).
  Same `--gav`/`--since`/`--threads`/`--rate`/`--list`/429-abort as `deps`. Parents
  and BOMs are mined once as their own nodes, never re-downloaded per child.
  `PomFetcher`'s shared `HttpClient` uses **HTTP/1.1 deliberately** (not HTTP/2): the
  JDK client multiplexes all HTTP/2 requests onto one connection per origin and throws
  `too many concurrent streams` once `--threads` exceeds the server's stream cap — HTTP/1.1
  pools multiple connections instead, so don't "upgrade" it. Beyond the 429 abort, `mine`
  also has a **systemic-failure circuit breaker**: `TRANSIENT_ABORT_STREAK` (500) consecutive
  transient failures with no intervening success aborts the run (shared `aborted`/`abortReason`
  stop signal) so a broken endpoint/network doesn't burn the whole worklist as `transient`.
  `resolve` (`GraphResolveCmd` → `PomResolver`) is the deferred resolution pass: it
  walks the mined parent chain + import BOMs to fill managed versions, interpolates
  `${...}` properties, and projects resolvable direct edges into the concrete
  `dependencies` table (`pom_meta.deps_resolved`). First-cut — does **not** handle
  version ranges, profiles, `<exclusions>`, relocation, or inherited-metadata
  coalescing (raw values stay in `pom_meta`). Like `mine`, it **streams** the worklist
  in bounded keyset pages (ordered by `artifact_id`, `PAGE_SIZE=50_000`) so it runs in
  constant memory on a full-Central backlog (the old path materialised the whole `todo`
  list *and* cached every node — double OOM). The per-node work is DB-**read**-bound
  (loading parent/BOM/property rows), so it's parallelised: `--threads` (default 8)
  reader workers each own a `DuckDBConnection.duplicate()` connection (concurrent
  readers over one in-process DB via MVCC) and only **read**, producing resolved child
  ids / pending inserts; **all writes** (edge inserts, synthetic-artifact inserts,
  `deps_resolved` updates) funnel through a single writer connection on the collector
  thread, so DuckDB's single-writer rule still holds. This is the **only** place in the
  codebase that opens concurrent DuckDB connections (`duplicate()`); `mine` instead
  serialises because its workers do network I/O, not DB I/O. No network → no `--rate`.
  Per-reader parent/id caches and the writer's id cache are bounded LRUs. **Before** the
  per-node pass, a set-based fast path (`resolveSetBased`) resolves the context-free
  majority in a few hash-join statements — (A) literal versions, (B) exact `${name}`
  tokens defined concretely in the *same* POM's properties, (C) null versions pinned by
  a concrete non-import managed entry in the *same* POM — synthesising missing targets,
  inserting edges, and marking a POM resolved iff *every* dep is covered by A/B/C (the
  marking predicate is the exact complement of the passes). This matters because DuckDB
  table-scans `WHERE artifact_id = ?`/`(gid,aid)` filters (it won't use a secondary ART
  index for them — so per-`artifact_id` indexes were tried and reverted as useless), so
  the per-node path is scan-bound. A second set-based pass (`resolveInheritedSetBased`)
  then resolves the **parent-chain** residue (~95% of unresolved POMs have a parent): a
  recursive CTE over parent links builds per-node effective properties (nearest wins +
  `${other}` interpolation) and effective non-import managed versions (interpolated via
  those props), resolves each dep (literal / `${name}` / null-managed), and marks a POM
  resolved iff every dep is concrete. It also resolves **import BOMs transitively** (the
  import graph is followed to a fixpoint up to `BOM_DEPTH` levels, so BOM-of-BOM resolves;
  each reached BOM's effective managed versions — BOM + its parent chain — are computed once
  and attributed to consumers by join; parent-chain managed wins over BOM). Runs in bounded
  keyset batches (`INHERITED_BATCH`) so the per-node effective-property explosion doesn't OOM.
  Only embedded/partial `${...}`, profiles, ranges, and exclusions fall through to the
  per-node pass; `--set-based-only` skips that pass.
  `import-goblin` (`GraphImportCmd` → `GraphRepository.importGoblinEdges`) seeds
  `artifacts`/`dependencies` from a Goblin CSV export (Aether-resolved edges,
  equivalent to `graph deps`, for all of Central up to the dataset snapshot) via a
  set-based DuckDB load — minutes, no Central traffic. Then keep current with
  `mine --since <snapshot>`. See `GOBLIN-IMPORT.md`.
  `export-neo4j` (`GraphExportNeo4jCmd` → `GraphRepository.exportNeo4jCsv`) writes the
  graph as `neo4j-admin import` CSVs in Goblin's schema (Release/Artifact nodes,
  `relationship_AR`, `dependency` edges w/ targetVersion+scope) for the hybrid model:
  DuckDB stays system-of-record, Neo4j is an optional read-side for traversal/Weaver.
  CacheGenie only writes CSVs (no Neo4j embedding → no GPL entanglement). See
  `HYBRID-NEO4J.md`.
  `push-neo4j` (`GraphPushNeo4jCmd`) is the primary hybrid flow: it MERGEs DuckDB's
  graph delta into an **existing** Neo4j graph (a loaded Goblin dump) directly over
  Bolt — idempotent, additive, batched (`--uri/--user/--password/--since/--batch-size`).
  Neo4j stays the canonical graph; DuckDB is the working store that tops it up. Uses
  the Apache-2.0 `org.neo4j.driver:neo4j-java-driver` (added to `cli/pom.xml`) — the
  GPL applies to the Neo4j server, not the Bolt driver, so bundling it is fine.
- `cache` (`hydrate`, `fill`) — download JARs into the local repository.
- `compare` — API comparison between artifact versions.
- `db` — manage the DuckDB graph database. Subcommands: `compact` (CHECKPOINT +
  VACUUM to reclaim space), `optimize` (secondary indexes on `artifacts(gid,aid)`,
  `dependencies(child_id)`, `meta_artifacts(gid,aid)`, `meta_versions(ga_id)` +
  the POM-mining coord indexes + `ANALYZE`), `views`
  (create the `gav`, `dependents`, `version_ranges`, `released` convenience
  views; `released` normalises `meta_versions.published` to a real `TIMESTAMP`), and
  `export` (`COPY` tables + `version_ranges` to parquet/csv/json via
  `-f/--format`, `-o/--out`). Implemented in `actions/DBAction.java`. (Replaced
  the old `.properties`→CSV dumper.)
- `metadata` (`gen-metadata`) — synthesise `maven-metadata.xml` files into the local
  Maven repo (`~/.m2/repository`) from the discovery catalogue
  (`meta_artifacts`/`meta_versions`), **no network**. `mine` fetches only `.pom`, so
  the local repo lacks the version metadata an *offline* Aether resolve needs for
  version ranges / `LATEST` / `RELEASE`; this rebuilds it from data `index-sync`
  already has. Writes one `maven-metadata-<repo-id>.xml` per `(gid,aid)` (default id
  `central`, matching the `Resolver`'s remote so `SimpleLocalRepositoryManager` finds
  it; `--also-plain` also emits plain `maven-metadata.xml`). `<versions>` in
  publish-date order (cosmetic — Aether re-sorts); `<latest>`/`<release>` from the
  catalogue. Functionally equivalent to Central's metadata for resolution, not a
  byte-for-byte copy. `MetadataCmd` → `MetadataAction` →
  `MetaRepository.streamArtifactMetadata` (single ordered join grouped client-side,
  so heap is bounded regardless of catalogue size). `-gav <group[:artifact]>` scopes
  it. See `MINING.md` "Offline resolution from local POMs".
- `analyse` — inspect the cache; subcommand `pom` (analyse local POMs in
  `~/.m2/repository`). (The `meta` subcommand was removed — use `graph stats` for
  discovery-metadata counts; `meta-csv` was removed too — use `db export`.)
- `insights` (`insight`) — ecosystem-evolution analysis over `graph.db`, the
  richer companion to the `graph stats` snapshot. Subcommands: `arrivals`
  (coverage + versions/artifacts/groups per year), `lifecycle` (versions-per-artifact
  stats, release-count + lifespan distributions, single-release share, update
  frequency), `abandonment` (quiet-2y/5y + last-release-age survival curve), `churn`
  (how often consecutive versions of an artifact bump a dependency they already
  declare — new/removed deps excluded; resolved `dependencies` by default, `--raw`
  uses declared `direct_dep` literals, `--top N`), `resolution` (`coverage`) (the
  catalogued→mined→resolved funnel via `pom_meta.deps_resolved`, un-mined backlog,
  and resolve-ran-but-no-edges POMs — "does everything have resolved deps?"), and
  `report` (all, churn summary only). Shared opts `-g/--gav <group[:artifact]>` (subgroup-matching),
  `--since`/`--until <year>`, `-f/--format table|csv|json`. `InsightsCmd` (CLI,
  table/csv/json formatting) → `EcosystemStats` (core, read-only; SQL lives here so
  it's unit-tested against a synthetic DuckDB). Time-based metrics parse the
  ISO-8601 `published` string via `TRY_CAST(replace(...,'Z',''))`; `churn` is a
  window pass over the dependency tables — scope it and run `db optimize` at scale.
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
- `DB_SCHEMA.md` — DuckDB graph schema (incl. POM-mining tables) and example analysis queries.
- `CHANGELOG.md` — Keep a Changelog format, SemVer.
- `MINING.md` — running `graph mine` at scale politely: the Google GCS mirror (`-r`), rate guidance, resumable/chunked (`--limit`) runs.
- `GOBLIN-IMPORT.md` — seed the graph from the Goblin dataset (Neo4j 4.x dump → CSV → `import-goblin`); incl. the 4.x-vs-CalVer version caveat.
- `HYBRID-NEO4J.md` — DuckDB system-of-record + optional Neo4j read-side (`export-neo4j` for a fresh build, `push-neo4j` to top up an existing graph).
- `RUNBOOK-full-central.md` — end-to-end "zero to full Maven Central graph" pipeline.
