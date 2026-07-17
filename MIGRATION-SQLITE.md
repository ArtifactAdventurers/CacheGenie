# MIGRATION-SQLITE.md — moving CacheGenie's store off DuckDB

Status: **proposal / decision doc** — nothing implemented yet.
Context: single user, one-off migration acceptable, no backward-compatibility burden.

## 1. Why

CacheGenie's workload is hybrid: an upsert-heavy operational catalogue (index-sync,
mine, resolve) and whole-ecosystem analytics (insights, stats, export). DuckDB is
excellent at the second and structurally wrong for the first. The evidence is our
own changelog:

- **Point lookups table-scan.** DuckDB won't use a secondary ART index for
  `WHERE artifact_id = ?` / `(gid,aid)` filters (verified via EXPLAIN; per-id
  indexes were built and reverted as useless). Every row-by-row path degraded to
  O(table-scan) and had to be rewritten set-based — `IndexSyncWriter`, summary
  refresh, `resolve`'s passes. The set-based rewrites weren't optimisations; they
  were workarounds.
- **Single-writer rule** dictated architecture: `mine`'s drainer thread,
  page-disjoint reader/writer phases, `resolve`'s `duplicate()` dance (the only
  concurrent-connection use in the codebase).
- **Memory fragility on the 8GB Pi.** Default ~80% RAM ceiling stacked on the JVM:
  OOM-killed merges, "failed to pin block", and finally a native SIGSEGV
  (`BufferHandle::IsValid`) in the one writer path that lacked guards. Three copies
  of `applyMemoryGuards` exist purely to keep DuckDB from killing the process.
- Operational scar tissue: staging tables balloon `graph.db` (`db compact
  --rewrite` exists for this), lock-conflict special-casing in `GraphCmd`, the
  reader-never-open-with-writer invariant threaded through everything.

SQLite is the mirror image: superb indexed point lookups, WAL gives concurrent
readers + one writer for free, a few MB of memory, boringly reliable on
linux-aarch64 — and weak at massive analytical joins. So the choice is really
about where analytics run.

## 2. The choices

### Option A — All-SQLite (recommended)

SQLite is the only database in the codebase. `EcosystemStats` and `db export` are
rewritten to portable SQL + Java-side aggregation. DuckDB disappears from the jar
entirely; the **standalone `duckdb` CLI** remains available ad hoc for heavy
analytics or parquet export (`ATTACH 'genie.sqlite' (TYPE sqlite)` — zero code,
zero dependency).

- Pros: one engine, one file, one mental model; zero native DuckDB risk anywhere;
  the write path gets real B-tree indexes (row-by-row becomes viable again — see
  §5); simplest end-state; sqlite-jdbc (xerial) is the most battle-tested native
  lib on ARM there is.
- Cons: `insights` needs its ~20 report queries rewritten (`TRY_CAST`, `year()`,
  `date_diff`, `median`, `quantile_cont`, `INTERVAL` are all DuckDB-only —
  read-only code, but it breaks wholesale); parquet export drops out of the app
  (CSV/JSON stay, app-side); churn-style whole-table window passes will be slower
  (bounded: they're offline, single-user reports).

### Option B — SQLite system-of-record + DuckDB read-side

All writes go to SQLite. `insights`/`stats`/`export`/viewer open an **in-memory
DuckDB** that `ATTACH`es the SQLite file via the `sqlite_scanner` extension, so
their SQL stays (nearly) as-is.

- Pros: `EcosystemStats` and parquet export survive almost untouched; analytics
  keep vectorised speed; DuckDB crashes can no longer corrupt or lose writes
  (worst case: a report dies).
- Cons: keeps the native dependency and both drivers in the jar; the extension is
  downloaded/loaded at runtime (needs network once per DuckDB version, awkward on
  an offline Pi); two engines' SQL dialects forever; scanning SQLite from DuckDB
  is slower than native DuckDB tables, eroding the very advantage it preserves.

### Option C — Split stores

Catalogue + raw mining move to SQLite; the resolved graph (`artifacts`,
`dependencies`) and analytics stay in native DuckDB.

- Pros: smallest immediate diff (`resolve`, `insights`, viewer, exports untouched).
- Cons: **`resolve` still writes through DuckDB**, so the failure mode that
  triggered this discussion survives; two files whose cross-store joins
  (worklist selection joins `meta_versions` × `pom_meta`) need an attach or
  denormalisation; migration pain without the payoff. Included for completeness,
  not seriously proposed.

**Recommendation: Option A.** With one user, the analytics rewrite is a bounded,
read-only chore, and the end-state has no native-DB risk at all. Option B is the
fallback if, after the Phase-0 benchmark (§7), SQLite's set-based merge or the
insights rewrite look worse than expected.

## 3. What actually has to change (inventory)

Full sweep of every `jdbc:duckdb:` open (9 production sites) and all SQL.
Favourable findings first: **no exotic types** (INTEGER/BIGINT/VARCHAR/BOOLEAN
only; timestamps already ISO-8601 TEXT), and these all work on current SQLite
unchanged: `INSERT OR IGNORE`, `ON CONFLICT DO NOTHING/UPDATE` + `excluded.*`
(3.24+), `RETURNING` (3.35+), recursive CTEs, window functions (3.25+),
`FILTER (WHERE)` on aggregates (3.30+), `ANALYZE`, temp tables, `ATTACH`.
Watch-item: DuckDB's `NULLS LAST` defaults differ — explicit `NULLS LAST` needs
rewriting (SQLite 3.30+ supports the syntax, but check each `ORDER BY`).

| Class | Role | Effort | DuckDB-only surface |
| --- | --- | --- | --- |
| `PomResolver` (~940 ln) | writer | **Hard** | `duplicate()` concurrency; `regexp_extract` everywhere; `arg_min`; `SET` guards; sequences |
| `GraphRepository` (~840 ln) | writer | **Hard** | 7-appender `MiningWriter`; `read_csv_auto`+`split_part` (Goblin import); `COPY TO` (Neo4j CSV export); sequence |
| `MetaRepository` (~1000 ln) | writer | **Hard-ish** | `IndexStageLoader` appender; `arg_max` summary refresh; `CHECKPOINT`; `SET` guards; sequence |
| `DBAction` (~310 ln) | admin | Medium | `COPY FROM DATABASE`→`VACUUM INTO`; `COPY TO` export; `current_database()`; `CREATE OR REPLACE VIEW`→drop+create; `released` view's `TRY_CAST` |
| `EcosystemStats` (~440 ln) | read-only | Medium | `TRY_CAST`, `year()`, `date_diff`, `median`, `quantile_cont`, `INTERVAL` (~20 queries) |
| `GraphQueryService` (viewer) | read-only | **Easy** | driver class + conn string + RO property only; all SQL portable |
| `GraphPushNeo4jCmd`, `GraphCmd` (query/stats) | read-only | **Easy** | conn string, RO property, DuckDB lock-message handling (obsolete under WAL) |
| Tests (`PomResolverTest`, `EcosystemStatsTest`) | fixtures | Easy-Medium | open `jdbc:duckdb:` directly (~16 sites) |

Cross-cutting: 2 sequences (`seq_artifact_id`, `seq_meta_artifact_id`) +
`nextval()` at ~10 call sites → `INTEGER PRIMARY KEY` (rowid) with seeded
`sqlite_sequence`, or a plain max+1 counter held by each writer (writers are
single-threaded already). 3 copies of `applyMemoryGuards` → deleted, replaced by
one PRAGMA block (§6). Maven: single `duckdb_jdbc` pin in the root pom →
`org.xerial:sqlite-jdbc`.

## 4. Schema mapping

11 base tables port 1:1 (`artifacts`, `dependencies`, `meta_artifacts`,
`meta_versions`, `pom_meta`, `direct_dep`, `dependency_management`,
`pom_properties`, `pom_developers`, `pom_licenses` + staging equivalents as TEMP
tables). BOOLEAN→INTEGER 0/1 via sqlite-jdbc transparently. The 4 convenience
views recreate with `DROP VIEW IF EXISTS` + `CREATE VIEW`; `released` re-expressed
with `strftime`.

**Indexing strategy inverts.** Under DuckDB, secondary indexes were useless for
our filters; under SQLite they're the whole point. Day-one indexes:
`artifacts(gid,aid,version)` UNIQUE, `dependencies(child_id)`,
`dependencies(parent_id)`, `meta_artifacts(gid,aid)` UNIQUE,
`meta_versions(ga_id)`, `pom_meta(artifact_id)` UNIQUE,
`pom_meta(parent_gid,parent_aid,parent_version)`, `direct_dep(artifact_id)`,
`dependency_management(artifact_id)`, `pom_properties(artifact_id)`.
`db optimize` becomes "create these + ANALYZE".

## 5. The interesting consequence: `resolve` gets simpler

The set-based passes (A/B/C + the inherited recursive-CTE pass) exist because
DuckDB made per-node lookups O(scan). Under SQLite, the **per-node path becomes
the primary path**: parent-chain walks are a handful of indexed point lookups per
node, and `${...}` interpolation already lives in Java (`Reader.interpolate`).
That deletes the hardest SQL in the codebase (`regexp_extract`, `arg_min`,
BOM-fixpoint temp-table pyramid — ~500 lines) rather than porting it.

Concurrency: WAL mode gives N reader connections concurrently with the one writer
— strictly better than DuckDB's `duplicate()` model, and `mine`'s
drainer/page structure keeps working as-is (a single writer thread remains good
practice; it's just no longer *mandatory* that readers stay closed meanwhile).
Estimated resolve throughput needs the Phase-0 benchmark: per-node at even
2–5k nodes/s is a few hours for a 20M backlog — acceptable for a one-off with
resumability, vs the set-based passes' minutes. If that's too slow, passes A/B/C
(the context-free majority) re-express in portable SQL (literal joins — no regex
needed for pass A/C; pass B's `${name}` match can use `substr`/`instr` or a
registered Java function, which xerial's `Function.create` supports).

## 6. Writer mechanics

- **Appenders → batched prepared statements.** `IndexStageLoader` and
  `MiningWriter`'s 7 appenders become `PreparedStatement.addBatch()` +
  `executeBatch()` every ~1–5k rows inside one transaction per flush. SQLite
  sustains hundreds of thousands of rows/s this way on Pi-class hardware
  (benchmark to confirm on the actual SD/USB storage).
- **PRAGMA block** (replaces all `applyMemoryGuards`): `journal_mode=WAL`,
  `synchronous=NORMAL`, `busy_timeout=30000`, `cache_size=-262144` (256MB),
  `temp_store=FILE`, `foreign_keys=OFF`. Memory ceiling problems disappear —
  SQLite's cache is a cap, not a target.
- **Goblin import** (`read_csv_auto`) → Java CSV reader + batch insert.
  **Neo4j CSV export** (`COPY TO`) → plain Java CSV writer over a streamed
  ResultSet. **`db export`** keeps CSV/JSON app-side; parquet drops (use the
  duckdb CLI ad hoc if ever needed).
- **`db compact --rewrite`** → `VACUUM INTO 'new-file'` (simpler than today's
  ATTACH/COPY/DETACH dance); plain `compact` → `PRAGMA wal_checkpoint(TRUNCATE)`.
- `arg_max` summary refresh → `ROW_NUMBER() OVER (PARTITION BY ga_id ORDER BY
  published DESC)` per touched set, or a correlated `ORDER BY published DESC
  LIMIT 1` — both index-served.

## 7. Phasing

- **Phase 0 — spike + benchmark (do first, throwaway).** Add sqlite-jdbc; write a
  scratch harness that (a) bulk-inserts 5M staged index rows + merges, (b) mines
  a batch through batched inserts, (c) walks 10k parent chains point-lookup style
  — all on the Pi, against a copy of real data. Go/no-go numbers before any real
  code moves. Also validates sqlite-jdbc's aarch64 native lib.
- **Phase 1 — one-off data migration.** `db migrate-sqlite <out.sqlite>` command
  (both drivers on the classpath during transition): stream all 11 tables
  DuckDB→SQLite in batched transactions, seed id counters from `max(id)`, build
  §4 indexes, `ANALYZE`, row-count + spot-check verification. graph.db stays
  untouched as the fallback.
- **Phase 2 — easy readers.** Viewer, `graph query`/`stats`, `push-neo4j`:
  driver/connection/RO-property swap; delete DuckDB lock-message handling.
- **Phase 3 — writers.** `MetaRepository` (staging loader, sync writer, summary
  refresh), `GraphRepository` (`MiningWriter`, direct writer, Goblin import,
  Neo4j export), `DBAction`. Sequences replaced. Guards deleted.
- **Phase 4 — resolve.** Per-node-primary rewrite per §5; keeps the pager,
  worker readers (now plain connections), single collector-writer.
- **Phase 5 — analytics.** `EcosystemStats` rewrite (portable SQL + Java
  percentiles); `released` view; `db export` CSV/JSON.
- **Phase 6 — remove `duckdb_jdbc`**, delete `migrate-sqlite`, update docs
  (README, DB_SCHEMA, MINING, RUNBOOK, CLAUDE.md).

Each phase compiles + runs alone; Phases 2–5 can land in any order after 1.

## 8. Risks / open questions

- **Set-based merge throughput.** The index-sync full-bootstrap merge (~100M
  staged records) is DuckDB's best event. SQLite will be slower; Phase 0 measures
  whether it's "fine" (likely, once staged+indexed and batched) or needs the
  merge restructured into keyset chunks. Mitigating context: full bootstrap is a
  once-ever event; incremental chunks are small.
- **File size.** SQLite handles the volume (tens of GB is routine), but check
  disk headroom for WAL + `VACUUM INTO` (needs ~2× peak).
- **Insights fidelity.** `median`/`quantile_cont` move to Java — verify against
  current outputs on the same data before deleting the DuckDB path.
- **`graph query`** exposes raw SQL to the user — dialect changes silently
  (acceptable: single user, and it's explicitly an escape hatch).
- **sqlite-jdbc is also a native lib.** The bet isn't "no native code", it's
  "the most widely deployed native DB on earth vs a young one". Named for
  honesty.
